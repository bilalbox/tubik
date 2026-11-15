package com.github.libretube.player

import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.github.libretube.api.AllowlistMediaServiceRepository
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.NewPipeMediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.player.parser.SabrStreamException
import com.github.libretube.util.AllowedCatalog
import com.github.libretube.util.AllowedSnapshot
import com.github.libretube.util.AllowedSnapshotProvider
import com.github.libretube.util.ClientUserAgentRegistry
import com.github.libretube.util.NewPipeDownloaderImpl
import com.github.libretube.util.PlayingQueue
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
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

class StreamRefreshRecoveryTest {

    private fun disabledAllowedCatalog(): AllowedCatalog =
        AllowedCatalog(
            snapshotProvider =
                object : AllowedSnapshotProvider {
                    override fun isAllowlistEnabled(): Boolean = false

                    override suspend fun snapshot(): AllowedSnapshot =
                        AllowedSnapshot(allowlistEnabled = false)
                },
        )

    @Test
    fun `invalidateStreamsCache evicts cached streams so next getStreams fetches fresh data`() =
        runBlocking {
            val videoId = "vid_cache_evict_1"
            var fetchCount = 0

            val newPipeRepo =
                NewPipeMediaServiceRepository(
                    remoteStreamsFetcher = { id ->
                        fetchCount++
                        sampleStreams(title = "Fetch #$fetchCount for $id")
                    },
                )
            val repo: MediaServiceRepository =
                AllowlistMediaServiceRepository(
                    delegate = newPipeRepo,
                    allowedCatalog = disabledAllowedCatalog(),
                )

            val first = repo.getStreams(videoId)
            val second = repo.getStreams(videoId)
            assertEquals("Fetch #1 for $videoId", first.title)
            assertEquals("Fetch #1 for $videoId", second.title)
            assertEquals(1, fetchCount)

            repo.invalidateStreamsCache(videoId)

            val refreshed = repo.getStreams(videoId)
            assertEquals("Fetch #2 for $videoId", refreshed.title)
            assertEquals(2, fetchCount)
        }

    @Test
    fun `repository instances isolate stream caches and build video-scoped StreamTransportContext`() =
        runBlocking {
            val sharedVideoId = "vid_repo_isolated"
            val customClient = OkHttpClient.Builder().build()
            val customPoTokenProvider =
                object : PoTokenProvider {
                    override fun getWebClientPoToken(videoId: String): PoTokenResult =
                        PoTokenResult("vis-$videoId", "req-$videoId", "stream-$videoId")
                }
            val registryA =
                ClientUserAgentRegistry().apply {
                    record(sharedVideoId, NewPipeDownloaderImpl.IOS_USER_AGENT)
                }
            val registryB =
                ClientUserAgentRegistry().apply {
                    record(sharedVideoId, NewPipeDownloaderImpl.ANDROID_KIDS_USER_AGENT)
                }

            var fetchCountA = 0
            var fetchCountB = 0
            val repoA =
                NewPipeMediaServiceRepository(
                    poTokenProvider = customPoTokenProvider,
                    httpClient = customClient,
                    userAgentRegistry = registryA,
                    remoteStreamsFetcher = { id ->
                        fetchCountA++
                        sampleStreams(title = "RepoA #$fetchCountA for $id")
                    },
                )
            val repoB =
                NewPipeMediaServiceRepository(
                    userAgentRegistry = registryB,
                    remoteStreamsFetcher = { id ->
                        fetchCountB++
                        sampleStreams(title = "RepoB #$fetchCountB for $id")
                    },
                )

            assertEquals("RepoA #1 for $sharedVideoId", repoA.getStreams(sharedVideoId).title)
            assertEquals("RepoB #1 for $sharedVideoId", repoB.getStreams(sharedVideoId).title)
            assertEquals(1, fetchCountA)
            assertEquals(1, fetchCountB)

            // Invalidating repoA must not affect repoB's cache
            repoA.invalidateStreamsCache(sharedVideoId)
            assertEquals("RepoA #2 for $sharedVideoId", repoA.getStreams(sharedVideoId).title)
            assertEquals("RepoB #1 for $sharedVideoId", repoB.getStreams(sharedVideoId).title)
            assertEquals(2, fetchCountA)
            assertEquals(1, fetchCountB)

            val wrappedRepo: MediaServiceRepository =
                AllowlistMediaServiceRepository(
                    delegate = repoA,
                    allowedCatalog = disabledAllowedCatalog(),
                )
            val transportContext = wrappedRepo.getStreamTransportContext(sharedVideoId)
            assertEquals(sharedVideoId, transportContext.videoId)
            assertEquals(NewPipeDownloaderImpl.IOS_USER_AGENT, transportContext.userAgent)
            assertSame(customClient, transportContext.httpClient)
            assertSame(customPoTokenProvider, transportContext.poTokenProvider)
        }

