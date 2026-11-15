package com.github.libretube.player

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams

sealed class PlaybackSource {
    data object GeneratedDash : PlaybackSource()

    data class RemoteDash(val url: String) : PlaybackSource()

    data object Sabr : PlaybackSource()

    data class Hls(val url: String) : PlaybackSource()

    data object None : PlaybackSource()
}

fun PipedStream.isPlayableDashVideo(): Boolean {
    return videoOnly == true && hasDashIndex()
}

fun PipedStream.isPlayableDashAudio(): Boolean {
    return videoOnly != true && hasDashIndex()
}

private fun PipedStream.hasDashIndex(): Boolean {
    return !url.isNullOrBlank() &&
        url?.startsWith("sabr://") != true &&
        !mimeType.isNullOrBlank() &&
        !codec.isNullOrBlank() &&
        initStart != null &&
        initEnd != null &&
        indexStart != null &&
        (indexEnd ?: 0) > 0
}

/**
 * Picks a stream that ExoPlayer can actually open. A plain video URL is not enough for a generated
 * DASH manifest: without an index range the player errors immediately and the service used to shut
 * down.
 */
fun choosePlaybackSource(streams: Streams): PlaybackSource {
    if (streams.isLive) {
        if (!streams.dash.isNullOrBlank()) return PlaybackSource.RemoteDash(streams.dash)
        if (!streams.hls.isNullOrBlank()) return PlaybackSource.Hls(streams.hls)
        return PlaybackSource.None
    }

    val playableVideo = streams.videoStreams.any { it.isPlayableDashVideo() }
    val playableAudio = streams.audioStreams.any { it.isPlayableDashAudio() }
    if (playableVideo && playableAudio) return PlaybackSource.GeneratedDash
    if (
        !streams.serverAbrStreamingUrl.isNullOrBlank() &&
            !streams.videoPlaybackUstreamerConfig.isNullOrBlank()
    ) {
        return PlaybackSource.Sabr
    }
    if (!streams.hls.isNullOrBlank()) return PlaybackSource.Hls(streams.hls)
    if (playableVideo || playableAudio) return PlaybackSource.GeneratedDash
    return PlaybackSource.None
}

/**
 * The player is idle while a video is still loading. Destroying the service there closes playback
 * before it starts. Idle after playback has begun, and with no refresh in flight, means the session
 * is done.
 */
fun shouldDestroyPlayerOnIdle(
    isTransitioning: Boolean,
    hasPendingRecovery: Boolean,
    hasPlaybackStarted: Boolean = true,
    hasConnectedControllers: Boolean = false,
): Boolean {
    return hasPlaybackStarted && !hasConnectedControllers && !isTransitioning && !hasPendingRecovery
}
