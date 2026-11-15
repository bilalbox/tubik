package com.github.libretube.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import com.github.libretube.player.parser.SabrStreamException
import com.github.libretube.util.NewPipeDownloaderImpl
import com.google.protobuf.ByteString
import misc.Common.FormatId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import video_streaming.UmpPartId.UMPPartId

class UnifiedMediaTransportTest {

    @Test
    fun `downloader, OkHttpMediaDataSource, and SabrClient share a unified OkHttp connection pool`() {
        val dataSourceFactory = OkHttpMediaDataSource.Factory()
        val sabrClient =
            SabrClient(
                videoId = "video-pool-test",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB",
                ustreamerConfig = ByteString.EMPTY,
            )

        assertSame(
            NewPipeDownloaderImpl.sharedConnectionPool,
            NewPipeDownloaderImpl.httpClient.connectionPool,
        )
        assertSame(
            NewPipeDownloaderImpl.sharedConnectionPool,
            dataSourceFactory.connectionPool,
        )
        assertSame(
            NewPipeDownloaderImpl.sharedConnectionPool,
            sabrClient.connectionPool,
        )
    }

    @Test
    fun `OkHttpMediaDataSource sends client-matched User-Agent and Range header for DASH and HLS media segments`() {
        val capturedHeaders = mutableListOf<Triple<String?, String?, String?>>()
        val payloadBytes = ByteArray(100) { idx -> (idx + 1).toByte() }

        val testClient =
            NewPipeDownloaderImpl.httpClient
                .newBuilder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    capturedHeaders.add(
                        Triple(
                            req.header("User-Agent"),
                            req.header("Range"),
                            req.header("X-Transport-Header"),
                        ),
                    )
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_2)
                        .code(206)
                        .message("Partial Content")
                        .body(payloadBytes.toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
                .build()

        val transportContext =
            StreamTransportContext(
                videoId = "vid-ios",
                userAgent = NewPipeDownloaderImpl.IOS_USER_AGENT,
                headers = mapOf("X-Transport-Header" to "ctx-token"),
                httpClient = testClient,
            )
        val factory =
            OkHttpMediaDataSource.Factory(
                transportContext = transportContext,
                dataSpecUriToString = { it.key!! },
            )

        // 1. Segment URL without c= param uses context User-Agent (IOS) and custom headers
        val dataSource1 = factory.createDataSource()
        val length1 =
            dataSource1.open(
                createTestDataSpec(
                    url = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=140",
                    position = 100L,
                    length = 100L,
                ),
            )
        assertEquals(100L, length1)
        val readBuf = ByteArray(100)
        var totalRead = 0
        while (totalRead < 100) {
            val r = dataSource1.read(readBuf, totalRead, 100 - totalRead)
            if (r == C.RESULT_END_OF_INPUT) break
            totalRead += r
        }
        dataSource1.close()
        assertEquals(100, totalRead)
        assertArrayEquals(payloadBytes, readBuf)
        assertEquals(NewPipeDownloaderImpl.IOS_USER_AGENT, capturedHeaders[0].first)
        assertEquals("bytes=100-199", capturedHeaders[0].second)
        assertEquals("ctx-token", capturedHeaders[0].third)

        // 2. Segment URL with c=ANDROID_KIDS uses ANDROID_KIDS_USER_AGENT
        val dataSource2 = factory.createDataSource()
        dataSource2.open(
            createTestDataSpec(
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=ANDROID_KIDS&itag=18",
            ),
        )
        dataSource2.close()
        assertEquals(NewPipeDownloaderImpl.ANDROID_KIDS_USER_AGENT, capturedHeaders[1].first)
        assertEquals(null, capturedHeaders[1].second)

        // 3. Segment URL with c=WEB uses WEB USER_AGENT
        val dataSource3 = factory.createDataSource()
        dataSource3.open(
            createTestDataSpec(
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB&itag=251",
                position = 500L,
            ),
        )
        dataSource3.close()
        assertEquals(NewPipeDownloaderImpl.USER_AGENT, capturedHeaders[2].first)
        assertEquals("bytes=500-", capturedHeaders[2].second)
    }

