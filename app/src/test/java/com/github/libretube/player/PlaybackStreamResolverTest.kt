package com.github.libretube.player

import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.github.libretube.api.AllowlistMediaServiceRepository
import com.github.libretube.api.NewPipeMediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.player.parser.SabrStreamException
import com.github.libretube.util.AccessControl
import com.github.libretube.util.InMemoryAccessControlStorage
import com.github.libretube.util.NewPipeDownloaderImpl
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

@androidx.media3.common.util.UnstableApi
class PlaybackStreamResolverTest {

    private fun disabledAccessControl(): AccessControl =
        AccessControl(storage = InMemoryAccessControlStorage(allowlistEnabled = false))

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
        title: String = "Video",
        videoStreams: List<PipedStream> = emptyList(),
        audioStreams: List<PipedStream> = emptyList(),
        serverAbrStreamingUrl: String? = null,
        videoPlaybackUstreamerConfig: String? = null,
        dash: String? = null,
        hls: String? = null,
        isLive: Boolean = false,
    ): Streams =
        Streams(
            title = title,
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
            dash = dash,
            hls = hls,
            livestream = isLive,
        )

    private fun httpError(statusCode: Int): PlaybackException {
        val cause =
            HttpDataSource.InvalidResponseCodeException(
                statusCode,
                "HTTP $statusCode",
                IOException("HTTP $statusCode"),
                emptyMap(),
                createTestDataSpec("https://rr1---sn-test.googlevideo.com/videoplayback?itag=140"),
                ByteArray(0),
            )
        return object :
            PlaybackException(
                "Playback failed",
                cause,
                ERROR_CODE_IO_BAD_HTTP_STATUS,
                Bundle(),
                1234L,
            ) {}
    }

    private fun fakeIosClient(attemptProvider: () -> Int): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val body =
                    if (req.header("User-Agent") == NewPipeDownloaderImpl.IOS_USER_AGENT) {
                        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":140,"url":"https://rr1---sn-test.googlevideo.com/videoplayback?c=IOS&attempt=${attemptProvider()}"}]}}"""
                    } else {
                        """{"playabilityStatus":{"status":"OK"}}"""
                    }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun `choosePlaybackSource picks generated DASH, SABR fallback, HLS, and live sources cleanly`() {
        val unindexedWithSabr =
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
            )
        assertEquals(
            PlaybackSource.Sabr,
            PlaybackStreamResolver.choosePlaybackSource(unindexedWithSabr),
        )

        val indexedDash =
            sampleStreams(
                videoStreams = listOf(playableVideo()),
                audioStreams = listOf(playableAudio()),
                serverAbrStreamingUrl = "https://example.com/sabr",
                videoPlaybackUstreamerConfig = "ustreamer",
            )
        assertEquals(
            PlaybackSource.GeneratedDash,
            PlaybackStreamResolver.choosePlaybackSource(indexedDash),
        )

        val liveDash = sampleStreams(isLive = true, dash = "https://example.com/live.mpd")
        assertEquals(
            PlaybackSource.RemoteDash("https://example.com/live.mpd"),
            PlaybackStreamResolver.choosePlaybackSource(liveDash),
        )

        val liveHls = sampleStreams(isLive = true, hls = "https://example.com/live.m3u8")
        assertEquals(
            PlaybackSource.Hls("https://example.com/live.m3u8"),
            PlaybackStreamResolver.choosePlaybackSource(liveHls),
        )
    }

    @Test
    fun `resolve pairs streams, video-scoped transport context, and chosen source in one call`() =
        runBlocking {
            val clientA = OkHttpClient.Builder().build()
            val repo =
                AllowlistMediaServiceRepository(
                    delegate =
                        NewPipeMediaServiceRepository(
                            httpClient = clientA,
                            resolveRecordedUserAgent = { id ->
                                if (id == "vid_1") StreamTransportContext.IOS_USER_AGENT else null
                            },
                            remoteStreamsFetcher = {
                                sampleStreams(
                                    title = "Original",
                                    videoStreams = listOf(playableVideo()),
                                    audioStreams = listOf(playableAudio()),
                                )
                            },
                        ),
                    accessControl = disabledAccessControl(),
                )
            val resolver =
                PlaybackStreamResolver(
                    repository = repo,
                    enrichStreams = { s, _ -> s.copy(title = "DeArrowed") },
                )

            val resolved = resolver.resolve("vid_1")
            assertEquals("vid_1", resolved.videoId)
            assertEquals("DeArrowed", resolved.streams.title)
            assertEquals(PlaybackSource.GeneratedDash, resolved.source)
            assertEquals(StreamTransportContext.IOS_USER_AGENT, resolved.transportContext.userAgent)
            assertSame(clientA, resolved.transportContext.httpClient)
        }

    @Test
    fun `recover handles HTTP 403, 410, and SABR reload once per video and rejects non-recoverable errors`() =
        runBlocking {
            var fetchCount = 0
            val repo =
                AllowlistMediaServiceRepository(
                    delegate =
                        NewPipeMediaServiceRepository(
                            remoteStreamsFetcher = { id ->
                                fetchCount++
                                sampleStreams(title = "Fetch #$fetchCount for $id")
                            },
                        ),
                    accessControl = disabledAccessControl(),
                )
            val resolver = PlaybackStreamResolver(repository = repo, enrichStreams = { s, _ -> s })

            resolver.onVideoStarted("vid_403")
            assertTrue(resolver.beginRecoveryIfEligible("vid_403", httpError(403)))
            val outcome403 = resolver.recover("vid_403", resumePositionMs = 42_000L)
            assertTrue(outcome403 is StreamRecoveryOutcome.Recovered)
            val recovered403 = outcome403 as StreamRecoveryOutcome.Recovered
            assertEquals("Fetch #1 for vid_403", recovered403.resolved.streams.title)
            assertEquals(42_000L, recovered403.resumePositionMs)

            // Retry budget exhausted for same video
            assertFalse(resolver.beginRecoveryIfEligible("vid_403", httpError(403)))

            // New video resets budget and recovers on 410 and SABR reload
            resolver.onVideoStarted("vid_410")
            assertTrue(resolver.beginRecoveryIfEligible("vid_410", httpError(410)))
            val outcome410 = resolver.recover("vid_410", resumePositionMs = 15_000L)
            assertEquals(15_000L, (outcome410 as StreamRecoveryOutcome.Recovered).resumePositionMs)

            resolver.onVideoStarted("vid_sabr")
            val sabrReload = SabrStreamException("Reload required", isReloadRequired = true)
            assertTrue(resolver.beginRecoveryIfEligible("vid_sabr", sabrReload))
            assertTrue(resolver.recover("vid_sabr", 9_000L) is StreamRecoveryOutcome.Recovered)

            // Non-recoverable errors (500, IO error) never start recovery
            resolver.onVideoStarted("vid_500")
            assertFalse(resolver.beginRecoveryIfEligible("vid_500", httpError(500)))
            assertFalse(resolver.beginRecoveryIfEligible("vid_500", IOException("Offline")))
        }

    @Test
    fun `recover returns Stale if active video switched mid-flight and Failed if refetch throws`() =
        runBlocking {
            lateinit var resolver: PlaybackStreamResolver
            val switchingRepo =
                AllowlistMediaServiceRepository(
                    delegate =
                        NewPipeMediaServiceRepository(
                            remoteStreamsFetcher = { id ->
                                resolver.onVideoStarted("vid_next")
                                sampleStreams(title = "Stale for $id")
                            },
                        ),
                    accessControl = disabledAccessControl(),
                )
            resolver =
                PlaybackStreamResolver(repository = switchingRepo, enrichStreams = { s, _ -> s })
            resolver.onVideoStarted("vid_old")
            assertTrue(resolver.beginRecoveryIfEligible("vid_old", httpError(403)))
            assertEquals(StreamRecoveryOutcome.Stale, resolver.recover("vid_old", 1_000L))

            val failingRepo =
                AllowlistMediaServiceRepository(
                    delegate =
                        NewPipeMediaServiceRepository(
                            remoteStreamsFetcher = { throw IOException("Network down") },
                        ),
                    accessControl = disabledAccessControl(),
                )
            val failingResolver =
                PlaybackStreamResolver(repository = failingRepo, enrichStreams = { s, _ -> s })
            failingResolver.onVideoStarted("vid_fail")
            assertTrue(failingResolver.beginRecoveryIfEligible("vid_fail", httpError(403)))
            val failOutcome = failingResolver.recover("vid_fail", 1_000L)
            assertTrue(failOutcome is StreamRecoveryOutcome.Failed)
            assertFalse(failingResolver.isRefreshing)
        }

    @Test
    fun `mid-stream 403 refreshes expired stream URL and propagates updated downloader User-Agent`() =
        runBlocking {
            val videoId = "vid_e2e_403"
            var attempt = 0
            val fakeHttp = fakeIosClient { attempt }
            val downloader = NewPipeDownloaderImpl(fakeHttp)
            val repo =
                AllowlistMediaServiceRepository(
                    delegate =
                        NewPipeMediaServiceRepository(
                            httpClient = fakeHttp,
                            resolveRecordedUserAgent = downloader::recordedUserAgent,
                            remoteStreamsFetcher = { id ->
                                attempt++
                                if (attempt >= 2) {
                                    val payload =
                                        """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20260101.00.00","hl":"en","gl":"US"}},"videoId":"$id"}"""
                                            .toByteArray(StandardCharsets.UTF_8)
                                    downloader.execute(
                                        Request.Builder()
                                            .post(
                                                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                                                payload,
                                            )
                                            .build(),
                                    )
                                }
                                sampleStreams(title = "Attempt #$attempt")
                            },
                        ),
                    accessControl = disabledAccessControl(),
                )
            val resolver = PlaybackStreamResolver(repository = repo, enrichStreams = { s, _ -> s })
            resolver.onVideoStarted(videoId)

            val initial = resolver.resolve(videoId)
            assertEquals(
                StreamTransportContext.DEFAULT_USER_AGENT,
                initial.transportContext.userAgent,
            )

            assertTrue(resolver.beginRecoveryIfEligible(videoId, httpError(403)))
            val recovered = resolver.recover(videoId, 30_000L) as StreamRecoveryOutcome.Recovered
            assertEquals("Attempt #2", recovered.resolved.streams.title)
            assertEquals(
                StreamTransportContext.IOS_USER_AGENT,
                recovered.resolved.transportContext.userAgent,
            )
        }

    @Test
    fun `shouldDestroyOnIdle guards startup, pending recovery, and connected controllers`() {
        val resolver = PlaybackStreamResolver(repository = AllowlistMediaServiceRepository())
        resolver.onVideoStarted("vid_idle")

        assertFalse(
            resolver.shouldDestroyOnIdle(
                videoId = "vid_idle",
                playerError = null,
                isTransitioning = true,
                hasPlaybackStarted = true,
                hasConnectedControllers = false,
            ),
        )
        assertFalse(
            resolver.shouldDestroyOnIdle(
                videoId = "vid_idle",
                playerError = null,
                isTransitioning = false,
                hasPlaybackStarted = false,
                hasConnectedControllers = false,
            ),
        )
        assertFalse(
            resolver.shouldDestroyOnIdle(
                videoId = "vid_idle",
                playerError = null,
                isTransitioning = false,
                hasPlaybackStarted = true,
                hasConnectedControllers = true,
            ),
        )
        assertFalse(
            resolver.shouldDestroyOnIdle(
                videoId = "vid_idle",
                playerError = httpError(403),
                isTransitioning = false,
                hasPlaybackStarted = true,
                hasConnectedControllers = false,
            ),
        )
        assertTrue(
            resolver.shouldDestroyOnIdle(
                videoId = "vid_idle",
                playerError = null,
                isTransitioning = false,
                hasPlaybackStarted = true,
                hasConnectedControllers = false,
            ),
        )
    }
}
