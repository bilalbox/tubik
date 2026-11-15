package com.github.libretube.player.parser

/** Zero-copy view over a contiguous range `[offset, offset + length)` of [array]. */
class ByteSlice(
    val array: ByteArray,
    val offset: Int = 0,
    val length: Int = array.size - offset,
) {
    init {
        require(offset >= 0 && length >= 0 && offset <= array.size - length) {
            "Invalid ByteSlice bounds: offset=$offset, length=$length, arraySize=${array.size}"
        }
    }

    fun isEmpty(): Boolean = length == 0

    fun toByteArray(): ByteArray = array.copyOfRange(offset, offset + length)
}