    @Test
    fun `OkHttpMediaDataSource throws InvalidResponseCodeException on HTTP 403 and 410`() {
        var statusCodeToReturn = 403
        val testClient =
            NewPipeDownloaderImpl.httpClient
                .newBuilder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(statusCodeToReturn)
                        .message("Forbidden/Gone")
                        .body("error".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()

        val dataSource =
            OkHttpMediaDataSource.Factory(
                    transportContext = StreamTransportContext(httpClient = testClient),
                    dataSpecUriToString = { it.key!! },
                )
                .createDataSource()
        val spec =
            createTestDataSpec(
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=IOS&itag=140",
            )
        try {
            dataSource.open(spec)
            fail("Expected InvalidResponseCodeException for 403")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }

        statusCodeToReturn = 410
        try {
            dataSource.open(spec)
            fail("Expected InvalidResponseCodeException for 410")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(410, e.responseCode)
        }
    }

    @Test
    fun `SabrClient sends client-matched User-Agent, headers, and PoToken from StreamTransportContext`() {
        val capturedHeaders = mutableListOf<Pair<String?, String?>>()
        val testClient =
            NewPipeDownloaderImpl.httpClient
                .newBuilder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    capturedHeaders.add(req.header("User-Agent") to req.header("X-Sabr-Header"))
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(ByteArray(0).toResponseBody("application/vnd.yt-ump".toMediaType()))
                        .build()
                }
                .build()

        val customPoTokenProvider =
            object : PoTokenProvider {
                override fun getWebClientPoToken(videoId: String): PoTokenResult =
                    PoTokenResult("vis-$videoId", "req-$videoId", "stream-$videoId")
            }

        val transportContext =
            StreamTransportContext(
                videoId = "vid-sabr-ios",
                userAgent = NewPipeDownloaderImpl.IOS_USER_AGENT,
                headers = mapOf("X-Sabr-Header" to "sabr-ctx"),
                httpClient = testClient,
                poTokenProvider = customPoTokenProvider,
            )

        val sabrClient =
            SabrClient(
                videoId = "vid-sabr-ios",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?source=youtube",
                ustreamerConfig = ByteString.EMPTY,
                transportContext = transportContext,
            )

        val formatId = FormatId.newBuilder().setItag(251).build()
        sabrClient.getNextSegment(PlaybackRequest.initRequest(formatId, 0L, 1.0f))

        assertTrue(capturedHeaders.isNotEmpty())
        assertEquals(NewPipeDownloaderImpl.IOS_USER_AGENT, capturedHeaders.first().first)
        assertEquals("sabr-ctx", capturedHeaders.first().second)
        assertEquals(
            ByteString.copyFrom("stream-vid-sabr-ios".toByteArray()),
            sabrClient.generatePoToken(),
        )
    }

    @Test
    fun `SabrClient throws reload-required SabrStreamException on HTTP 403 and RELOAD_PLAYER_RESPONSE`() {
        val client403 =
            NewPipeDownloaderImpl.httpClient
                .newBuilder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(403)
                        .message("Forbidden")
                        .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()

        val sabr403 =
            SabrClient(
                videoId = "vid-sabr-403",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB",
                ustreamerConfig = ByteString.EMPTY,
                transportContext =
                    StreamTransportContext(videoId = "vid-sabr-403", httpClient = client403),
            )
        val formatId = FormatId.newBuilder().setItag(251).build()
        try {
            sabr403.getNextSegment(PlaybackRequest.initRequest(formatId, 0L, 1.0f))
            fail("Expected SabrStreamException on HTTP 403")
        } catch (e: SabrStreamException) {
            assertEquals(403, e.httpStatusCode)
            assertTrue(e.isReloadRequired)
        }

        // UMP RELOAD_PLAYER_RESPONSE part: varint(partId) + varint(size=0)
        val reloadUmpBytes = byteArrayOf(UMPPartId.RELOAD_PLAYER_RESPONSE_VALUE.toByte(), 0)
        val clientReload =
            NewPipeDownloaderImpl.httpClient
                .newBuilder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(reloadUmpBytes.toResponseBody("application/vnd.yt-ump".toMediaType()))
                        .build()
                }
                .build()

        val sabrReload =
            SabrClient(
                videoId = "vid-sabr-reload",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB",
                ustreamerConfig = ByteString.EMPTY,
                transportContext =
                    StreamTransportContext(videoId = "vid-sabr-reload", httpClient = clientReload),
            )
        try {
            sabrReload.getNextSegment(PlaybackRequest.initRequest(formatId, 0L, 1.0f))
            fail("Expected SabrStreamException on RELOAD_PLAYER_RESPONSE")
        } catch (e: SabrStreamException) {
            assertTrue(e.isReloadRequired)
        }
    }
}
