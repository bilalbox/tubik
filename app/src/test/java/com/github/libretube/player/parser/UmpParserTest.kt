package com.github.libretube.player.parser

import org.junit.Assert.*
import org.junit.Test
import video_streaming.UmpPartId

class ParserTest {
    @Test
    fun testReadVarint() {
        val testCases =
            listOf(
                // 1 byte long varint
                Pair(byteArrayOf(0x01), 1u),
                Pair(byteArrayOf(0x4F), 79u),
                // 2 byte long varint
                Pair(byteArrayOf(0x96.toByte(), 0), 22u),
                Pair(byteArrayOf(0x80.toByte(), 0x01), 64u),
                Pair(byteArrayOf(0x8A.toByte(), 0x7F), 8138u),
                Pair(byteArrayOf(0xBF.toByte(), 0x7F), 8191u),
                // 3 byte long varint
                Pair(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0x01), 12288u),
                Pair(byteArrayOf(0xDF.toByte(), 0x7F, 0xFF.toByte()), 2093055u),
                // 4 byte long varint
                Pair(byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01), 1574912u),
                Pair(byteArrayOf(0xEF.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte()), 268433407u),
                // 5 byte long varint
                Pair(
                    byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01),
                    25198720u,
                ),
                Pair(
                    byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                    4294967167u,
                ),
            )

        for ((data, expected) in testCases) {
            val parser = UmpParser(data)
            val result = parser.readVarint()
            assertEquals(
                "Failed for input: ${data.joinToString { it.toString() }}",
                expected,
                result,
            )
        }
    }

    @Test
    fun testReadPart() {
        val parser = UmpParser(byteArrayOf(20, 1, 42))
        val part = parser.readPart()

        assertNotNull(part)
        assertEquals(UmpPartId.UMPPartId.MEDIA_HEADER, part?.type)
        assertArrayEquals(byteArrayOf(42), part?.data)
        assertTrue(parser.data().isEmpty())
    }

    @Test
    fun testReadPartZeroCopySlicesOriginalBuffer() {
        // UMP part: type = MEDIA (21), size = 5, payload = [headerId=7, 0x11, 0x22, 0x33, 0x44]
        val backing =
            byteArrayOf(
                0x7F, // sentinel byte before startOffset
                UmpPartId.UMPPartId.MEDIA_VALUE.toByte(),
                5,
                7,
                0x11,
                0x22,
                0x33,
                0x44,
                0x7E, // sentinel byte after endExclusive
            )

        val parser = UmpParser(backing, startOffset = 1, endExclusive = backing.size - 1)
        val part = parser.readPart()

        assertNotNull(part)
        assertEquals(UmpPartId.UMPPartId.MEDIA, part!!.type)
        assertSame(backing, part.slice.array)
        assertEquals(3, part.slice.offset)
        assertEquals(5, part.slice.length)
        assertFalse(parser.hasRemaining())

        val mediaParser = UmpParser(part.slice)
        assertEquals(7u, mediaParser.readVarint())
        val payloadSlice = mediaParser.remainingSlice()
        assertSame(backing, payloadSlice.array)
        assertEquals(4, payloadSlice.offset)
        assertEquals(4, payloadSlice.length)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), payloadSlice.toByteArray())
    }
}
