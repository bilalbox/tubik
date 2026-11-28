package com.github.libretube.services

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.enums.NotificationId
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.toLocalDate
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.DownloadHelper.getNotificationId
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.receivers.NotificationReceiver
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_PAUSE
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_RESUME
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_STOP
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import com.github.libretube.ui.activities.MainActivity
import java.net.HttpURLConnection
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.sink

/**
 * Tracks download queue items with explicit lifecycle states so paused/failed items are never
 * auto-resumed.
 */
internal class DownloadQueueTracker {
    enum class State {
        QUEUED,
        RUNNING,
        PAUSED,
        FAILED,
    }

    private val states = LinkedHashMap<Int, State>()

    @Synchronized
    fun enqueue(id: Int) {
        if (states[id] == State.RUNNING) return
        states[id] = State.QUEUED
    }

    @Synchronized
    fun markRunning(id: Int) {
        states[id] = State.RUNNING
    }

    @Synchronized
    fun markPaused(id: Int) {
        states[id] = State.PAUSED
    }

    @Synchronized
    fun markFailed(id: Int) {
        states[id] = State.FAILED
    }

    @Synchronized
    fun remove(id: Int) {
        states.remove(id)
    }

    @Synchronized fun stateOf(id: Int): State? = states[id]

    @Synchronized fun isRunning(id: Int): Boolean = states[id] == State.RUNNING

    @Synchronized fun activeCount(): Int = states.values.count { it == State.RUNNING }

    @Synchronized
    fun nextQueuedId(): Int? = states.entries.firstOrNull { it.value == State.QUEUED }?.key

    @Synchronized
    fun clear() {
        states.clear()
    }
}

