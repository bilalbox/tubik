package com.github.libretube.util

import com.github.libretube.player.StreamTransportContext
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

class NewPipeDownloaderImplTest {
    private val downloader = NewPipeDownloaderImpl()

    @Test
    fun `shouldRetryPlayerWithFallbackClient triggers on unplayable status or missing usable streamingData`() {
        val url =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false& \$fields=playabilityStatus,streamingData"
        val body = """{"videoId":"020g-0hhCAU"}""".toByteArray()
        val unplayableResponse =
            """
            {"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video is not available"}}
            """
                .trimIndent()
        val errorStatusResponse =
            """
            {"playabilityStatus":{"status":"ERROR","reason":"Generic error"}}
            """
                .trimIndent()
        val ageCheckResponse =
            """
            {"playabilityStatus":{"status":"AGE_CHECK_REQUIRED"}}
            """
                .trimIndent()
        val okWithoutStreamingDataResponse =
            """
            {"playabilityStatus":{"status":"OK"}}
            """
                .trimIndent()
        val okWithSabrOnlyFormatsResponse =
            """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "serverAbrStreamingUrl":"https://rr1---sn-test.googlevideo.com/videoplayback?sabr=1",
                "adaptiveFormats":[{"itag":251,"mimeType":"audio/webm"}]
              }
            }
            """
                .trimIndent()
        val okWithPlayableAdaptiveFormatResponse =
            """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[{"itag":251,"url":"https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB&itag=251"}]
              }
            }
            """
                .trimIndent()
        val okWithHlsManifestResponse =
            """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/020g-0hhCAU"
              }
            }
            """
                .trimIndent()

        assertTrue(downloader.shouldRetryPlayerWithFallbackClient(url, body, unplayableResponse))
        assertTrue(downloader.shouldRetryPlayerWithFallbackClient(url, body, errorStatusResponse))
        assertTrue(downloader.shouldRetryPlayerWithFallbackClient(url, body, ageCheckResponse))
        assertTrue(
            downloader.shouldRetryPlayerWithFallbackClient(
                url,
                body,
                okWithoutStreamingDataResponse,
            ),
        )
        assertTrue(
            downloader.shouldRetryPlayerWithFallbackClient(
                url,
                body,
                okWithSabrOnlyFormatsResponse,
            ),
        )

        assertFalse(
            downloader.shouldRetryPlayerWithFallbackClient(
                url,
                body,
                okWithPlayableAdaptiveFormatResponse,
            ),
        )
        assertFalse(
            downloader.shouldRetryPlayerWithFallbackClient(url, body, okWithHlsManifestResponse),
        )
        assertFalse(
            downloader.shouldRetryPlayerWithFallbackClient(
                "https://www.youtube.com/youtubei/v1/next",
                body,
                unplayableResponse,
            ),
        )
    }

