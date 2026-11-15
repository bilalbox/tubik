package com.github.libretube.api.poToken

import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.player.parser.SabrClient
import com.google.protobuf.ByteString
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PoTokenGeneratorTest {

    private class FakeWebPoTokenSession(
        val id: Int,
        private val expired: () -> Boolean = { false },
        private val onGenerate: suspend (String) -> String = { "pot-$id-$it" },
    ) : WebPoTokenSession {
        val generateCount = AtomicInteger(0)
        val closed = AtomicBoolean(false)

        override suspend fun generatePoToken(identifier: String): String {
            generateCount.incrementAndGet()
            return onGenerate(identifier)
        }

        override fun isExpired(): Boolean = expired()

        override fun close() {
            closed.set(true)
        }
    }

    @Test
    fun `repeated calls to MediaServiceRepository instance reuse the same singleton repository`() {
        val first = MediaServiceRepository.instance
        val second = MediaServiceRepository.instance
        val third = MediaServiceRepository.instance

        assertSame(first, second)
        assertSame(second, third)
    }

    @Test
    fun `unexpired web PoToken and visitorData are reused across repository calls, stream extractions, and SabrClient sessions`() {
        val visitorDataCalls = AtomicInteger(0)
        val sessionCreations = AtomicInteger(0)
        val isExpiredFlag = AtomicBoolean(false)
        val createdSessions = mutableListOf<FakeWebPoTokenSession>()

        PoTokenGenerator.instance.configureForTesting(
            supportsWebView = { true },
            visitorData = { "visitor-${visitorDataCalls.incrementAndGet()}" },
            session = {
                val id = sessionCreations.incrementAndGet()
                FakeWebPoTokenSession(
                        id = id,
                        expired = { isExpiredFlag.get() },
                    )
                    .also { createdSessions.add(it) }
            },
        )

        val firstToken = PoTokenGenerator.instance.getWebClientPoToken("video-1")
        assertNotNull(firstToken)
        assertEquals("visitor-1", firstToken!!.visitorData)
        assertEquals("pot-1-video-1", firstToken.playerRequestPoToken)
        assertEquals("pot-1-video-1", firstToken.streamingDataPoToken)
        assertEquals(1, visitorDataCalls.get())
        assertEquals(1, sessionCreations.get())
        assertEquals(1, createdSessions[0].generateCount.get())

        // Repeated MediaServiceRepository.instance accesses (e.g. search, feed pagination) must not
        // wipe cached PoToken
        MediaServiceRepository.instance.getTrendingCategories()
        MediaServiceRepository.instance.getTrendingCategories()

        // Newly created SabrClient immediately receives the cached web PoToken on startup
        val sabrClient =
            SabrClient(
                videoId = "video-1",
                url = "https://example.com/sabr",
                ustreamerConfig = ByteString.EMPTY,
            )
        assertEquals(
            ByteString.copyFrom("pot-1-video-1".toByteArray()),
            sabrClient.currentPoToken,
        )

        // Subsequent stream extractions while unexpired reuse the cached token immediately
        val secondToken = PoTokenGenerator.instance.getWebClientPoToken("video-2")
        assertSame(firstToken, secondToken)
        assertEquals(1, visitorDataCalls.get())
        assertEquals(1, sessionCreations.get())
        assertEquals(1, createdSessions[0].generateCount.get())

        // Once expired, cached token is no longer returned and next extraction rotates session
        // cleanly
        isExpiredFlag.set(true)
        assertNull(PoTokenGenerator.instance.getCachedWebClientPoToken())

        isExpiredFlag.set(false)
        val refreshedToken = PoTokenGenerator.instance.getWebClientPoToken("video-3")
        assertNotNull(refreshedToken)
        assertEquals("visitor-2", refreshedToken!!.visitorData)
        assertEquals("pot-2-video-3", refreshedToken.streamingDataPoToken)
        assertEquals(2, visitorDataCalls.get())
        assertEquals(2, sessionCreations.get())
        assertTrue("Expired WebView session must be closed", createdSessions[0].closed.get())
    }

    @Test(timeout = 2000L)
    fun `hung WebView token generation times out cleanly and recovers via a single controlled recreation`() {
        val visitorDataCalls = AtomicInteger(0)
        val sessionCreations = AtomicInteger(0)
        val createdSessions = Collections.synchronizedList(mutableListOf<FakeWebPoTokenSession>())

        val generator =
            PoTokenGenerator(
                supportsWebViewProvider = { true },
                visitorDataProvider = { "visitor-${visitorDataCalls.incrementAndGet()}" },
                sessionFactory = {
                    val id = sessionCreations.incrementAndGet()
                    FakeWebPoTokenSession(
                            id = id,
                            onGenerate = { videoId ->
                                if (id == 1) {
                                    // First WebView session hangs forever
                                    CompletableDeferred<String>().await()
                                } else {
                                    "pot-$id-$videoId"
                                }
                            },
                        )
                        .also { createdSessions.add(it) }
                },
                generationTimeoutMs = 50L,
            )

        val result = generator.getWebClientPoToken("video-timeout")
        assertNotNull("Expected recovery from single controlled recreation", result)
        assertEquals("visitor-2", result!!.visitorData)
        assertEquals("pot-2-video-timeout", result.streamingDataPoToken)
        assertEquals(2, sessionCreations.get())
        assertTrue(
            "Hung first WebView session must be closed on timeout",
            createdSessions[0].closed.get(),
        )
        assertSame(result, generator.getCachedWebClientPoToken())
    }

    @Test(timeout = 2000L)
    fun `when both initial generation and controlled recreation time out, returns null cleanly and recovers on subsequent call`() {
        val visitorDataCalls = AtomicInteger(0)
        val sessionCreations = AtomicInteger(0)
        val shouldHang = AtomicBoolean(true)
        val createdSessions = Collections.synchronizedList(mutableListOf<FakeWebPoTokenSession>())

        val generator =
            PoTokenGenerator(
                supportsWebViewProvider = { true },
                visitorDataProvider = { "visitor-${visitorDataCalls.incrementAndGet()}" },
                sessionFactory = {
                    val id = sessionCreations.incrementAndGet()
                    FakeWebPoTokenSession(
                            id = id,
                            onGenerate = { videoId ->
                                if (shouldHang.get()) {
                                    CompletableDeferred<String>().await()
                                } else {
                                    "pot-$id-$videoId"
                                }
                            },
                        )
                        .also { createdSessions.add(it) }
                },
                generationTimeoutMs = 50L,
            )

        val failedResult = generator.getWebClientPoToken("video-hung")
        assertNull(
            "Expected null when both initial attempt and single recreation time out",
            failedResult,
        )
        assertNull(generator.getCachedWebClientPoToken())
        // Initial creation (1) + single controlled recreation (2) = 2 total creations
        assertEquals(2, sessionCreations.get())
        assertTrue(createdSessions[0].closed.get())
        assertTrue(createdSessions[1].closed.get())

        // Subsequent call after WebView becomes healthy again recovers cleanly
        shouldHang.set(false)
        val recoveredResult = generator.getWebClientPoToken("video-recovered")
        assertNotNull(recoveredResult)
        assertEquals("visitor-3", recoveredResult!!.visitorData)
        assertEquals("pot-3-video-recovered", recoveredResult.streamingDataPoToken)
        assertEquals(3, sessionCreations.get())
    }

    @Test(timeout = 10000L)
    fun `concurrent PoToken requests are thread-safe and initialize only a single WebView session`() {
        val visitorDataCalls = AtomicInteger(0)
        val sessionCreations = AtomicInteger(0)
        val generateCalls = AtomicInteger(0)

        val generator =
            PoTokenGenerator(
                supportsWebViewProvider = { true },
                visitorDataProvider = {
                    delay(20L)
                    "visitor-${visitorDataCalls.incrementAndGet()}"
                },
                sessionFactory = {
                    delay(20L)
                    val id = sessionCreations.incrementAndGet()
                    FakeWebPoTokenSession(
                        id = id,
                        onGenerate = { videoId ->
                            delay(20L)
                            generateCalls.incrementAndGet()
                            "pot-$id-$videoId"
                        },
                    )
                },
                generationTimeoutMs = 1000L,
            )

        val threadCount = 8
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val results =
            Collections.synchronizedList(
                mutableListOf<org.schabi.newpipe.extractor.services.youtube.PoTokenResult?>(),
            )
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            for (i in 0 until threadCount) {
                executor.submit {
                    try {
                        startGate.await()
                        results.add(generator.getWebClientPoToken("video-$i"))
                    } finally {
                        doneGate.countDown()
                    }
                }
            }

            startGate.countDown()
            assertTrue(doneGate.await(8, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertEquals(threadCount, results.size)
        val firstNonNull = results.first()
        assertNotNull(firstNonNull)
        results.forEach { token ->
            assertSame(firstNonNull, token)
        }
        assertEquals(1, visitorDataCalls.get())
        assertEquals(1, sessionCreations.get())
        assertEquals(1, generateCalls.get())
    }
}