/** Download service with custom implementation of downloading using [HttpURLConnection]. */
class DownloadService : LifecycleService() {
    private val binder = LocalBinder()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)

    private lateinit var notificationManager: NotificationManager
    private lateinit var summaryNotificationBuilder: Builder

    private val downloadQueue = DownloadQueueTracker()
    private val _downloadFlow = MutableSharedFlow<Pair<Int, DownloadStatus>>()
    val downloadFlow: SharedFlow<Pair<Int, DownloadStatus>> = _downloadFlow

    /** Cache that contains all the already-loaded video info. */
    private val cachedStreamsInfo: MutableMap<String, Streams> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()
        IS_DOWNLOAD_RUNNING = true
        notifyForeground()
        sendBroadcast(Intent(ACTION_SERVICE_STARTED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val downloadId = intent?.getIntExtra("id", -1)
        when (intent?.action) {
            ACTION_DOWNLOAD_RESUME -> resume(downloadId!!)
            ACTION_DOWNLOAD_PAUSE -> pause(downloadId!!)
            ACTION_DOWNLOAD_STOP -> stop(downloadId!!)
            ACTION_RESUME_ALL -> resumeAll()
        }

        val downloadData =
            intent?.parcelableExtra<DownloadData>(IntentData.downloadData)
                ?: return START_NOT_STICKY
        val videoId = downloadData.videoId

        serviceScope.launch {
            val streams = loadStreamsInfo(videoId) ?: return@launch

            storeVideoMetadata(videoId, streams)

            val downloadItems = streams.toDownloadItems(downloadData)
            for (downloadItem in downloadItems) {
                start(downloadItem)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun loadStreamsInfo(videoId: String): Streams? {
        if (cachedStreamsInfo.contains(videoId)) return cachedStreamsInfo[videoId]

        val streams =
            try {
                withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getStreams(videoId)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.stackTraceToString())
                toastFromMainDispatcher(e.localizedMessage.orEmpty())
                return null
            }

        cachedStreamsInfo[videoId] = streams
        return streams
    }

    private suspend fun storeVideoMetadata(videoId: String, streams: Streams) {
        val thumbnailTargetPath = getDownloadPath(DownloadHelper.THUMBNAIL_DIR, videoId)

        val download =
            Download(
                videoId,
                streams.title,
                streams.description,
                streams.uploader,
                streams.duration,
                streams.uploadTimestamp?.toLocalDate(),
                thumbnailTargetPath,
                streams.uploaderUrl,
                streams.views,
                streams.likes,
                streams.dislikes,
            )
        Database.downloadDao().insertDownload(download)

        for (chapter in streams.chapters) {
            val downloadChapter =
                DownloadChapter(
                    videoId = videoId,
                    name = chapter.title,
                    start = chapter.start,
                    thumbnailUrl = chapter.image,
                )
            Database.downloadDao().insertDownloadChapter(downloadChapter)
        }

        // asynchronously load the remaining metadata
        // this allows the main thread to already start the actual download items (i.e. video/audio)
        // while the thumbnail and SponsorBlock segments are loaded in the background
        serviceScope.launch(Dispatchers.IO) {
            downloadExtraVideoMetadata(videoId, streams.thumbnailUrl, thumbnailTargetPath)
        }
    }

    /** Download the thumbnail and SponsorBlock segments for the given [videoId]. */
    private suspend fun downloadExtraVideoMetadata(
        videoId: String,
        thumbnailUrl: String,
        thumbnailTargetPath: Path,
    ) {
        coroutineScope {
            launch {
                val segmentData =
                    try {
                        val categories = PlayerHelper.getSponsorBlockCategories()
                        MediaServiceRepository.instance.getSegments(
                            videoId,
                            categories.map { it.key },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG(), "failed to download SponsorBlock segments for $videoId")
                        Log.e(TAG(), e.stackTraceToString())
                        return@launch
                    }

                Database.downloadDao()
                    .insertSponsorBlockSegments(
                        segmentData.segments.map { it.toDownloadSegment(videoId) },
                    )
            }

            launch {
                try {
                    ImageHelper.downloadImage(
                        this@DownloadService,
                        thumbnailUrl,
                        thumbnailTargetPath,
                    )
                } catch (e: Exception) {
                    Log.e(TAG(), "failed to download image $thumbnailUrl")
                    Log.e(TAG(), e.stackTraceToString())
                }
            }
        }
    }

    /**
     * Download file and emit [DownloadStatus] to the collectors of [downloadFlow] and notification.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun selectFormatAndDownloadFile(item: DownloadItem) {
        val streams = loadStreamsInfo(item.videoId)
        val provider = streams?.let { createDownloadProvider(item, it) }
        if (provider == null) {
            downloadQueue.markFailed(item.id)
            startNextEnqueueDownload()
            stopServiceIfDone()
            return
        }
        downloadFile(item, provider)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun createDownloadProvider(
        item: DownloadItem,
        streams: Streams,
    ): DownloadProvider? {
        val transportContext =
            MediaServiceRepository.instance.getStreamTransportContext(item.videoId)
        return if (item.type == FileType.SUBTITLE) {
            // subtitles are always plain files and don't use SABR
            val subtitle = streams.subtitles.firstOrNull { it.code == item.language }
            if (subtitle == null) {
                Log.e(TAG(), "No matching subtitle found for language ${item.language}")
                emitSelectionFailure(item.id, "No matching subtitle found")
                null
            } else {
                RawByteStreamDownloadProvider(subtitle.url!!.toHttpUrl(), transportContext)
            }
        } else {
            val selectedStream = selectMatchingStream(streams, item)
            when {
                selectedStream == null -> {
                    Log.e(
                        TAG(),
                        "No matching stream found for ${item.fileName} format=${item.format} quality=${item.quality}",
                    )
                    emitSelectionFailure(item.id, "No matching stream found")
                    null
                }
                selectedStream.url?.startsWith("http") == true ->
                    RawByteStreamDownloadProvider(
                        selectedStream.url!!.toHttpUrl(),
                        transportContext,
                    )
                else -> SabrDownloadProvider(item, streams, selectedStream, transportContext)
            }
        }
    }

    private suspend fun emitSelectionFailure(id: Int, message: String) {
        toastFromMainDispatcher(getString(R.string.download_failed))
        _downloadFlow.emit(id to DownloadStatus.Error(message, null))
    }

    private class DownloadLoopState(var totalRead: Long, var retries: Int = 0)

    /**
     * Starts and progresses until the download is canceled or finished.
     *
     * You should probably not call this directly, call [selectFormatAndDownloadFile].
     */
    private suspend fun downloadFile(
        item: DownloadItem,
        downloadProvider: DownloadProvider,
    ) {
        downloadQueue.markRunning(item.id)
        val notificationBuilder = getNotificationBuilder(item)
        setResumeNotification(notificationBuilder, item)

        val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
        val loopState = DownloadLoopState(totalRead = item.path.fileSize())
        try {
            while (downloadQueue.isRunning(item.id) && !item.isFinished) {
                val shouldContinue =
                    stepDownloadChunk(item, downloadProvider, sink, notificationBuilder, loopState)
                if (!shouldContinue) break
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { sink.flush() }
                runCatching { sink.close() }
            }
        }

        finalizeDownloadLoop(item, notificationBuilder)
        startNextEnqueueDownload()
        stopServiceIfDone()
    }

    private suspend fun stepDownloadChunk(
        item: DownloadItem,
        downloadProvider: DownloadProvider,
        sink: okio.BufferedSink,
        notificationBuilder: Builder,
        loopState: DownloadLoopState,
    ): Boolean {
        return try {
            when (val result = downloadProvider.downloadNextChunk(item, sink)) {
                DownloadProgressResult.DownloadComplete -> {
                    setPauseNotification(notificationBuilder, item, true)
                    _downloadFlow.emit(item.id to DownloadStatus.Completed)
                    downloadQueue.remove(item.id)
                    false
                }
                DownloadProgressResult.Failed ->
                    handleChunkFailure(item, notificationBuilder, loopState)
                is DownloadProgressResult.Progressed -> {
                    loopState.retries = 0
                    loopState.totalRead += result.bytes
                    _downloadFlow.emit(
                        item.id to
                            DownloadStatus.Progress(
                                result.bytes,
                                loopState.totalRead,
                                item.downloadSize,
                            ),
                    )
                    updateNotification(notificationBuilder, item, loopState.totalRead.toInt())
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            downloadQueue.markFailed(item.id)
            toastFromMainThread("${getString(R.string.download)}: ${e.message}")
            Log.e(this@DownloadService::class.java.name, e.stackTraceToString())
            _downloadFlow.emit(item.id to DownloadStatus.Error(e.message.toString(), e))
            false
        }
    }

    private suspend fun handleChunkFailure(
        item: DownloadItem,
        notificationBuilder: Builder,
        loopState: DownloadLoopState,
    ): Boolean {
        if (loopState.retries < MAX_SEGMENT_RETRIES) {
            delay(200)
            loopState.retries++
            return true
        }
        setPauseNotification(notificationBuilder, item, false)
        downloadQueue.markFailed(item.id)
        toastFromMainThread(
            "${getString(R.string.download)}: ${getString(R.string.download_failed)}",
        )
        _downloadFlow.emit(
            item.id to DownloadStatus.Error(getString(R.string.download_failed), null),
        )
        return false
    }

    private suspend fun finalizeDownloadLoop(
        item: DownloadItem,
        notificationBuilder: Builder,
    ) {
        if (downloadQueue.isRunning(item.id) && item.isFinished) {
            setPauseNotification(notificationBuilder, item, true)
            _downloadFlow.emit(item.id to DownloadStatus.Completed)
            downloadQueue.remove(item.id)
        } else if (downloadQueue.stateOf(item.id) == DownloadQueueTracker.State.PAUSED) {
            setPauseNotification(notificationBuilder, item, false)
            _downloadFlow.emit(item.id to DownloadStatus.Paused)
        }
    }

    private suspend fun startNextEnqueueDownload() {
        while (true) {
            val id = downloadQueue.nextQueuedId() ?: return
            val dbItem = Database.downloadDao().findDownloadItemById(id)
            if (
                dbItem != null &&
                    (dbItem.downloadSize <= 0L || dbItem.path.fileSize() < dbItem.downloadSize)
            ) {
                resume(id)
                return
            } else {
                downloadQueue.remove(id)
            }
        }
    }

    private fun updateNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        totalRead: Int,
    ) {
        notificationBuilder
            .setContentText(
                totalRead.formatAsFileSize() + " / " + item.downloadSize.formatAsFileSize(),
            )
            .setProgress(
                item.downloadSize.toInt(),
                totalRead,
                false,
            )
        notificationManager.notify(
            item.getNotificationId(),
            notificationBuilder.build(),
        )
    }

    /**
     * Returns true if the current amount of downloads is still less than the maximum amount of
     * concurrent downloads.
     */
    private fun mayStartNewDownload(): Boolean {
        return downloadQueue.activeCount() < DownloadHelper.MAX_CONCURRENT_DOWNLOADS
    }

    /**
     * Initiate download [Job] using [DownloadItem] by creating file according to [FileType] for the
     * requested file.
     */
    private fun start(item: DownloadItem) {
        item.path =
            when (item.type) {
                    FileType.AUDIO -> getDownloadPath(DownloadHelper.AUDIO_DIR, item.fileName)
                    FileType.VIDEO -> getDownloadPath(DownloadHelper.VIDEO_DIR, item.fileName)
                    FileType.SUBTITLE -> getDownloadPath(DownloadHelper.SUBTITLE_DIR, item.fileName)
                }
                .apply { deleteIfExists() }
                .createFile()

        serviceScope.launch {
            item.id = Database.downloadDao().insertDownloadItem(item).toInt()

            if (mayStartNewDownload()) {
                selectFormatAndDownloadFile(item)
            } else {
                downloadQueue.enqueue(item.id)
                _downloadFlow.emit(item.id to DownloadStatus.Paused)
                stopServiceIfDone()
            }
        }
    }

    /** Resume download which may have been paused. */
    fun resume(id: Int) {
        // If file is already downloading then avoid new download job.
        if (downloadQueue.isRunning(id)) return

        if (!mayStartNewDownload()) {
            toastFromMainThread(getString(R.string.concurrent_downloads_limit_reached))
            downloadQueue.enqueue(id)
            serviceScope.launch {
                _downloadFlow.emit(id to DownloadStatus.Paused)
            }
            return
        }

        downloadQueue.enqueue(id)
        serviceScope.launch {
            val file =
                Database.downloadDao().findDownloadItemById(id)
                    ?: run {
                        downloadQueue.remove(id)
                        stopServiceIfDone()
                        return@launch
                    }
            selectFormatAndDownloadFile(file)
        }
    }

    /** Pause downloading job for given [id]. If no downloads are active, stop the service. */
    fun pause(id: Int) {
        downloadQueue.markPaused(id)

        serviceScope.launch {
            _downloadFlow.emit(id to DownloadStatus.Paused)
        }

        stopServiceIfDone()
    }

    /** Resume all downloads: Queue them all, then fill empty slots. */
    private fun resumeAll() {
        serviceScope.launch {
            val incompleteItems =
                withContext(Dispatchers.IO) {
                    Database.downloadDao()
                        .getAll()
                        .flatMap { it.downloadItems }
                        .filter { !it.isFinished }
                }

            incompleteItems.forEach {
                if (!downloadQueue.isRunning(it.id)) {
                    downloadQueue.enqueue(it.id)
                }
            }

            val current = downloadQueue.activeCount()
            val slotsToFill = DownloadHelper.MAX_CONCURRENT_DOWNLOADS - current

            if (slotsToFill > 0) {
                val candidates =
                    incompleteItems
                        .filter {
                            downloadQueue.stateOf(it.id) == DownloadQueueTracker.State.QUEUED
                        }
                        .take(slotsToFill)

                candidates.forEach { item ->
                    launch {
                        selectFormatAndDownloadFile(item)
                    }
                }
            }

            stopServiceIfDone()
        }
    }

    /** Stop downloading job for given [id]. If no downloads are active, stop the service. */
    private fun stop(id: Int) = serviceScope.launch {
        downloadQueue.remove(id)
        _downloadFlow.emit(id to DownloadStatus.Stopped)

        val item = Database.downloadDao().findDownloadItemById(id)
        if (item != null) {
            notificationManager.cancel(item.getNotificationId())
            Database.downloadDao().deleteDownloadItemById(id)
        }
        stopServiceIfDone()
    }

    /** Stop service if no downloads are active */
    private fun stopServiceIfDone() {
        if (downloadQueue.activeCount() == 0 && downloadQueue.nextQueuedId() == null) {
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
            sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
            stopSelf()
        }
    }

    private fun selectMatchingStream(streams: Streams, item: DownloadItem): PipedStream? {
        val streamList =
            when (item.type) {
                FileType.AUDIO -> streams.audioStreams
                FileType.VIDEO -> streams.videoStreams
                FileType.SUBTITLE -> null
            } ?: return null

        return streamList.find {
            it.format == item.format &&
                it.quality == item.quality &&
                (item.language.isNullOrEmpty() || it.audioTrackLocale == item.language)
        }
            ?: streamList.find {
                it.format == item.format && it.quality == item.quality
            }
            ?: streamList.find {
                it.quality == item.quality
            }
            ?: streamList.firstOrNull()
    }

    /** Check whether the file downloading or not. */
    fun isDownloading(id: Int): Boolean {
        return downloadQueue.isRunning(id)
    }

    private fun notifyForeground() {
        notificationManager = getSystemService()!!

        summaryNotificationBuilder =
            Builder(this, DOWNLOAD_CHANNEL_NAME)
                .setSmallIcon(R.drawable.ic_launcher_lockscreen)
                .setContentTitle(getString(R.string.downloading))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setGroupSummary(true)

        ServiceCompat.startForeground(
            this,
            NotificationId.DOWNLOAD_IN_PROGRESS.id,
            summaryNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun getNotificationBuilder(item: DownloadItem): Builder {
        val intent =
            Intent(this@DownloadService, MainActivity::class.java)
                .putExtra(IntentData.OPEN_DOWNLOADS, true)
        val activityIntent =
            PendingIntentCompat.getActivity(
                this@DownloadService,
                0,
                intent,
                FLAG_CANCEL_CURRENT,
                false,
            )

        return Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setContentTitle("[${item.type}] ${item.fileName}")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(activityIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
    }

    private fun setResumeNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
    ) {
        notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .clearActions()
            .addAction(getPauseAction(item.id))
            .addAction(getStopAction(item.id))

        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun setPauseNotification(
        notificationBuilder: Builder,
        item: DownloadItem,
        isCompleted: Boolean = false,
    ) {
        notificationBuilder.setProgress(0, 0, false).setOngoing(false).clearActions()

        if (isCompleted) {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_done)
                .setContentText(getString(R.string.download_completed))
        } else {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_pause)
                .setContentText(getString(R.string.download_paused))
                .addAction(getResumeAction(item.id))
                .addAction(getStopAction(item.id))
        }
        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun getResumeAction(id: Int): NotificationCompat.Action {
        val intent =
            Intent(this, NotificationReceiver::class.java)
                .setAction(ACTION_DOWNLOAD_RESUME)
                .putExtra("id", id)

        return NotificationCompat.Action.Builder(
                R.drawable.ic_play,
                getString(R.string.resume),
                PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false),
            )
            .build()
    }

    private fun getPauseAction(id: Int): NotificationCompat.Action {
        val intent =
            Intent(this, NotificationReceiver::class.java)
                .setAction(ACTION_DOWNLOAD_PAUSE)
                .putExtra("id", id)

        return NotificationCompat.Action.Builder(
                R.drawable.ic_pause,
                getString(R.string.pause),
                PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false),
            )
            .build()
    }

    private fun getStopAction(id: Int): NotificationCompat.Action {
        val intent =
            Intent(this, NotificationReceiver::class.java).apply {
                action = ACTION_DOWNLOAD_STOP
                putExtra("id", id)
            }

        // the request code must differ from the one of the pause/resume action
        val requestCode = Int.MAX_VALUE / 2 - id
        return NotificationCompat.Action.Builder(
                R.drawable.ic_stop,
                getString(R.string.stop),
                PendingIntentCompat.getBroadcast(
                    this,
                    requestCode,
                    intent,
                    FLAG_UPDATE_CURRENT,
                    false,
                ),
            )
            .build()
    }

    /** Get a [Path] from the corresponding download directory and the file name */
    private fun getDownloadPath(directory: String, fileName: String): Path {
        return DownloadHelper.getDownloadDir(this, directory) / fileName
    }

    override fun onDestroy() {
        serviceScope.cancel()
        dispatcher.close()
        downloadQueue.clear()
        cachedStreamsInfo.clear()
        IS_DOWNLOAD_RUNNING = false
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        intent.getIntArrayExtra("ids")?.forEach { resume(it) }
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    companion object {
        private const val DOWNLOAD_NOTIFICATION_GROUP = "download_notification_group"
        const val ACTION_SERVICE_STARTED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STOPPED"
        const val ACTION_RESUME_ALL =
            "com.github.libretube.services.DownloadService.ACTION_RESUME_ALL"

        private const val MAX_SEGMENT_RETRIES = 3
        var IS_DOWNLOAD_RUNNING = false
    }
}
