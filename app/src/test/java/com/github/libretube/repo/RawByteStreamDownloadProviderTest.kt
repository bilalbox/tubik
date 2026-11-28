package com.github.libretube.repo

import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.player.StreamTransportContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.sink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawByteStreamDownloadProviderTest {

    private fun tempFile(initialBytes: ByteArray = ByteArray(0)): Path {
        val path = Files.createTempFile("raw-download-test-", ".bin")
        path.toFile().deleteOnExit()
        if (initialBytes.isNotEmpty()) {
            Files.write(path, initialBytes)
        }
        return path
    }

    private fun testItem(path: Path, downloadSize: Long = -1L): DownloadItem =
        DownloadItem(
            id = 1,
            type = FileType.SUBTITLE,
            videoId = "vid-1",
            fileName = path.fileName.toString(),
            path = path,
            downloadSize = downloadSize,
        )

    private fun stubClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    @Test
    fun `HTTP 200 without Content-Range returns DownloadComplete on first pass`() = runBlocking {
        val payload = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nHello\n".toByteArray()
        val path = tempFile()
        val item = testItem(path)
        var requestCount = 0

        val client = stubClient { req ->
            requestCount++
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody("text/vtt".toMediaType()))
                .build()
        }

        val provider =
            RawByteStreamDownloadProvider(
                url = "https://example.com/subtitles.vtt".toHttpUrl(),
                httpClient = client,
            )

        val result =
            path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
                provider.downloadNextChunk(item, sink)
            }

        assertEquals(DownloadProgressResult.DownloadComplete, result)
        assertEquals(1, requestCount)
        assertEquals(payload.size.toLong(), item.downloadSize)
        assertArrayEquals(payload, Files.readAllBytes(path))
    }

    @Test
    fun `HTTP 200 chunked response with unknown content length returns DownloadComplete without looping`() =
        runBlocking {
            val payload = "chunked-subtitle-body".toByteArray()
            val path = tempFile()
            val item = testItem(path)
            var requestCount = 0

            val unknownLengthBody =
                object : ResponseBody() {
                    override fun contentType(): MediaType = "text/vtt".toMediaType()

                    override fun contentLength(): Long = -1L

                    override fun source(): BufferedSource = Buffer().write(payload)
                }

            val client = stubClient { req ->
                requestCount++
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(unknownLengthBody)
                    .build()
            }

            val provider =
                RawByteStreamDownloadProvider(
                    url = "https://example.com/chunked.vtt".toHttpUrl(),
                    httpClient = client,
                )

            val result =
                path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
                    provider.downloadNextChunk(item, sink)
                }

            assertEquals(DownloadProgressResult.DownloadComplete, result)
            assertEquals(1, requestCount)
            assertTrue(item.downloadSize <= 0L)
            assertArrayEquals(payload, Files.readAllBytes(path))
        }

    @Test
    fun `HTTP 200 with existing partial bytes truncates file cleanly before writing`() =
        runBlocking {
            val staleBytes = "stale-partial-".toByteArray()
            val fullBody = "fresh-full-body".toByteArray()
            val path = tempFile(staleBytes)
            val item = testItem(path)

            val client = stubClient { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fullBody.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }

            val provider =
                RawByteStreamDownloadProvider(
                    url = "https://example.com/file.bin".toHttpUrl(),
                    httpClient = client,
                )

            val result =
                path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
                    provider.downloadNextChunk(item, sink)
                }

            assertEquals(DownloadProgressResult.DownloadComplete, result)
            assertArrayEquals(fullBody, Files.readAllBytes(path))
        }

    @Test
    fun `HTTP 206 returns Progressed until total downloadSize is reached`() = runBlocking {
        val fullData = ByteArray(10) { (it + 1).toByte() }
        val path = tempFile()
        val item = testItem(path)

        val client = stubClient { req ->
            val range = req.header("Range") ?: ""
            val chunk =
                if (range.startsWith("bytes=0-")) {
                    fullData.copyOfRange(0, 6)
                } else {
                    fullData.copyOfRange(6, 10)
                }
            val start = if (range.startsWith("bytes=0-")) 0 else 6
            val end = start + chunk.size - 1
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes $start-$end/${fullData.size}")
                .body(chunk.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        val provider =
            RawByteStreamDownloadProvider(
                url = "https://example.com/video.mp4".toHttpUrl(),
                httpClient = client,
            )

        path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
            val firstResult = provider.downloadNextChunk(item, sink)
            assertTrue(firstResult is DownloadProgressResult.Progressed)
            assertEquals(6L, (firstResult as DownloadProgressResult.Progressed).bytes)
            assertEquals(10L, item.downloadSize)

            val secondResult = provider.downloadNextChunk(item, sink)
            assertEquals(DownloadProgressResult.DownloadComplete, secondResult)
        }

        assertArrayEquals(fullData, Files.readAllBytes(path))
    }

    @Test
    fun `StreamTransportContext User-Agent and headers are sent on outgoing request`() =
        runBlocking {
            val path = tempFile()
            val item = testItem(path)
            var capturedUserAgent: String? = null
            var capturedHeader: String? = null

            val client = stubClient { req ->
                capturedUserAgent = req.header("User-Agent")
                capturedHeader = req.header("X-Test-Client")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toByteArray().toResponseBody("text/plain".toMediaType()))
                    .build()
            }

            val transportContext =
                StreamTransportContext(
                    videoId = "vid-ios",
                    userAgent = StreamTransportContext.DEFAULT_USER_AGENT,
                    headers = mapOf("X-Test-Client" to "tubik"),
                    httpClient = client,
                )

            val provider =
                RawByteStreamDownloadProvider(
                    url = "https://example.com/videoplayback?c=IOS".toHttpUrl(),
                    transportContext = transportContext,
                )

            val result =
                path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
                    provider.downloadNextChunk(item, sink)
                }

            assertEquals(DownloadProgressResult.DownloadComplete, result)
            assertEquals(StreamTransportContext.IOS_USER_AGENT, capturedUserAgent)
            assertEquals("tubik", capturedHeader)
        }
}