    @Test
    fun `mid-stream 403, 410, and SABR reload errors invalidate cache once and resume at saved position`() =
        runBlocking {
            val videoId = "vid_recover_1"
            var fetchCount = 0
            val newPipeRepo =
                NewPipeMediaServiceRepository(
                    remoteStreamsFetcher = { id ->
                        fetchCount++
                        sampleStreams(title = "Streams #$fetchCount for $id")
                    },
                )
            val repo: MediaServiceRepository =
                AllowlistMediaServiceRepository(
                    delegate = newPipeRepo,
                    allowedCatalog = disabledAllowedCatalog(),
                )

            // Warm cache with initial fetch
            repo.getStreams(videoId)
            assertEquals(1, fetchCount)

            val resumed = mutableListOf<Pair<String, Long>>()
            val coordinator =
                StreamRefreshCoordinator(
                    invalidateStreamsCache = { repo.invalidateStreamsCache(it) },
                    fetchStreams = { repo.getStreams(it) },
                    resolveTransportContext = { repo.getStreamTransportContext(it) },
                )

            // 1. HTTP 403 recovers at 42_500ms
            coordinator.onVideoStarted(videoId)
            val err403 = wrapInPlaybackException(httpCodeException(403))
            assertTrue(coordinator.beginRecoveryIfEligible(videoId, err403))
            val outcome403 = coordinator.executeRecovery(videoId, resumePositionMs = 42_500L)
            assertTrue(outcome403 is StreamRefreshCoordinator.RecoveryOutcome.Recovered)
            val recovered403 = outcome403 as StreamRefreshCoordinator.RecoveryOutcome.Recovered
            assertEquals(videoId, recovered403.transportContext.videoId)
            resumed += recovered403.streams.title to recovered403.resumePositionMs
            assertEquals(listOf("Streams #2 for $videoId" to 42_500L), resumed)
            assertEquals(2, fetchCount)

            // 2. HTTP 410 on a fresh video playback recovers at 18_000ms
            val videoId410 = "vid_recover_410"
            repo.getStreams(videoId410)
            coordinator.onVideoStarted(videoId410)
            val err410 = wrapInPlaybackException(httpCodeException(410))
            assertTrue(coordinator.beginRecoveryIfEligible(videoId410, err410))
            val outcome410 = coordinator.executeRecovery(videoId410, resumePositionMs = 18_000L)
            assertTrue(outcome410 is StreamRefreshCoordinator.RecoveryOutcome.Recovered)
            assertEquals(
                18_000L,
                (outcome410 as StreamRefreshCoordinator.RecoveryOutcome.Recovered).resumePositionMs,
            )

            // 3. SABR RELOAD_PLAYER_RESPONSE recovers at 95_000ms
            val videoIdSabr = "vid_recover_sabr"
            repo.getStreams(videoIdSabr)
            coordinator.onVideoStarted(videoIdSabr)
            val errSabr =
                wrapInPlaybackException(
                    SabrStreamException("Server requested player reload", isReloadRequired = true),
                )
            assertTrue(coordinator.beginRecoveryIfEligible(videoIdSabr, errSabr))
            val outcomeSabr = coordinator.executeRecovery(videoIdSabr, resumePositionMs = 95_000L)
            assertTrue(outcomeSabr is StreamRefreshCoordinator.RecoveryOutcome.Recovered)
            assertEquals(
                95_000L,
                (outcomeSabr as StreamRefreshCoordinator.RecoveryOutcome.Recovered)
                    .resumePositionMs,
            )
        }

