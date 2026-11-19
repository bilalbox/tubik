package com.github.libretube.player

/** Decides, hop by hop, how much of the enhanced audio is let through. */
interface SpeechGate : AutoCloseable {
    val isLoaded: Boolean
        get() = true

    /**
     * Tells the gate the hop geometry it will be fed and returns the number of hops by which the
     * caller delays the gated audio behind this gate's decisions, so the gate is already open when
     * a word starts.
     */
    fun prepare(sampleRate: Int, hopSize: Int): Int

    /**
     * Feeds one hop of enhanced audio and returns the gain in [0f, 1f] at the start and end of the
     * hop being emitted, for a click-free linear ramp. [frameRatio] is enhanced RMS over input RMS.
     */
    fun processHop(hop: FloatArray, sampleRate: Int, frameRatio: Float): Pair<Float, Float>

    fun reset()
}
