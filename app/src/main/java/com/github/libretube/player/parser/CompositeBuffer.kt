package com.github.libretube.player.parser

import kotlin.math.min

/**
 * A buffer that wraps multiple smaller contiguous buffer slices, allowing to treat them as if they
 * were a single buffer without copying intermediate chunks.
 */
class CompositeBuffer
private constructor(
    private val slices: Array<ByteSlice>,
) {
    constructor(data: List<ByteArray>) : this(Array(data.size) { ByteSlice(data[it]) })

    companion object {
        fun fromSlices(slices: List<ByteSlice>): CompositeBuffer =
            CompositeBuffer(slices.toTypedArray())
    }

    private var position: Int = 0
    private val length = slices.sumOf { it.length }

    /** Returns the number of [Byte]s remaining in the buffer. */
    fun remaining() = length - position

    /** Returns whether the buffer still has [Byte]s to read. */
    fun hasRemaining() = position != length

    /** Transfers [length] bytes into [destination] starting at [offset]. */
    @Throws(IndexOutOfBoundsException::class)
    fun read(destination: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset + length < 0 || destination.size < offset + length) {
            throw IndexOutOfBoundsException(
                "Destination buffer does not have enough space to hold ${offset + length} bytes",
            )
        }

        var bytesTransferred = 0
        var globalOffset = 0
        var writeOffset = offset

        for (chunk in slices) {
            if (position >= globalOffset + chunk.length) {
                globalOffset += chunk.length
                continue
            }

            val chunkOffset = position - globalOffset
            val available = chunk.length - chunkOffset
            val remaining = length - bytesTransferred
            val toCopy = min(available, remaining)

            val srcStart = chunk.offset + chunkOffset
            chunk.array.copyInto(
                destination,
                writeOffset,
                srcStart,
                srcStart + toCopy,
            )

            position += toCopy
            writeOffset += toCopy
            bytesTransferred += toCopy

            if (bytesTransferred == length) break
            globalOffset += chunk.length
        }
    }
}