    @Test
    fun `persistent 403 or failed refresh is bounded to single retry and does not loop infinitely`() =
        runBlocking {
            val videoId = "vid_bounded_1"
            var invalidations = 0
            var fetches = 0

            val coordinator =
                StreamRefreshCoordinator(
                    invalidateStreamsCache = { invalidations++ },
                    fetchStreams = { id ->
                        fetches++
                        sampleStreams(title = "Refresh #$fetches for $id")
                    },
                    maxRetries = 1,
                )
            coordinator.onVideoStarted(videoId)

            val err403 = wrapInPlaybackException(httpCodeException(403))
            // First 403 is eligible
            assertTrue(coordinator.beginRecoveryIfEligible(videoId, err403))
            // Concurrent duplicate error while refresh in-flight does not start a second fetch
            assertFalse(coordinator.canStartNewRecovery(videoId, err403))
            coordinator.executeRecovery(videoId, resumePositionMs = 12_000L)

            // Second 403 for the same video must NOT retry again
            assertFalse(coordinator.beginRecoveryIfEligible(videoId, err403))
            assertEquals(1, invalidations)
            assertEquals(1, fetches)

            // Non-recoverable HTTP 404 or generic IOException never triggers recovery even on a
            // fresh video
            val videoId404 = "vid_non_recoverable"
            coordinator.onVideoStarted(videoId404)
            assertFalse(
                coordinator.beginRecoveryIfEligible(
                    videoId404,
                    wrapInPlaybackException(httpCodeException(404)),
                ),
            )
            assertFalse(
                coordinator.beginRecoveryIfEligible(
                    videoId404,
                    wrapInPlaybackException(IOException("timeout")),
                ),
            )
            assertEquals(1, invalidations)
            assertEquals(1, fetches)

            // Refresh failure also leaves retry budget exhausted and resets refreshing flag
            val failingCoordinator =
                StreamRefreshCoordinator(
                    invalidateStreamsCache = { invalidations++ },
                    fetchStreams = { throw IOException("Network down") },
                    maxRetries = 1,
                )
            failingCoordinator.onVideoStarted("vid_fail_refresh")
            assertTrue(failingCoordinator.beginRecoveryIfEligible("vid_fail_refresh", err403))
            val failOutcome =
                failingCoordinator.executeRecovery("vid_fail_refresh", resumePositionMs = 5_000L)
            assertTrue(failOutcome is StreamRefreshCoordinator.RecoveryOutcome.Failed)
            assertFalse(failingCoordinator.isRefreshing)
            assertFalse(failingCoordinator.beginRecoveryIfEligible("vid_fail_refresh", err403))
        }

