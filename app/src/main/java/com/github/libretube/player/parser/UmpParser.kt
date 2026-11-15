package com.github.libretube.player.parser

import video_streaming.UmpPartId.UMPPartId

/** A parser to read UMP data using zero-copy slices of the backing [ByteArray]. */
class UmpParser(
    private val buf: ByteArray,
    startOffset: Int = 0,
    private val endExclusive: Int = buf.size,
) {
    constructor(slice: ByteSlice) : this(slice.array, slice.offset, slice.offset + slice.length)

    init {
        require(startOffset >= 0 && endExclusive >= startOffset && endExclusive <= buf.size) {
            "Invalid UmpParser bounds: startOffset=$startOffset, endExclusive=$endExclusive, bufSize=${buf.size}"
        }
    }

    private var position = startOffset

    /** Reads a single byte from the buffer. */
    private fun readByte(): UByte? {
        if (position >= endExclusive) return null
        return buf[position++].toUByte()
    }

    /** Returns a zero-copy [ByteSlice] of the next [n] bytes without copying. */
    private fun readSlice(n: Int): ByteSlice? {
        if (n < 0 || n > endExclusive - position) return null
        val slice = ByteSlice(buf, position, n)
        position += n
        return slice
    }

    /**
     * Read a variable sized integer from the buffer.
     *
     * The implementation follows
     * https://github.com/gsuberland/UMP_Format/blob/main/UMP_Format.md#variable-sized-integers
     */
    fun readVarint(): UInt? {
        val prefix = readByte() ?: return null

        // decode the size from the first 5 bits
        // [0...4] bits corresponds to a size of 1...5 bytes
        val varintSize = minOf(prefix.inv().countLeadingZeroBits(), 4) + 1

        var shift = 0
        var result = 0u

        if (varintSize != 5) {
            shift = 8 - varintSize
            // compute mask of prefix
            val mask = (1u shl shift) - 1u
            result = result or (prefix.toUInt() and mask)
        }

        for (i in 1 until varintSize) {
            val byte = readByte()?.toUInt() ?: return null
            result = result or (byte shl shift)
            shift += 8
        }

        return result
    }

    fun hasRemaining(): Boolean = position < endExclusive

    /** Returns a zero-copy [ByteSlice] over the remaining unconsumed bytes. */
    fun remainingSlice(): ByteSlice = ByteSlice(buf, position, endExclusive - position)

    /** Returns a copy of the remaining data of the buffer. */
    fun data(): ByteArray = buf.copyOfRange(position, endExclusive)

    /** Read a single [Part] backed by a zero-copy [ByteSlice]. */
    fun readPart(): Part? {
        val ty = readVarint() ?: return null
        val umpType = UMPPartId.forNumber(ty.toInt()) ?: UMPPartId.UNKNOWN

        val size = readVarint() ?: return null
        if (size > Int.MAX_VALUE.toUInt()) return null
        val slice = readSlice(size.toInt()) ?: return null

        return Part(umpType, slice)
    }

    /** A single segment (part) of a UMP stream. */
    data class Part(
        /**
         * Type of the part.
         *
         * Set to [UMPPartId.UNKNOWN] if the type could not be identified.
         */
        val type: UMPPartId,

        /** Zero-copy slice containing the part payload. */
        val slice: ByteSlice,
    ) {
        val data: ByteArray
            get() = slice.toByteArray()
    }
}
