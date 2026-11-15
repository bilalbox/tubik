package com.github.libretube.player.parser

import androidx.media3.common.Format
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.player.StreamTransportContext
import com.github.libretube.player.manifest.Representation
import com.github.libretube.util.NewPipeDownloaderImpl
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import misc.Common.FormatId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import video_streaming.FormatInitializationMetadataOuterClass.FormatInitializationMetadata
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.SabrContextSendingPolicyOuterClass.SabrContextSendingPolicy
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate
import video_streaming.StreamerContextOuterClass.StreamerContext
import video_streaming.UmpPartId.UMPPartId
import video_streaming.VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest

class SabrClientTest {

    @Test
    fun `uninitialized and deselected MEDIA_HEADER parts are safely ignored without NullPointerException`() {
        val rep140 = audioRep(140)
        val rep251 = audioRep(251)

        var requestIndex = 0
        val client =
            NewPipeDownloaderImpl.httpClient
                .newBuilder()
                .addInterceptor { chain ->
                    val body =
                        when (requestIndex++) {
                            0 ->
                                concat(
                                    // Uninitialized format 999 arrives before any metadata for 999
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-1",
                                            headerId = 1,
                                            itag = 999,
                                            seq = 0,
                                            isInit = true,
                                            length = 3,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 1, "bad".toByteArray()),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(1)),
                                    // Valid initialized format 140
                                    umpPart(
                                        UMPPartId.FORMAT_INITIALIZATION_METADATA,
                                        formatInitMeta("vid-1", itag = 140),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-1",
                                            headerId = 2,
                                            itag = 140,
                                            seq = 0,
                                            isInit = true,
                                            length = 5,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 2, "hello".toByteArray()),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(2)),
                                )
                            1 ->
                                concat(
                                    // Newly selected format 251 arrives across two MEDIA chunks;
                                    // 140 is evicted after this response
                                    umpPart(
                                        UMPPartId.FORMAT_INITIALIZATION_METADATA,
                                        formatInitMeta("vid-1", itag = 251),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-1",
                                            headerId = 4,
                                            itag = 251,
                                            seq = 0,
                                            isInit = true,
                                            length = 5,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 4, "ab".toByteArray()),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 4, "cde".toByteArray()),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(4)),
                                )
                            else ->
                                concat(
                                    // Format 140 was deselected and evicted; stale MEDIA_HEADER for
                                    // 140 arrives
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-1",
                                            headerId = 3,
                                            itag = 140,
                                            seq = 1,
                                            isInit = false,
                                            length = 5,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 3, "stale".toByteArray()),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(3)),
                                    // Segment 1 for format 251
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-1",
                                            headerId = 5,
                                            itag = 251,
                                            seq = 1,
                                            isInit = false,
                                            length = 3,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 5, "xyz".toByteArray()),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(5)),
                                )
                        }

                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/vnd.yt-ump".toMediaType()))
                        .build()
                }
                .build()

        val sabrClient =
            SabrClient(
                videoId = "vid-1",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB",
                ustreamerConfig = ByteString.EMPTY,
                transportContext = StreamTransportContext(videoId = "vid-1", httpClient = client),
            )

        sabrClient.selectFormat(rep140)
        val seg140 =
            sabrClient.getNextSegment(PlaybackRequest.initRequest(rep140.formatId(), 0L, 1.0f))
        assertNotNull(seg140)
        assertArrayEquals("hello".toByteArray(), readAll(seg140!!))

        // Deselect 140 by switching to 251; first request triggers eviction of 140 after media()
        sabrClient.selectFormat(rep251)
        val seg251 =
            sabrClient.getNextSegment(PlaybackRequest.initRequest(rep251.formatId(), 0L, 1.0f))
        assertNotNull(seg251)
        assertEquals(2, seg251!!.data.size)
        // Both MEDIA chunks slice the same UMP response ByteArray directly without copying
        assertSame(seg251.data[0].array, seg251.data[1].array)
        assertArrayEquals("abcde".toByteArray(), readAll(seg251))

        // Next request receives a stale MEDIA_HEADER for deselected 140 alongside segment 1 for 251
        val seg251Part1 =
            sabrClient.getNextSegment(
                PlaybackRequest(
                    format = rep251.formatId(),
                    playerPosition = 5_000L,
                    playbackSpeed = 1.0f,
                    segment = 1L,
                    segmentStartTimeMs = 5_000L,
                    bufferedSegments = listOf(0L),
                ),
            )
        assertNotNull(seg251Part1)
        assertArrayEquals("xyz".toByteArray(), readAll(seg251Part1!!))
    }

    @Test
    fun `SABR_CONTEXT_SENDING_POLICY discard removes both active and inactive contexts from stored map and active set`() {
        val rep = audioRep(251)
        val capturedContexts = mutableListOf<StreamerContext>()
        var requestIndex = 0

        val client =
            NewPipeDownloaderImpl.httpClient
                .newBuilder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val sink = Buffer()
                    req.body!!.writeTo(sink)
                    val abrRequest = VideoPlaybackAbrRequest.parseFrom(sink.readByteArray())
                    capturedContexts.add(abrRequest.streamerContext)

                    val body =
                        when (requestIndex++) {
                            0 ->
                                concat(
                                    umpPart(
                                        UMPPartId.FORMAT_INITIALIZATION_METADATA,
                                        formatInitMeta("vid-ctx", itag = 251),
                                    ),
                                    umpPart(
                                        UMPPartId.SABR_CONTEXT_UPDATE,
                                        contextUpdate(
                                            type = 1,
                                            value = "ctx1",
                                            sendByDefault = true,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.SABR_CONTEXT_UPDATE,
                                        contextUpdate(
                                            type = 2,
                                            value = "ctx2",
                                            sendByDefault = false,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-ctx",
                                            headerId = 1,
                                            itag = 251,
                                            seq = 0,
                                            isInit = true,
                                            length = 1,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 1, byteArrayOf(1)),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(1)),
                                )
                            1 ->
                                concat(
                                    umpPart(
                                        UMPPartId.SABR_CONTEXT_SENDING_POLICY,
                                        discardPolicy(1, 2),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-ctx",
                                            headerId = 2,
                                            itag = 251,
                                            seq = 1,
                                            isInit = false,
                                            length = 1,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 2, byteArrayOf(2)),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(2)),
                                )
                            2 ->
                                concat(
                                    umpPart(
                                        UMPPartId.SABR_CONTEXT_UPDATE,
                                        contextUpdate(
                                            type = 1,
                                            value = "ctx1-new",
                                            sendByDefault = false,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-ctx",
                                            headerId = 3,
                                            itag = 251,
                                            seq = 2,
                                            isInit = false,
                                            length = 1,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 3, byteArrayOf(3)),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(3)),
                                )
                            else ->
                                concat(
                                    umpPart(
                                        UMPPartId.MEDIA_HEADER,
                                        mediaHeader(
                                            "vid-ctx",
                                            headerId = 4,
                                            itag = 251,
                                            seq = 3,
                                            isInit = false,
                                            length = 1,
                                        ),
                                    ),
                                    umpPart(
                                        UMPPartId.MEDIA,
                                        mediaPayload(headerId = 4, byteArrayOf(4)),
                                    ),
                                    umpPart(UMPPartId.MEDIA_END, encodeVarint(4)),
                                )
                        }

                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/vnd.yt-ump".toMediaType()))
                        .build()
                }
                .build()

        val sabrClient =
            SabrClient(
                videoId = "vid-ctx",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?c=WEB",
                ustreamerConfig = ByteString.EMPTY,
                transportContext = StreamTransportContext(videoId = "vid-ctx", httpClient = client),
            )
        sabrClient.selectFormat(rep)

        sabrClient.getNextSegment(PlaybackRequest.initRequest(rep.formatId(), 0L, 1.0f))
        sabrClient.getNextSegment(segmentRequest(rep, seq = 1L))
        assertEquals(listOf(1), capturedContexts[1].sabrContextsList.map { it.type })
        assertEquals(listOf(2), capturedContexts[1].unsentSabrContextsList)

        // After discardPolicy=[1, 2], neither active nor unsent contexts may remain
        sabrClient.getNextSegment(segmentRequest(rep, seq = 2L))
        assertTrue(capturedContexts[2].sabrContextsList.isEmpty())
        assertTrue(capturedContexts[2].unsentSabrContextsList.isEmpty())

        // Re-registering type=1 with sendByDefault=false must keep it inactive (not lingering in
        // activeSabrContexts)
        sabrClient.getNextSegment(segmentRequest(rep, seq = 3L))
        assertTrue(capturedContexts[3].sabrContextsList.isEmpty())
        assertEquals(listOf(1), capturedContexts[3].unsentSabrContextsList)
    }

    private fun segmentRequest(rep: Representation, seq: Long) =
        PlaybackRequest(
            format = rep.formatId(),
            playerPosition = seq * 5_000L,
            playbackSpeed = 1.0f,
            segment = seq,
            segmentStartTimeMs = seq * 5_000L,
            bufferedSegments = emptyList(),
        )

    private fun contextUpdate(type: Int, value: String, sendByDefault: Boolean): ByteArray =
        SabrContextUpdate.newBuilder()
            .setType(type)
            .setValue(ByteString.copyFromUtf8(value))
            .setSendByDefault(sendByDefault)
            .build()
            .toByteArray()

    private fun discardPolicy(vararg types: Int): ByteArray =
        SabrContextSendingPolicy.newBuilder()
            .addAllDiscardPolicy(types.toList())
            .build()
            .toByteArray()

    private fun audioRep(itag: Int) =
        Representation(
            Format.Builder().setContainerMimeType("audio/mp4").build(),
            PipedStream(
                itag = itag,
                mimeType = "audio/mp4",
                codec = "mp4a.40.2",
                lastModified = 1L,
                durationMs = 60_000L,
            ),
        )

    private fun readAll(segment: Segment): ByteArray {
        val buf = CompositeBuffer.fromSlices(segment.data)
        val out = ByteArray(buf.remaining())
        buf.read(out, 0, out.size)
        return out
    }

    private fun formatInitMeta(videoId: String, itag: Int): ByteArray =
        FormatInitializationMetadata.newBuilder()
            .setVideoId(videoId)
            .setFormatId(FormatId.newBuilder().setItag(itag).setLastModified(1L).build())
            .setEndTimeMs(60_000L)
            .setEndSegmentNumber(10L)
            .build()
            .toByteArray()

    private fun mediaHeader(
        videoId: String,
        headerId: Int,
        itag: Int,
        seq: Long,
        isInit: Boolean,
        length: Long,
    ): ByteArray =
        MediaHeader.newBuilder()
            .setVideoId(videoId)
            .setHeaderId(headerId)
            .setItag(itag)
            .setFormatId(FormatId.newBuilder().setItag(itag).setLastModified(1L).build())
            .setSequenceNumber(seq)
            .setIsInitSeg(isInit)
            .setDurationMs(5_000L)
            .setContentLength(length)
            .build()
            .toByteArray()

    private fun mediaPayload(headerId: Int, data: ByteArray): ByteArray =
        concat(encodeVarint(headerId), data)

    private fun umpPart(type: UMPPartId, payload: ByteArray): ByteArray =
        concat(encodeVarint(type.number), encodeVarint(payload.size), payload)

    private fun encodeVarint(value: Int): ByteArray {
        require(value in 0..127) { "Test helper supports 1-byte varints (0..127), got $value" }
        return byteArrayOf(value.toByte())
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }
}
