package com.github.libretube.services

import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.filterByTab
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.activities.NoInternetActivity
import com.github.libretube.ui.fragments.DownloadTab
import com.github.libretube.ui.fragments.DownloadsFragmentPage.Companion.sortDownloadList
import com.github.libretube.util.AccessControl
import com.github.libretube.util.AllowedSnapshot
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.normalizeChannelId
import kotlin.io.path.exists
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun selectAllowedShuffleDownloadId(
    downloads: List<Download>,
    snapshot: AllowedSnapshot,
    random: Random = Random.Default,
): String? =
    downloads.filter { snapshot.isChannelAllowed(it.uploaderUrl) }.randomOrNull(random)?.videoId

/** A service to play downloaded audio in the background */
@OptIn(UnstableApi::class)
open class OfflinePlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = true
    private var noInternetService: Boolean = false

    private var downloadWithItems: DownloadWithItems? = null
    private lateinit var playerData: PlayerData

    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playNextVideo()
                }

                // add video to watch history when playback starts
                if (playbackState == Player.STATE_READY && PlayerHelper.watchHistoryEnabled) {
                    val watchHistoryEntry =
                        downloadWithItems
                            ?.download
                            ?.toStreamItem()
                            ?.toWatchHistoryEntry(exoPlayer?.currentPosition) ?: return

                    serviceScope.launch(Dispatchers.IO) {
                        runCatching {
                            UserDataRepositoryHelper.userDataRepository.addToWatchHistory(
                                watchHistoryEntry,
                            )
                        }
                    }
                }
            }
        }

    override suspend fun onServiceCreated(args: Bundle) {
        if (args.isEmpty) return

        playerData = args.parcelable(IntentData.playerData)!!
        noInternetService = args.getBoolean(IntentData.noInternet, false)
        isAudioOnlyPlayer = args.getBoolean(IntentData.audioOnly, false)

        PlayingQueue.clear()

        this.videoId =
            if (playerData.shuffle) {
                withContext(Dispatchers.IO) {
                    val snapshot = AccessControl.instance.snapshot()
                    val candidates =
                        if (playerData.downloadTab == DownloadTab.PLAYLIST) {
                            Database.downloadDao()
                                .getDownloadPlaylistById(playerData.playlistId!!)
                                .downloadVideos
                        } else {
                            Database.downloadDao()
                                .getAll()
                                .filterByTab(playerData.downloadTab!!)
                                .map { it.download }
                        }
                    selectAllowedShuffleDownloadId(candidates, snapshot)
                }
            } else {
                playerData.videoId
            } ?: return

        playbackSession.bindTrack(
            sessionToken = sessionToken,
            videoId = this.videoId,
            channelId = normalizeChannelId(playerData.channelId),
        )

        withContext(Dispatchers.Main.immediate) {
            exoPlayer?.removeListener(playerListener)
            exoPlayer?.addListener(playerListener)
            trackSelector?.updateParameters {
                setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
            }
        }

        fillQueue()
    }

    override fun getIntentActivity(): Class<*> {
        return if (noInternetService) NoInternetActivity::class.java else MainActivity::class.java
    }

    /** Attempt to start an audio player with the given download items */
    override suspend fun startPlayback() {
        super.startPlayback()
        playbackSession.bindTrack(
            sessionToken = sessionToken,
            videoId = videoId,
            channelId =
                normalizeChannelId(PlayingQueue.getCurrent()?.uploaderUrl, playerData.channelId),
        )

        val downloadWithItems =
            withContext(Dispatchers.IO) {
                val found = Database.downloadDao().findById(videoId) ?: return@withContext null
                if (
                    !AccessControl.instance.snapshot().isChannelAllowed(found.download.uploaderUrl)
                ) {
                    toastFromMainDispatcher(R.string.channel_not_allowed)
                    return@withContext null
                }
                found
            }
                ?: run {
                    finishTransition()
                    if (!hasConnectedControllers()) requestStopService()
                    return
                }
        this.downloadWithItems = downloadWithItems
        playbackSession.bindTrack(
            sessionToken = sessionToken,
            videoId = videoId,
            channelId = normalizeChannelId(downloadWithItems.download.uploaderUrl),
        )

        PlayingQueue.updateCurrent(downloadWithItems.download.toStreamItem())

        withContext(Dispatchers.Main) {
            syncExoPlayerSpeed()
            setSponsorBlockSegments(
                downloadWithItems.downloadSponsorBlockSegments.map { it.toSegment() },
            )

            setMediaItem(downloadWithItems)

            // automatically start playback when using the audio player
            exoPlayer?.playWhenReady = PlayerHelper.playAutomatically || isAudioOnlyPlayer
            exoPlayer?.prepare()

            if (watchPositionsEnabled) {
                DatabaseHelper.getWatchPosition(videoId)?.let {
                    if (
                        !DatabaseHelper.isVideoWatched(
                            it,
                            downloadWithItems.download.duration,
                        )
                    )
                        exoPlayer?.seekTo(it)
                }
            }
        }
    }

    private fun setMediaItem(downloadWithItems: DownloadWithItems) {
        val downloadFiles = downloadWithItems.downloadItems.filter { it.path.exists() }

        val videoUri = downloadFiles.firstOrNull { it.type == FileType.VIDEO }?.path?.toAndroidUri()
        val audioUri = downloadFiles.firstOrNull { it.type == FileType.AUDIO }?.path?.toAndroidUri()
        val subtitleInfo = downloadFiles.firstOrNull { it.type == FileType.SUBTITLE }

        val videoSource = videoUri?.let { videoUri ->
            val videoItem =
                MediaItem.Builder().setUri(videoUri).setMetadata(downloadWithItems).build()

            ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(videoItem)
        }

        val audioSource = audioUri?.let { audioUri ->
            val audioItem =
                MediaItem.Builder().setUri(audioUri).setMetadata(downloadWithItems).build()

            ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(audioItem)
        }

        val subtitleSource = subtitleInfo?.let { subtitleInfo ->
            val subtitle =
                SubtitleConfiguration.Builder(subtitleInfo.path.toAndroidUri())
                    .setMimeType(MimeTypes.APPLICATION_TTML)
                    .setLanguage(subtitleInfo.language ?: "en")
                    .build()

            SingleSampleMediaSource.Factory(FileDataSource.Factory())
                .createMediaSource(subtitle, C.TIME_UNSET)
        }

        var mediaSource: MediaSource? = null
        listOfNotNull(videoSource, audioSource, subtitleSource).forEach { source ->
            mediaSource =
                if (mediaSource == null) source else MergingMediaSource(mediaSource!!, source)
        }

        if (mediaSource == null || isAudioOnlyPlayer && audioSource == null) {
            stopSelf()
            return
        }

        exoPlayer?.setMediaSource(mediaSource!!)

        trackSelector?.updateParameters {
            setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
            setPreferredTextLanguage(subtitleInfo?.language ?: "en")
        }
    }

    private suspend fun fillQueue() {
        val snapshot = withContext(Dispatchers.IO) { AccessControl.instance.snapshot() }
        if (playerData.downloadTab == DownloadTab.PLAYLIST) {
            var videos =
                withContext(Dispatchers.IO) {
                        Database.downloadDao().getDownloadPlaylistById(playerData.playlistId!!)
                    }
                    .downloadVideos
                    .filter { snapshot.isChannelAllowed(it.uploaderUrl) }

            if (playerData.shuffle && videos.any { it.videoId == videoId })
                videos =
                    listOf(videos.first { it.videoId == videoId }) +
                        videos.filter { it.videoId != videoId }.shuffled()
            else if (playerData.downloadSortingOrder != null)
                videos = sortDownloadList(videos, playerData.downloadSortingOrder!!)
            PlayingQueue.setStreams(videos.map { it.toStreamItem() })
        } else {
            var downloads =
                withContext(Dispatchers.IO) {
                        Database.downloadDao().getAll()
                    }
                    .filterByTab(playerData.downloadTab!!)
                    .map { it.download }
                    .filter { snapshot.isChannelAllowed(it.uploaderUrl) }

            if (playerData.shuffle) downloads = downloads.shuffled()
            else if (playerData.downloadSortingOrder != null)
                downloads = sortDownloadList(downloads, playerData.downloadSortingOrder!!)

            PlayingQueue.add(*downloads.map { it.toStreamItem() }.toTypedArray())
        }
    }

    /** Stop the service when app is removed from the task manager. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }
}
