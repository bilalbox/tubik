package com.github.libretube.player

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackSourceTest {
    @Test
    fun `video urls without dash index ranges fall through to sabr`() {
        val source =
            choosePlaybackSource(
                sampleStreams(
                    videoStreams =
                        listOf(
                            PipedStream(
                                url = "https://example.com/video",
                                videoOnly = true,
                                mimeType = "video/mp4",
                                codec = "avc1.4d401f",
                                indexEnd = 0,
                            ),
                        ),
                    audioStreams =
                        listOf(
                            PipedStream(
                                url = "https://example.com/audio",
                                mimeType = "audio/mp4",
                                codec = "mp4a.40.2",
                                videoOnly = false,
                                indexEnd = 0,
                            ),
                        ),
                    serverAbrStreamingUrl = "https://example.com/sabr",
                    videoPlaybackUstreamerConfig = "ustreamer",
                ),
            )

        assertEquals(PlaybackSource.Sabr, source)
    }

    @Test
    fun `indexed video and audio stay on generated dash`() {
        val source =
            choosePlaybackSource(
                sampleStreams(
                    videoStreams = listOf(playableVideo()),
                    audioStreams = listOf(playableAudio()),
                    serverAbrStreamingUrl = "https://example.com/sabr",
                    videoPlaybackUstreamerConfig = "ustreamer",
                ),
            )

        assertEquals(PlaybackSource.GeneratedDash, source)
    }

    @Test
    fun `idle during startup does not tear the player down`() {
        assertFalse(shouldDestroyPlayerOnIdle(isTransitioning = true, hasPendingRecovery = false))
    }

    @Test
    fun `idle after playback started and with no recovery still stops the service`() {
        assertEquals(
            true,
            shouldDestroyPlayerOnIdle(isTransitioning = false, hasPendingRecovery = false),
        )
    }

    @Test
    fun `idle before playback has ever started does not tear the player down`() {
        assertFalse(
            shouldDestroyPlayerOnIdle(
                isTransitioning = false,
                hasPendingRecovery = false,
                hasPlaybackStarted = false,
                hasConnectedControllers = false,
            ),
        )
    }

    @Test
    fun `idle while a player UI controller is still connected does not tear the player down`() {
        assertFalse(
            shouldDestroyPlayerOnIdle(
                isTransitioning = false,
                hasPendingRecovery = false,
                hasPlaybackStarted = true,
                hasConnectedControllers = true,
            ),
        )
    }

    private fun playableVideo() =
        PipedStream(
            url = "https://example.com/video",
            videoOnly = true,
            mimeType = "video/mp4",
            codec = "avc1.4d401f",
            initStart = 0,
            initEnd = 700,
            indexStart = 701,
            indexEnd = 1200,
            width = 1280,
            height = 720,
            fps = 30,
            bitrate = 1_000_000,
        )

    private fun playableAudio() =
        PipedStream(
            url = "https://example.com/audio",
            videoOnly = false,
            mimeType = "audio/mp4",
            codec = "mp4a.40.2",
            initStart = 0,
            initEnd = 600,
            indexStart = 601,
            indexEnd = 900,
            bitrate = 128000,
        )

    private fun sampleStreams(
        videoStreams: List<PipedStream>,
        audioStreams: List<PipedStream>,
        serverAbrStreamingUrl: String? = null,
        videoPlaybackUstreamerConfig: String? = null,
        hls: String? = null,
    ) =
        Streams(
            title = "Video",
            description = "Description",
            uploader = "Channel",
            uploaderUrl = "UCchannel",
            thumbnailUrl = "https://example.com/thumb.jpg",
            category = "Education",
            uploaderVerified = false,
            duration = 120,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            serverAbrStreamingUrl = serverAbrStreamingUrl,
            videoPlaybackUstreamerConfig = videoPlaybackUstreamerConfig,
            hls = hls,
        )
}
