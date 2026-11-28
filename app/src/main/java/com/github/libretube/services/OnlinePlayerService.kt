package com.github.libretube.services

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.github.libretube.R
import com.github.libretube.api.ChannelNotAllowedException
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.getSubtitleRoleFlags
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.player.PlaybackSource
import com.github.libretube.player.PlaybackStreamResolver
import com.github.libretube.player.ResolvedPlaybackStream
import com.github.libretube.player.SabrMediaSource
import com.github.libretube.player.StreamRecoveryOutcome
import com.github.libretube.player.isPlayableDashAudio
import com.github.libretube.player.isPlayableDashVideo
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YoutubeHlsPlaylistParser
import com.github.libretube.util.normalizeChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads the selected videos audio in background mode with a notification area. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class OnlinePlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = false

    // PlaylistId/ChannelId for autoplay
    private var playlistId: String? = null
    private var channelId: String? = null
    private var startTimestampSeconds: Long? = null

    private var currentStream: ResolvedPlaybackStream? = null
    private val streams: Streams?
        get() = currentStream?.streams

    private val scope = CoroutineScope(Dispatchers.Main)

    /*
    Current job that's loading a new video (the value is null if no video is loading at the moment).
     */
    private var fetchVideoInfoJob: Job? = null
    private var refreshStreamJob: Job? = null

    private val streamResolver = PlaybackStreamResolver()

    private var hasPlaybackStarted = false

    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        if (!isTransitioning)
                            playNextVideo(relatedStreams = streams?.relatedStreams)
                    }

                    Player.STATE_IDLE -> {
                        if (
                            streamResolver.shouldDestroyOnIdle(
                                videoId = currentVideoIdOrNull(),
                                playerError = exoPlayer?.playerError,
                                isTransitioning = isTransitioning,
                                hasPlaybackStarted = hasPlaybackStarted,
                                hasConnectedControllers = hasConnectedControllers(),
                            )
                        ) {
                            requestStopService()
                        }
                    }

                    Player.STATE_BUFFERING -> {}
                    Player.STATE_READY -> {
                        hasPlaybackStarted = true
                        // save video to watch history when the video starts playing or is being
                        // resumed
                        // waiting for the player to be ready since the video can't be claimed to be
                        // watched
                        // while it did not yet start actually, but did buffer only so far
                        if (PlayerHelper.watchHistoryEnabled) {
                            val watchHistoryEntry =
                                streams
                                    ?.toStreamItem(videoId)
                                    ?.toWatchHistoryEntry(exoPlayer?.currentPosition) ?: return

                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    UserDataRepositoryHelper.userDataRepository.addToWatchHistory(
                                        watchHistoryEntry,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    override suspend fun onServiceCreated(args: Bundle) {
        val playerData = args.parcelable<PlayerData>(IntentData.playerData)
        if (playerData == null) {
            requestStopService()
            return
        }
        isAudioOnlyPlayer = args.getBoolean(IntentData.audioOnly)
        hasPlaybackStarted = false

        // get the intent arguments
        videoId = playerData.videoId!!
        streamResolver.onVideoStarted(videoId)
        playlistId = playerData.playlistId
        channelId = playerData.channelId
        playbackSession.bindTrack(
            sessionToken = sessionToken,
            videoId = videoId,
            channelId = normalizeChannelId(channelId, PlayingQueue.getCurrent()?.uploaderUrl),
        )
        startTimestampSeconds = playerData.timestamp

        if (!playerData.keepQueue) PlayingQueue.clear()

        withContext(Dispatchers.Main.immediate) {
            exoPlayer?.removeListener(playerListener)
            exoPlayer?.addListener(playerListener)
            trackSelector?.updateParameters {
                setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
            }
        }
    }

    override suspend fun startPlayback() {
        super.startPlayback()
        hasPlaybackStarted = false

        playbackSession.bindTrack(
            sessionToken = sessionToken,
            videoId = videoId,
            channelId = normalizeChannelId(PlayingQueue.getCurrent()?.uploaderUrl, channelId),
        )
        streamResolver.onVideoStarted(videoId)
        refreshStreamJob?.cancel()
        refreshStreamJob = null

        val timestampMs = startTimestampSeconds?.times(1000) ?: 0L
        startTimestampSeconds = null

        // stop any previous task for loading video info
        fetchVideoInfoJob?.cancelAndJoin()

        // start loading the video info while keeping a reference to the job
        // so that it can be canceled once a different video is loaded
        fetchVideoInfoJob = scope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    try {
                        streamResolver.resolve(videoId)
                    } catch (_: ChannelNotAllowedException) {
                        toastFromMainDispatcher(R.string.channel_not_allowed)
                        return@withContext null
                    } catch (e: Exception) {
                        Log.e(TAG(), e.stackTraceToString())
                        toastFromMainDispatcher(e.localizedMessage.orEmpty())
                        return@withContext null
                    }
                }
                    ?: run {
                        finishTransition()
                        if (PlayingQueue.isEmpty() && !hasConnectedControllers())
                            requestStopService()
                        return@launch
                    }
            currentStream = loaded

            val streamItem = streams?.toStreamItem(videoId)
            playbackSession.bindTrack(
                sessionToken = sessionToken,
                videoId = videoId,
                channelId = normalizeChannelId(streamItem?.uploaderUrl, channelId),
                category = streams?.category,
            )

            streamItem?.let {
                // save the current stream to the queue
                PlayingQueue.updateCurrent(it)

                if (!PlayingQueue.hasNext()) {
                    PlayingQueue.updateQueue(it, playlistId, channelId)
                }

                // update feed item with newer information, e.g. more up-to-date views
                SubscriptionHelper.submitFeedItemChange(it.toFeedItem())
            }

            launch {
                val segments = getSponsorBlockSegments()
                withContext(Dispatchers.Main) { setSponsorBlockSegments(segments) }
            }

            try {
                withContext(Dispatchers.Main) {
                    if (isServiceDestroyed || exoPlayer == null) return@withContext
                    syncExoPlayerSpeed()
                    if (setStreamSource()) {
                        configurePlayer(timestampMs)
                    } else {
                        finishTransition()
                        if (PlayingQueue.isEmpty() && !hasConnectedControllers())
                            requestStopService()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finishTransition()
                Log.e(TAG(), e.stackTraceToString())
                toastFromMainDispatcher(e.localizedMessage.orEmpty())
                if (PlayingQueue.isEmpty() && !hasConnectedControllers()) requestStopService()
            }
        }

        fetchVideoInfoJob?.join()
        fetchVideoInfoJob = null
    }

    private fun configurePlayer(seekToPositionMs: Long) {
        // seek to the previous position if available
        if (seekToPositionMs != 0L) {
            exoPlayer?.seekTo(seekToPositionMs)
        } else if (watchPositionsEnabled) {
            scope.launch(Dispatchers.IO) {
                DatabaseHelper.getWatchPosition(videoId)?.let {
                    if (!DatabaseHelper.isVideoWatched(it, streams?.duration)) {
                        withContext(Dispatchers.Main) { exoPlayer?.seekTo(it) }
                    }
                }
            }
        }

        exoPlayer?.apply {
            // automatically start playback when using the audio player
            playWhenReady = PlayerHelper.playAutomatically || isAudioOnlyPlayer
            prepare()
        }
    }

    private suspend fun getSponsorBlockSegments(): List<Segment> {
        return runCatching {
            MediaServiceRepository.instance
                .getSegments(
                    videoId,
                    sponsorBlockConfig.keys.toList(),
                    listOf("skip", "mute", "full", "poi", "chapter"),
                )
                .segments
        }
            .getOrElse { emptyList() }
    }

    override fun navigateVideo(videoId: String) {
        currentStream = null
        refreshStreamJob?.cancel()
        refreshStreamJob = null
        playbackSession.bindTrack(
            sessionToken = sessionToken,
            videoId = videoId,
            channelId = normalizeChannelId(PlayingQueue.getCurrent()?.uploaderUrl),
        )

        super.navigateVideo(videoId)
    }

    override fun handlePlayerError(error: PlaybackException): Boolean {
        val activeVideoId = currentVideoIdOrNull() ?: return false
        if (!streamResolver.beginRecoveryIfEligible(activeVideoId, error)) {
            return false
        }

        val resumePositionMs = exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
        val shouldPlayWhenReady = exoPlayer?.playWhenReady ?: true

        refreshStreamJob?.cancel()
        refreshStreamJob = scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    streamResolver.recover(activeVideoId, resumePositionMs)
                }
            if (currentVideoIdOrNull() != activeVideoId) return@launch
            when (outcome) {
                is StreamRecoveryOutcome.Recovered -> {
                    currentStream = outcome.resolved
                    if (setStreamSource()) {
                        exoPlayer?.apply {
                            if (outcome.resumePositionMs > 0L) seekTo(outcome.resumePositionMs)
                            playWhenReady = shouldPlayWhenReady
                            prepare()
                        }
                    } else {
                        finishTransition()
                    }
                }
                is StreamRecoveryOutcome.Failed -> {
                    finishTransition()
                    Log.e(TAG(), outcome.error.stackTraceToString())
                    toastFromMainDispatcher(outcome.error.localizedMessage.orEmpty())
                    val pending =
                        streamResolver.hasPendingRecovery(
                            currentVideoIdOrNull(),
                            exoPlayer?.playerError,
                        )
                    if (!pending && !hasConnectedControllers()) {
                        requestStopService()
                    }
                }
                StreamRecoveryOutcome.Stale -> Unit
            }
        }
        return true
    }

    override fun onDestroy() {
        fetchVideoInfoJob?.cancel()
        fetchVideoInfoJob = null
        refreshStreamJob?.cancel()
        refreshStreamJob = null
        scope.cancel()
        super.onDestroy()
    }

    /** Sets the [MediaItem] with the resolved [currentStream] into the [exoPlayer] */
    private fun setStreamSource(): Boolean {
        val resolved = currentStream ?: return false
        val streams = resolved.streams
        val transportContext = resolved.transportContext
        val cachingDataSourceFactory by lazy {
            PlayerHelper.getCachingDataSourceFactory(this, transportContext)
        }

        when (val source = resolved.source) {
            PlaybackSource.GeneratedDash,
            is PlaybackSource.RemoteDash -> {
                val dashUri =
                    if (source is PlaybackSource.RemoteDash) {
                        source.url.toUri()
                    } else {
                        val playable =
                            streams.copy(
                                videoStreams =
                                    streams.videoStreams.filter { it.isPlayableDashVideo() },
                                audioStreams =
                                    streams.audioStreams.filter { it.isPlayableDashAudio() },
                            )
                        PlayerHelper.createDashSource(playable, this)
                    }
                val mediaItem = createMediaItem(dashUri, MimeTypes.APPLICATION_MPD, streams)
                val dashMediaSource =
                    DashMediaSource.Factory(cachingDataSourceFactory).createMediaSource(mediaItem)
                exoPlayer?.setMediaSource(dashMediaSource)
                return true
            }
            // SABR fallback. Livestreams never select this; the player has no SABR live support.
            PlaybackSource.Sabr -> {
                val sabrUrl = streams.serverAbrStreamingUrl ?: return false
                val sabrMediaSourceFactory =
                    SabrMediaSource.Factory(
                        SabrManifest(videoId, streams),
                        transportContext,
                    )
                val mediaItem =
                    createMediaItem(
                        sabrUrl.toUri(),
                        "application/vnd.yt-ump",
                        streams,
                    )
                val mediaSource = sabrMediaSourceFactory.createMediaSource(mediaItem)
                val mediaSources =
                    listOf<MediaSource>(mediaSource) +
                        streams.subtitles.mapNotNull { subtitle ->
                            val subtitleUrl =
                                subtitle.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val format =
                                Format.Builder()
                                    .setSampleMimeType(subtitle.mimeType)
                                    .setLanguage(subtitle.code)
                                    .setRoleFlags(getSubtitleRoleFlags(subtitle))
                                    .build()
                            val subtitleParserFactory = DefaultSubtitleParserFactory()
                            val extractorsFactory = ExtractorsFactory {
                                arrayOf(
                                    SubtitleExtractor(
                                        subtitleParserFactory.create(format),
                                        format,
                                    ),
                                )
                            }
                            val progressiveMediaSourceFactory =
                                ProgressiveMediaSource.Factory(
                                        cachingDataSourceFactory,
                                        extractorsFactory,
                                    )
                                    .setLoadOnlySelectedTracks(true)
                            try {
                                // `enableLazyLoadingWithSingleTrack` is private
                                val method =
                                    ProgressiveMediaSource.Factory::class
                                        .java
                                        .getDeclaredMethod(
                                            "enableLazyLoadingWithSingleTrack",
                                            Int::class.java,
                                            Format::class.java,
                                        )
                                method.isAccessible = true
                                method.invoke(
                                    progressiveMediaSourceFactory,
                                    SubtitleExtractor.TRACK_ID,
                                    format
                                        .buildUpon()
                                        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                                        .setCodecs(format.sampleMimeType)
                                        .setCueReplacementBehavior(
                                            subtitleParserFactory.getCueReplacementBehavior(
                                                format,
                                            ),
                                        )
                                        .build(),
                                )
                            } catch (e: Exception) {
                                Log.w(
                                    this::class.simpleName,
                                    "failed to set subtitle lazy-loading: ${e.stackTrace}",
                                )
                            }
                            progressiveMediaSourceFactory.createMediaSource(
                                MediaItem.fromUri(subtitleUrl),
                            )
                        }

                exoPlayer?.setMediaSource(MergingMediaSource(*mediaSources.toTypedArray()))
                return true
            }
            is PlaybackSource.Hls -> {
                val hlsMediaSourceFactory =
                    HlsMediaSource.Factory(cachingDataSourceFactory)
                        .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())
                        .setAllowChunklessPreparation(true)

                val mediaItem =
                    createMediaItem(
                        source.url.toUri(),
                        MimeTypes.APPLICATION_M3U8,
                        streams,
                    )
                val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

                exoPlayer?.setMediaSource(mediaSource)
                return true
            }
            PlaybackSource.None -> {
                toastFromMainThread(R.string.unknown_error)
                return false
            }
        }
    }

    private fun getSubtitleConfigs(): List<SubtitleConfiguration> =
        streams
            ?.subtitles
            ?.mapNotNull { subtitle ->
                val url = subtitle.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val roleFlags = getSubtitleRoleFlags(subtitle)
                SubtitleConfiguration.Builder(url.toUri())
                    .setRoleFlags(roleFlags)
                    .setLanguage(subtitle.code)
                    .setMimeType(subtitle.mimeType)
                    .build()
            }
            .orEmpty()

    private fun createMediaItem(uri: Uri, mimeType: String, streams: Streams): MediaItem {
        val builder =
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mimeType)
                .setSubtitleConfigurations(getSubtitleConfigs())
                .setMetadata(streams, videoId)

        if (streams.isLive) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(8_000)
                    .setMinOffsetMs(4_000)
                    .setMaxOffsetMs(15_000)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build(),
            )
        }
        return builder.build()
    }
}
