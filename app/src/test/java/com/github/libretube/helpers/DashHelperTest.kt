package com.github.libretube.helpers

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashHelperTest {
    @Test
    fun `incomplete streams are skipped instead of crashing manifest creation`() {
        val manifest =
            DashHelper.createManifest(
                streams =
                    Streams(
                        title = "Video",
                        description = "Description",
                        uploader = "Channel",
                        uploaderUrl = "UCchannel",
                        thumbnailUrl = "https://example.com/thumb.jpg",
                        category = "Education",
                        uploaderVerified = false,
                        duration = 120,
                        videoStreams =
                            listOf(
                                PipedStream(
                                    url = "https://example.com/good-video",
                                    videoOnly = true,
                                    mimeType = "video/mp4",
                                    codec = "avc1.4d401f",
                                    bitrate = 1_000_000,
                                    width = 1280,
                                    height = 720,
                                    fps = 30,
                                    initStart = 0,
                                    initEnd = 700,
                                    indexStart = 701,
                                    indexEnd = 1200,
                                ),
                                PipedStream(
                                    url = "https://example.com/missing-codec",
                                    videoOnly = true,
                                    mimeType = "video/mp4",
                                    codec = null,
                                    indexEnd = 500,
                                ),
                            ),
                        audioStreams =
                            listOf(
                                PipedStream(
                                    url = null,
                                    mimeType = "audio/mp4",
                                    codec = "mp4a.40.2",
                                    videoOnly = false,
                                ),
                                PipedStream(
                                    url = "https://example.com/good-audio",
                                    mimeType = "audio/mp4",
                                    codec = "mp4a.40.2",
                                    videoOnly = false,
                                    bitrate = 128000,
                                    initStart = 0,
                                    initEnd = 600,
                                    indexStart = 601,
                                    indexEnd = 900,
                                ),
                            ),
                    ),
                supportsHdr = false,
            )

        assertTrue(manifest.contains("https://example.com/good-video"))
        assertTrue(manifest.contains("https://example.com/good-audio"))
        assertFalse(manifest.contains("https://example.com/missing-codec"))
    }
}