    @Test
    fun `execute tries ANDROID_KIDS first when primary lacks usable streamingData and falls back to IOS when needed`() {
        val seenRequests = mutableListOf<Pair<String, String?>>()
        var kidsUsable = true
        val fakeClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val reqUrl = req.url.toString()
                    val ua = req.header("User-Agent")
                    seenRequests.add(reqUrl to ua)

                    val responseJson =
                        when {
                            reqUrl.contains("/youtubei/v1/visitor_id") -> {
                                """{"responseContext":{"visitorData":"ios_visitor_123"}}"""
                            }
                            ua == NewPipeDownloaderImpl.ANDROID_KIDS_USER_AGENT -> {
                                if (kidsUsable) {
                                    """
                                    {
                                      "playabilityStatus":{"status":"OK"},
                                      "streamingData":{
                                        "adaptiveFormats":[
                                          {"itag":140,"url":"https://rr1---sn-test.googlevideo.com/videoplayback?id=vid_kids_1"}
                                        ]
                                      }
                                    }
                                    """
                                        .trimIndent()
                                } else {
                                    """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":140}]}}"""
                                }
                            }
                            ua == NewPipeDownloaderImpl.IOS_USER_AGENT -> {
                                """
                                {
                                  "playabilityStatus":{"status":"OK"},
                                  "streamingData":{
                                    "adaptiveFormats":[
                                      {"itag":140,"url":"https://rr1---sn-test.googlevideo.com/videoplayback?id=vid_ios_2"}
                                    ]
                                  }
                                }
                                """
                                    .trimIndent()
                            }
                            else -> {
                                // Primary request returns OK without streamingData
                                """{"playabilityStatus":{"status":"OK"}}"""
                            }
                        }

                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(responseJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

        val testDownloader = NewPipeDownloaderImpl(fakeClient)
        val requestPayload =
            """
            {
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20260101.00.00",
                  "hl": "en",
                  "gl": "US"
                }
              },
              "videoId": "vid_kids_1"
            }
            """
                .trimIndent()
                .toByteArray(StandardCharsets.UTF_8)

        val response =
            testDownloader.execute(
                Request.Builder()
                    .post(
                        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                        requestPayload,
                    )
                    .build(),
            )

        assertTrue(response.responseBody().contains("vid_kids_1"))
        assertFalse(seenRequests.any { it.second == NewPipeDownloaderImpl.IOS_USER_AGENT })
        assertEquals(
            NewPipeDownloaderImpl.ANDROID_KIDS_USER_AGENT,
            testDownloader.recordedUserAgent("vid_kids_1"),
        )
        assertEquals(
            NewPipeDownloaderImpl.ANDROID_KIDS_USER_AGENT,
            StreamTransportContext(
                    videoId = "vid_kids_1",
                    userAgent = testDownloader.recordedUserAgent("vid_kids_1"),
                )
                .resolveUserAgent(
                    "https://rr1---sn-test.googlevideo.com/videoplayback?id=vid_kids_1",
                ),
        )

        // When ANDROID_KIDS lacks usable streams, fallback should proceed to IOS
        seenRequests.clear()
        kidsUsable = false
        val iosFallbackPayload =
            """
            {
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20260101.00.00",
                  "hl": "en",
                  "gl": "US"
                }
              },
              "videoId": "vid_ios_2"
            }
            """
                .trimIndent()
                .toByteArray(StandardCharsets.UTF_8)

        val iosResponse =
            testDownloader.execute(
                Request.Builder()
                    .post(
                        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                        iosFallbackPayload,
                    )
                    .build(),
            )
        assertTrue(iosResponse.responseBody().contains("vid_ios_2"))
        assertEquals(
            listOf(
                NewPipeDownloaderImpl.USER_AGENT,
                NewPipeDownloaderImpl.ANDROID_KIDS_USER_AGENT,
                NewPipeDownloaderImpl.IOS_USER_AGENT,
                NewPipeDownloaderImpl.IOS_USER_AGENT,
            ),
            seenRequests.map { it.second },
        )
        assertEquals(
            NewPipeDownloaderImpl.IOS_USER_AGENT,
            testDownloader.recordedUserAgent("vid_ios_2"),
        )
        assertEquals(
            NewPipeDownloaderImpl.IOS_USER_AGENT,
            StreamTransportContext()
                .resolveUserAgent(
                    "https://rr1---sn-test.googlevideo.com/videoplayback?c=IOS&itag=140",
                ),
        )
        assertEquals(
            NewPipeDownloaderImpl.USER_AGENT,
            StreamTransportContext()
                .resolveUserAgent(
                    "https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB&itag=251",
                ),
        )
        assertSame(
            NewPipeDownloaderImpl.sharedConnectionPool,
            NewPipeDownloaderImpl.httpClient.connectionPool,
        )
    }

    @Test
    fun `downloader instances isolate recorded User-Agents and never bleed across unrelated videos`() {
        val clientReturningIos =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val body =
                        if (req.header("User-Agent") == NewPipeDownloaderImpl.IOS_USER_AGENT) {
                            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":140,"url":"https://rr1---sn-test.googlevideo.com/videoplayback?id=vid_a"}]}}"""
                        } else {
                            """{"playabilityStatus":{"status":"OK"}}"""
                        }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

        val downloaderA = NewPipeDownloaderImpl(clientReturningIos)
        val downloaderB = NewPipeDownloaderImpl(clientReturningIos)

        val payloadA =
            """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20260101.00.00","hl":"en","gl":"US"}},"videoId":"vid_a"}"""
                .toByteArray(StandardCharsets.UTF_8)

        downloaderA.execute(
            Request.Builder()
                .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", payloadA)
                .build(),
        )

        assertEquals(NewPipeDownloaderImpl.IOS_USER_AGENT, downloaderA.recordedUserAgent("vid_a"))
        assertNull(downloaderB.recordedUserAgent("vid_a"))
        // Blank user agents must not overwrite or mask fallback resolution, and oldest entries
        // evict
        downloaderA.recordUserAgent("vid_blank", "   ")
        assertNull(downloaderA.recordedUserAgent("vid_blank"))
        val boundedDownloader = NewPipeDownloaderImpl(clientReturningIos, maxUserAgentEntries = 2)
        boundedDownloader.recordUserAgent("v1", "ua1")
        boundedDownloader.recordUserAgent("v2", "ua2")
        boundedDownloader.recordUserAgent("v3", "ua3")
        assertNull(boundedDownloader.recordedUserAgent("v1"))
        assertEquals("ua2", boundedDownloader.recordedUserAgent("v2"))
        assertEquals("ua3", boundedDownloader.recordedUserAgent("v3"))
        // Unrelated video must fall back to default USER_AGENT rather than bleeding
        assertEquals(
            NewPipeDownloaderImpl.USER_AGENT,
            StreamTransportContext(
                    videoId = "vid_unrelated",
                    userAgent = downloaderA.recordedUserAgent("vid_unrelated"),
                )
                .resolveUserAgent("https://rr1---sn-test.googlevideo.com/videoplayback?itag=140"),
        )
    }

    @Test
    fun `buildIosClientPayload preserves cpn and videoId while switching client to IOS`() {
        val original =
            Json.parseToJsonElement(
                    """
                    {
                      "context": {
                        "client": {
                          "clientName": "VISIONOS",
                          "clientVersion": "0.1",
                          "hl": "en-GB",
                          "gl": "GB"
                        }
                      },
                      "videoId": "020g-0hhCAU",
                      "cpn": "test_cpn_1234567"
                    }
                    """
                        .trimIndent(),
                )
                .jsonObject

        val rewritten = downloader.buildIosClientPayload(original, "visitor_abc")
        assertNotNull(rewritten)
        assertEquals("020g-0hhCAU", rewritten!!["videoId"]?.jsonPrimitive?.content)
        assertEquals("test_cpn_1234567", rewritten["cpn"]?.jsonPrimitive?.content)

        val client = rewritten["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals(
            NewPipeDownloaderImpl.IOS_CLIENT_NAME,
            client["clientName"]?.jsonPrimitive?.content,
        )
        assertEquals(
            NewPipeDownloaderImpl.IOS_CLIENT_VERSION,
            client["clientVersion"]?.jsonPrimitive?.content,
        )
        assertEquals("visitor_abc", client["visitorData"]?.jsonPrimitive?.content)
        assertEquals("en-GB", client["hl"]?.jsonPrimitive?.content)
        assertEquals("GB", client["gl"]?.jsonPrimitive?.content)
    }
}