    @Test
    fun `integration - fallback IOS extraction, 403 cache invalidation, single refresh, and User-Agent propagation`() =
        runBlocking {
            val videoId = "vid_integration_403"

            var playerExtractionRound = 0
            val segmentRequests = mutableListOf<Pair<String, String?>>()

            val mockHttpClient =
                NewPipeDownloaderImpl.httpClient
                    .newBuilder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val reqUrl = req.url.toString()
                        val ua = req.header("User-Agent")

                        when {
                            reqUrl.contains("/youtubei/v1/visitor_id") -> {
                                Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_2)
                                    .code(200)
                                    .message("OK")
                                    .body(
                                        """{"responseContext":{"visitorData":"ios_vis"}}"""
                                            .toResponseBody("application/json".toMediaType()),
                                    )
                                    .build()
                            }
                            reqUrl.contains("/youtubei/v1/player") -> {
                                val bodyJson =
                                    if (ua == NewPipeDownloaderImpl.IOS_USER_AGENT) {
                                        playerExtractionRound++
                                        """
                            {
                              "playabilityStatus":{"status":"OK"},
                              "streamingData":{
                                "adaptiveFormats":[
                                  {"itag":140,"url":"https://rr1---sn-test.googlevideo.com/videoplayback?gen=$playerExtractionRound"}
                                ]
                              }
                            }
                            """
                                            .trimIndent()
                                    } else {
                                        // Primary WEB returns unplayable so fallback to IOS
                                        // triggers
                                        """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""
                                    }
                                Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_2)
                                    .code(200)
                                    .message("OK")
                                    .body(bodyJson.toResponseBody("application/json".toMediaType()))
                                    .build()
                            }
                            reqUrl.contains("googlevideo.com") -> {
                                segmentRequests += reqUrl to ua
                                if (reqUrl.contains("gen=1")) {
                                    Response.Builder()
                                        .request(req)
                                        .protocol(Protocol.HTTP_2)
                                        .code(403)
                                        .message("Forbidden")
                                        .body("expired".toResponseBody("text/plain".toMediaType()))
                                        .build()
                                } else {
                                    Response.Builder()
                                        .request(req)
                                        .protocol(Protocol.HTTP_2)
                                        .code(200)
                                        .message("OK")
                                        .body(
                                            "fresh-media-bytes"
                                                .toByteArray()
                                                .toResponseBody("video/mp4".toMediaType()),
                                        )
                                        .build()
                                }
                            }
                            else -> error("Unexpected URL: $reqUrl")
                        }
                    }
                    .build()

            val registry = ClientUserAgentRegistry()
            val downloader = NewPipeDownloaderImpl(mockHttpClient, registry)
            val primaryPlayerPayload =
                """
            {"context":{"client":{"clientName":"WEB","clientVersion":"2.20260101.00.00","hl":"en","gl":"US"}},"videoId":"$videoId"}
        """
                    .trimIndent()
                    .toByteArray(StandardCharsets.UTF_8)

            val repo =
                NewPipeMediaServiceRepository(
                    httpClient = mockHttpClient,
                    userAgentRegistry = registry,
                    remoteStreamsFetcher = {
                        val resp =
                            downloader.execute(
                                Request.Builder()
                                    .post(
                                        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                                        primaryPlayerPayload,
                                    )
                                    .build(),
                            )
                        val gen = if (resp.responseBody().contains("gen=1")) 1 else 2
                        sampleStreams(
                            title = "Stream gen=$gen",
                            streamUrl =
                                "https://rr1---sn-test.googlevideo.com/videoplayback?gen=$gen",
                        )
                    },
                )

            val initialStreams = repo.getStreams(videoId)
            val initialTransport = repo.getStreamTransportContext(videoId)
            val initialUrl = initialStreams.audioStreams.first().url!!
            val initialDataSourceFactory =
                OkHttpMediaDataSource.Factory(
                    transportContext = initialTransport,
                    dataSpecUriToString = { it.key!! },
                )

            val coordinator =
                StreamRefreshCoordinator(
                    invalidateStreamsCache = { repo.invalidateStreamsCache(it) },
                    fetchStreams = { repo.getStreams(it) },
                    resolveTransportContext = { repo.getStreamTransportContext(it) },
                )
            coordinator.onVideoStarted(videoId)

            // Mid-stream 403 on initial URL
            val ds1 = initialDataSourceFactory.createDataSource()
            var recoveredOutcome: StreamRefreshCoordinator.RecoveryOutcome.Recovered? = null
            try {
                ds1.open(createTestDataSpec(url = initialUrl, position = 1024L, length = 512L))
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                val playbackError = wrapInPlaybackException(e)
                assertTrue(coordinator.beginRecoveryIfEligible(videoId, playbackError))
                val outcome = coordinator.executeRecovery(videoId, resumePositionMs = 30_000L)
                assertTrue(outcome is StreamRefreshCoordinator.RecoveryOutcome.Recovered)
                recoveredOutcome = outcome as StreamRefreshCoordinator.RecoveryOutcome.Recovered
                assertEquals(30_000L, outcome.resumePositionMs)
            } finally {
                ds1.close()
            }

            // Fresh URL succeeds with explicit refreshed StreamTransportContext carrying
            // IOS_USER_AGENT
            val refreshedUrl = recoveredOutcome!!.streams.audioStreams.first().url!!
            val refreshedDataSourceFactory =
                OkHttpMediaDataSource.Factory(
                    transportContext = recoveredOutcome.transportContext,
                    dataSpecUriToString = { it.key!! },
                )
            val ds2 = refreshedDataSourceFactory.createDataSource()
            ds2.open(createTestDataSpec(url = refreshedUrl))
            val buf = ByteArray(32)
            val bytesRead = ds2.read(buf, 0, buf.size)
            ds2.close()

            assertEquals("fresh-media-bytes", String(buf, 0, bytesRead, StandardCharsets.UTF_8))
            assertEquals(2, segmentRequests.size)
            assertEquals(NewPipeDownloaderImpl.IOS_USER_AGENT, segmentRequests[0].second)
            assertEquals(NewPipeDownloaderImpl.IOS_USER_AGENT, segmentRequests[1].second)
            assertTrue(segmentRequests[0].first.contains("gen=1"))
            assertTrue(segmentRequests[1].first.contains("gen=2"))
        }

    @Test
    fun `failed 403 recovery tears down service even when PlayingQueue holds the failing stream`() =
        runBlocking {
            val videoId = "vid_fail_cleanup"
            PlayingQueue.clear()
            try {
                PlayingQueue.updateCurrent(
                    StreamItem(url = "/watch?v=$videoId", title = "Failing Stream"),
                )
                assertFalse(PlayingQueue.isEmpty())

                val coordinator =
                    StreamRefreshCoordinator(
                        invalidateStreamsCache = {},
                        fetchStreams = { throw IOException("Refresh failed") },
                        maxRetries = 1,
                    )
                coordinator.onVideoStarted(videoId)
                val err403 = wrapInPlaybackException(httpCodeException(403))

                assertTrue(coordinator.beginRecoveryIfEligible(videoId, err403))
                // While recovery is in-flight, STATE_IDLE must keep service alive
                assertTrue(coordinator.hasPendingRecovery(videoId, err403))

                val outcome = coordinator.executeRecovery(videoId, resumePositionMs = 15_000L)
                assertTrue(outcome is StreamRefreshCoordinator.RecoveryOutcome.Failed)
                // After failed recovery, no pending recovery remains
                assertFalse(coordinator.hasPendingRecovery(videoId, err403))

                var failureNotified = false
                var destroyCalled = false
                StreamRefreshCoordinator.applyRecoveryOutcome(
                    currentVideoId = videoId,
                    targetVideoId = videoId,
                    outcome = outcome,
                    hasPendingRecovery = coordinator.hasPendingRecovery(videoId, err403),
                    onRecovered = { fail("Expected failure, not recovery") },
                    onFailed = { failureNotified = true },
                    onDestroyService = { destroyCalled = true },
                )
                assertTrue(failureNotified)
                assertTrue(
                    "Expected service teardown on failed recovery even when PlayingQueue is non-empty",
                    destroyCalled,
                )
            } finally {
                PlayingQueue.clear()
            }
        }

    private fun httpCodeException(code: Int): HttpDataSource.InvalidResponseCodeException {
        return HttpDataSource.InvalidResponseCodeException(
            code,
            "HTTP $code",
            null,
            emptyMap(),
            createTestDataSpec("https://rr1---sn-test.googlevideo.com/videoplayback"),
            ByteArray(0),
        )
    }

    private fun wrapInPlaybackException(cause: Throwable): PlaybackException {
        return object :
            PlaybackException(
                "Playback failed",
                cause,
                ERROR_CODE_IO_BAD_HTTP_STATUS,
                Bundle(),
                1234L,
            ) {}
    }

    private fun sampleStreams(
        title: String = "Test Video",
        streamUrl: String = "https://rr1---sn-test.googlevideo.com/videoplayback?gen=1",
    ): Streams {
        return Streams(
            title = title,
            description = "Description",
            uploader = "Channel",
            uploaderUrl = "UC_TEST",
            thumbnailUrl = "https://example.com/thumb.jpg",
            category = "Education",
            uploaderVerified = false,
            duration = 120L,
            audioStreams = listOf(PipedStream(url = streamUrl, itag = 140)),
        )
    }
}
