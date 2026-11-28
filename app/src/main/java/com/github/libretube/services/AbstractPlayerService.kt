package com.github.libretube.services

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.HandlerDelayedTaskScheduler
import com.github.libretube.helpers.PlaybackTimelineScheduler
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.player.PlaybackSession
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.util.DefaultTrackSelectorWithAudioQualitySupport
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.PlayingQueueMode
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@UnstableApi
abstract class AbstractPlayerService : MediaLibraryService(), MediaLibrarySession.Callback {
    private var mediaLibrarySession: MediaLibrarySession? = null
    var exoPlayer: ExoPlayer? = null

    private var notificationProvider: NowPlayingNotification? = null
    var trackSelector: DefaultTrackSelector? = null

    lateinit var videoId: String

    protected fun currentVideoIdOrNull(): String? = if (::videoId.isInitialized) videoId else null

    @Volatile
    var isTransitioning = false
        private set

    protected val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transitionJob: Job? = null
    @Volatile private var sessionGeneration = 0L
    @Volatile
    protected var sessionToken: Long = 0L
        private set

    protected val playbackSession: PlaybackSession
        get() = PlaybackSession.instance

    @Volatile
    protected var isServiceDestroyed = false
        private set

    private var pendingStopRunnable: Runnable? = null

    protected fun finishTransition() {
        isTransitioning = false
    }

    protected fun hasConnectedControllers(): Boolean {
        return mediaLibrarySession?.connectedControllers?.isNotEmpty() == true
    }

    val handler = Handler(Looper.getMainLooper())
    private val timelineScheduler = PlaybackTimelineScheduler(HandlerDelayedTaskScheduler(handler))

    // SponsorBlock Segment data
    private var sponsorBlockAutoSkip = true
    protected val sponsorBlockConfig = PlayerHelper.getSponsorBlockCategories()
    private var sponsorBlockSegments = listOf<Segment>()

