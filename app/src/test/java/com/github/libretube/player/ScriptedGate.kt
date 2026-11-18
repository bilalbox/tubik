package com.github.libretube.player

/** Gate whose per-call gain is scripted: closed until [openAtCall], then fully open. */
internal class ScriptedGate(
    private val lookaheadHops: Int,
    private val openAtCall: Int = 0,
) : SpeechGate {
    private var calls = 0
    var resetCalls = 0

    override fun prepare(sampleRate: Int, hopSize: Int): Int = lookaheadHops

    override fun processHop(
        hop: FloatArray,
        sampleRate: Int,
        frameRatio: Float,
    ): Pair<Float, Float> {
        val call = calls++
        return when {
            call < openAtCall -> 0f to 0f
            call == openAtCall && openAtCall > 0 -> 0f to 1f
            else -> 1f to 1f
        }
    }

    override fun reset() {
        calls = 0
        resetCalls++
    }

    override fun close() = Unit
}
