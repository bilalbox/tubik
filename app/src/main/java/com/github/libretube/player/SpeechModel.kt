package com.github.libretube.player

interface SpeechModel : AutoCloseable {
    val isLoaded: Boolean

    val sampleRate: Int

    val hopSize: Int

    val hopDurationNs: Long
        get() = if (sampleRate > 0 && hopSize > 0) (hopSize * 1_000_000_000L) / sampleRate else 0L

    fun processHop(hop: FloatArray): FloatArray

    fun reset() {}

    override fun close() {}
}