    /**
     * Whether the service should automatically play the next video after the current video
     * finished.
     *
     * If set to `false`, the player UI views have to handle autoplay themselves.
     */
    protected var shouldHandleAutoplay = true

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                // Start or pause watch position timer and segment boundary scheduler
                if (!isPlaying) {
                    timelineScheduler.cancel(PlaybackTimelineScheduler.SEGMENT_TOKEN)
                    saveWatchPosition()
                } else {
                    checkForSegments()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                checkForSegments()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                super.onPlaybackParametersChanged(playbackParameters)
                checkForSegments()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (handlePlayerError(error)) return
                // show a toast on errors
                toastFromMainThread(error.localizedMessage.orEmpty())
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                when (playbackState) {
                    Player.STATE_ENDED -> {
                        saveWatchPosition()
                    }

                    Player.STATE_READY -> {
                        isTransitioning = false
                    }
                }
            }
        }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            START_SERVICE_ACTION -> {
                PlayingQueue.queueMode =
                    if (isOfflinePlayer) PlayingQueueMode.OFFLINE else PlayingQueueMode.ONLINE

                val activeGeneration = ++sessionGeneration
                sessionToken = playbackSession.nextSessionToken()
                pendingStopRunnable?.let(handler::removeCallbacks)
                pendingStopRunnable = null

                serviceScope.launch {
                    onServiceCreated(args)
                    if (activeGeneration != sessionGeneration || isServiceDestroyed) return@launch
                    updateNotification()

                    if (::videoId.isInitialized) startPlayback()
                }
            }

            STOP_SERVICE_ACTION -> {
                requestStopService(delayMs = STOP_SERVICE_DELAY_MS)
            }

            RUN_PLAYER_COMMAND_ACTION -> {
                runPlayerCommand(args)
            }
        }

        return super.onCustomCommand(session, controller, customCommand, args)
    }

    open fun runPlayerCommand(args: Bundle) {
        when {
            args.containsKey(PlayerCommand.SKIP_SILENCE.name) ->
                exoPlayer?.skipSilenceEnabled = args.getBoolean(PlayerCommand.SKIP_SILENCE.name)

            args.containsKey(PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name) ->
                trackSelector?.updateParameters {
                    setTrackTypeDisabled(
                        C.TRACK_TYPE_VIDEO,
                        args.getBoolean(PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name),
                    )
                }

            args.containsKey(PlayerCommand.SET_AUDIO_ROLE_FLAGS.name) -> {
                trackSelector?.updateParameters {
                    setPreferredAudioRoleFlags(args.getInt(PlayerCommand.SET_AUDIO_ROLE_FLAGS.name))
                }
            }

            args.containsKey(PlayerCommand.SET_AUDIO_LANGUAGE.name) -> {
                trackSelector?.updateParameters {
                    setPreferredAudioLanguage(args.getString(PlayerCommand.SET_AUDIO_LANGUAGE.name))
                }
            }

            args.containsKey(PlayerCommand.SET_RESOLUTION.name) -> {
                trackSelector?.updateParameters {
                    val resolution = args.getInt(PlayerCommand.SET_RESOLUTION.name)
                    setMinVideoSize(Int.MIN_VALUE, resolution)
                    setMaxVideoSize(Int.MAX_VALUE, resolution)
                }
            }

            args.containsKey(PlayerCommand.SET_CAPTION_TRACK.name) -> {
                val exoPlayer = exoPlayer ?: return

                val captionId = args.getString(PlayerCommand.SET_CAPTION_TRACK.name)
                val caption =
                    PlayerHelper.getCaptionTracks(exoPlayer).firstOrNull { it.id == captionId }

                trackSelector?.updateParameters {
                    val enableCaptions = caption != null

                    if (enableCaptions) {
                        setPreferredTextRoleFlags(caption.roleFlags)
                        setPreferredTextLanguage(caption.language)
                    }

                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enableCaptions)
                }
            }

            args.containsKey(PlayerCommand.PLAY_VIDEO_BY_ID.name) -> {
                navigateVideo(args.getString(PlayerCommand.PLAY_VIDEO_BY_ID.name) ?: return)
            }

            args.containsKey(PlayerCommand.PLAY_NEXT_VIDEO.name) -> {
                playNextVideo()
            }

            args.containsKey(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name) -> {
                isAudioOnlyPlayer = args.getBoolean(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name)
                trackSelector?.updateParameters {
                    setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
                }
                updateNotification()
            }

            args.containsKey(PlayerCommand.SET_SB_AUTO_SKIP_ENABLED.name) -> {
                sponsorBlockAutoSkip = args.getBoolean(PlayerCommand.SET_SB_AUTO_SKIP_ENABLED.name)
                checkForSegments()
            }

            args.containsKey(PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name) -> {
                shouldHandleAutoplay =
                    !args.getBoolean(PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name)
            }
        }
    }

    /**
     * Plays the next video.
     *
     * If a video ID is explicitly given, it is played next, otherwise a queued video (which
     * includes playlists) is chosen.
     *
     * If no video is queued and autoplay is enabled, a related video is used.
     */
    fun playNextVideo(videoId: String? = null, relatedStreams: List<StreamItem>? = null) {
        if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
            exoPlayer?.seekTo(0)
            return
        }

        if (!shouldHandleAutoplay) {
            return
        }

        val nextVideo =
            videoId
                ?: PlayingQueue.getNext()
                ?: relatedStreams
                    ?.firstOrNull { !it.isLive }
                    ?.url
                    ?.toID()
                    ?.takeIf { PlayerHelper.autoPlayEnabled && PlayerHelper.relatedStreamsEnabled }
                ?: return

        navigateVideo(nextVideo)
    }

    /**
     * Clear the currently playing video and start playing the new provided [videoId]
     *
     * If overriding, clearing old data must be done BEFORE calling [super.navigateVideo()]!
     */
    @CallSuper
    open fun navigateVideo(videoId: String) {
        timelineScheduler.cancel(PlaybackTimelineScheduler.SEGMENT_TOKEN)
        sponsorBlockSegments = emptyList()
        exoPlayer?.clearMediaItems()

        this.videoId = videoId

        transitionJob?.cancel()
        transitionJob = serviceScope.launch(Dispatchers.IO) { startPlayback() }
    }

    protected fun setSponsorBlockSegments(segments: List<Segment>) {
        sponsorBlockSegments = segments
        if (!PlayerHelper.sponsorBlockEnabled) {
            timelineScheduler.cancel(PlaybackTimelineScheduler.SEGMENT_TOKEN)
            return
        }

        updatePlaylistMetadata {
            // JSON-encode as work-around for https://github.com/androidx/media/issues/564
            val segments = JsonHelper.json.encodeToString(sponsorBlockSegments)
            setExtras(bundleOf(IntentData.segments to segments))
        }

        checkForSegments()
    }

    /**
     * Check for SponsorBlock segments and schedule the next check at the exact boundary of the next
     * segment without running a perpetual 100ms timer.
     */
    private fun checkForSegments() {
        val player =
            exoPlayer
                ?: run {
                    timelineScheduler.cancel(PlaybackTimelineScheduler.SEGMENT_TOKEN)
                    return
                }

        timelineScheduler.evaluateAndScheduleSegments(
            currentPositionMs = player.currentPosition,
            playbackSpeed = player.playbackParameters.speed,
            isPlaying = player.isPlaying,
            sponsorBlockEnabled = PlayerHelper.sponsorBlockEnabled,
            autoSkipEnabled = sponsorBlockAutoSkip,
            segments = sponsorBlockSegments,
            sponsorBlockConfig = sponsorBlockConfig,
            includeSegmentEnd = false,
            onAutoSkip = { _, targetMs ->
                player.seekTo(targetMs)
                if (PlayerHelper.sponsorBlockNotifications)
                    toastFromMainThread(R.string.segment_skipped)
            },
            onReschedule = this::checkForSegments,
        )
    }

    protected fun updatePlaylistMetadata(updateAction: MediaMetadata.Builder.() -> Unit) {
        handler.post {
            exoPlayer?.playlistMetadata =
                MediaMetadata.Builder()
                    .apply(updateAction)
                    // send a unique timestamp to notify that the metadata changed, even if playing
                    // the same video twice
                    .setTrackNumber(System.currentTimeMillis().mod(Int.MAX_VALUE))
                    .build()
        }
    }

    private fun handlePlayerAction(event: PlayerEvent) {
        if (PlayerHelper.handlePlayerAction(exoPlayer ?: return, event)) return

        when (event) {
            PlayerEvent.Next -> {
                navigateVideo(PlayingQueue.getNext() ?: return)
            }

            PlayerEvent.Prev -> {
                navigateVideo(PlayingQueue.getPrev() ?: return)
            }

            PlayerEvent.Stop -> {
                requestStopService()
            }

            else -> Unit
        }
    }

    /** Trigger a notification update with an updated PendingIntent. */
    private fun updateNotification() {
        val notificationIntent =
            Intent(this, getIntentActivity()).apply {
                putExtra(IntentData.maximizePlayer, true)
                putExtra(IntentData.offlinePlayer, isOfflinePlayer)
                putExtra(IntentData.audioOnly, isAudioOnlyPlayer)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        notificationProvider?.notificationIntent = notificationIntent
        triggerNotificationUpdate()
    }

    abstract val isOfflinePlayer: Boolean
    var isAudioOnlyPlayer: Boolean = false

    val watchPositionsEnabled
        get() =
            (PlayerHelper.watchPositionsAudio && isAudioOnlyPlayer) ||
                (PlayerHelper.watchPositionsVideo && !isAudioOnlyPlayer)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onCreate() {
        sessionToken = playbackSession.nextSessionToken()
        super.onCreate()

        notificationProvider = NowPlayingNotification(this)
        setMediaNotificationProvider(notificationProvider!!)

        createPlayerAndMediaSession()
        observePlaybackSession()
    }

    private fun observePlaybackSession() {
        serviceScope.launch {
            playbackSession.state
                .filter { it.sessionToken == sessionToken }
                .map { it.effectiveSpeed }
                .distinctUntilChanged()
                .collect { effectiveSpeed ->
                    syncExoPlayerSpeed(effectiveSpeed)
                }
        }
    }

    protected fun syncExoPlayerSpeed(
        effectiveSpeed: Float = playbackSession.effectiveSpeedForSession(sessionToken),
    ) {
        if (playbackSession.state.value.sessionToken != sessionToken) return
        val player = exoPlayer ?: return
        val currentParams = player.playbackParameters
        if (currentParams.speed != effectiveSpeed) {
            player.playbackParameters = PlaybackParameters(effectiveSpeed, currentParams.pitch)
        }
    }

    open fun getIntentActivity(): Class<*> = MainActivity::class.java

    protected open fun handlePlayerError(error: PlaybackException): Boolean = false

    abstract suspend fun onServiceCreated(args: Bundle)

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()

        val mediaNotificationSessionCommands =
            connectionResult.availableSessionCommands
                .buildUpon()
                .also { builder ->
                    builder.addSessionCommands(
                        listOf(
                            startServiceCommand,
                            runPlayerActionCommand,
                            stopServiceCommand,
                        ),
                    )
                }
                .build()

        val playerCommands =
            connectionResult.availablePlayerCommands
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(mediaNotificationSessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun createPlayerAndMediaSession() {
        val trackSelector = DefaultTrackSelectorWithAudioQualitySupport(this)
        this.trackSelector = trackSelector

        val player =
            PlayerHelper.createPlayer(
                context = this,
                trackSelector = trackSelector,
                playbackSession = playbackSession,
                sessionTokenProvider = { sessionToken },
            )
        // prevent android from putting LibreTube to sleep when locked
        player.setWakeMode(if (isOfflinePlayer) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
        player.addListener(playerListener)
        this.exoPlayer = player

        val forwardingPlayer = MediaSessionForwarder(player)

        // it's possible that this is called twice at the same time:
        // if the user rotates the screen while the initial media library session is still in
        // creation
        // this results in a race condition which service is the first one to create a
        // `MediaLibrarySession`
        // the other one will just crash
        try {
            mediaLibrarySession =
                MediaLibrarySession.Builder(this, forwardingPlayer, this)
                    .setId(this.javaClass.name)
                    .build()
        } catch (e: Exception) {
            Log.e(TAG(), "failed to start media library session because it already exists")
            e.printStackTrace()
            onDestroy()
        }
    }

    /**
     * Load the stream source and start the playback.
     *
     * This function should base its actions on the videoId variable.
     */
    @CallSuper
    open suspend fun startPlayback() {
        isTransitioning = true

        updatePlaylistMetadata {
            setExtras(bundleOf(IntentData.videoId to videoId))
        }
    }

    private fun saveWatchPosition() {
        if (isTransitioning || !watchPositionsEnabled || !::videoId.isInitialized) return

        exoPlayer?.let { PlayerHelper.saveWatchPosition(it, videoId) }
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        val event: KeyEvent = intent.parcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                handlePlayerAction(PlayerEvent.Next)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                handlePlayerAction(PlayerEvent.Prev)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                handlePlayerAction(PlayerEvent.Rewind)
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                handlePlayerAction(PlayerEvent.Forward)
            }

            KeyEvent.KEYCODE_MEDIA_STOP -> {
                handlePlayerAction(PlayerEvent.Stop)
            }
        }

        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    protected fun requestStopService(delayMs: Long = 0L) {
        val requestGeneration = sessionGeneration
        pendingStopRunnable?.let(handler::removeCallbacks)
        pendingStopRunnable = null

        val runnable = Runnable {
            pendingStopRunnable = null
            stopPlaybackAndService(requestGeneration)
        }
        if (delayMs > 0L) {
            pendingStopRunnable = runnable
            handler.postDelayed(runnable, delayMs)
        } else {
            handler.post(runnable)
        }
    }

    private fun stopPlaybackAndService(expectedGeneration: Long) {
        if (expectedGeneration != sessionGeneration || isServiceDestroyed) return
        playbackSession.clearTrack(sessionToken)
        timelineScheduler.cancelAll()
        saveWatchPosition()
        runCatching { exoPlayer?.stop() }
        PlayingQueue.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        sessionGeneration++
        playbackSession.clearTrack(sessionToken)
        pendingStopRunnable?.let(handler::removeCallbacks)
        pendingStopRunnable = null
        serviceScope.cancel()
        timelineScheduler.cancelAll()
        saveWatchPosition()

        notificationProvider = null
        handler.removeCallbacksAndMessages(null)

        runCatching {
            exoPlayer?.stop()
            exoPlayer?.release()
        }

        runCatching {
            mediaLibrarySession?.release()
            mediaLibrarySession = null
        }

        PlayingQueue.clear()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()

        super.onDestroy()
    }

    /** Stop the service when app is removed from the task manager. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopPlaybackAndService(sessionGeneration)
    }

    /**
     * [Player] wrapper that handles seeking actions (next/previous) itself instead of using the
     * default [Player] implementation
     */
    inner class MediaSessionForwarder(player: Player) : ForwardingPlayer(player) {
        override fun hasNextMediaItem(): Boolean {
            return PlayingQueue.hasNext()
        }

        override fun hasPreviousMediaItem(): Boolean {
            return PlayingQueue.hasPrev()
        }

        override fun seekToPrevious() {
            handlePlayerAction(PlayerEvent.Prev)
        }

        override fun seekToNext() {
            handlePlayerAction(PlayerEvent.Next)
        }

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands()
                .buildUpon()
                .addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS) return true

            return super.isCommandAvailable(command)
        }
    }

    companion object {
        private const val START_SERVICE_ACTION = "start_service_action"
        private const val STOP_SERVICE_ACTION = "stop_service_action"
        private const val RUN_PLAYER_COMMAND_ACTION = "run_player_command_action"
        private const val STOP_SERVICE_DELAY_MS = 50L

        val startServiceCommand = SessionCommand(START_SERVICE_ACTION, Bundle.EMPTY)
        val stopServiceCommand = SessionCommand(STOP_SERVICE_ACTION, Bundle.EMPTY)
        val runPlayerActionCommand = SessionCommand(RUN_PLAYER_COMMAND_ACTION, Bundle.EMPTY)
    }
}
