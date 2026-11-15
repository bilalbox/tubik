package com.github.libretube.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 8th-order Butterworth low-pass (four cascaded biquads) used on the higher-rate side of the
 * linear-interpolation resamplers: before decimating it keeps content above the model's Nyquist
 * from aliasing into the speech band, after interpolating it removes the spectral images.
 */
internal class ResamplingLowPass(
    val sampleRate: Int,
    val cutoffHz: Double,
) {
    private val b0 = FloatArray(STAGES)
    private val b1 = FloatArray(STAGES)
    private val a1 = FloatArray(STAGES)
    private val a2 = FloatArray(STAGES)
    private val x1 = FloatArray(STAGES)
    private val x2 = FloatArray(STAGES)
    private val y1 = FloatArray(STAGES)
    private val y2 = FloatArray(STAGES)

    init {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        for (stage in 0 until STAGES) {
            val q = 1.0 / (2.0 * cos(PI * (2 * stage + 1) / (2.0 * ORDER)))
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0[stage] = ((1.0 - cosW0) / 2.0 / a0).toFloat()
            b1[stage] = ((1.0 - cosW0) / a0).toFloat()
            a1[stage] = (-(2.0 * cosW0) / a0).toFloat()
            a2[stage] = ((1.0 - alpha) / a0).toFloat()
        }
    }

    fun processInPlace(buffer: FloatArray, count: Int) {
        for (stage in 0 until STAGES) {
            val sb0 = b0[stage]
            val sb1 = b1[stage]
            val sa1 = a1[stage]
            val sa2 = a2[stage]
            var lx1 = x1[stage]
            var lx2 = x2[stage]
            var ly1 = y1[stage]
            var ly2 = y2[stage]
            for (i in 0 until count) {
                val x = buffer[i]
                val y = sb0 * x + sb1 * lx1 + sb0 * lx2 - sa1 * ly1 - sa2 * ly2
                lx2 = lx1
                lx1 = x
                ly2 = ly1
                ly1 = y
                buffer[i] = y
            }
            x1[stage] = lx1
            x2[stage] = lx2
            y1[stage] = ly1
            y2[stage] = ly2
        }
    }

    fun reset() {
        x1.fill(0f)
        x2.fill(0f)
        y1.fill(0f)
        y2.fill(0f)
    }

    companion object {
        private const val ORDER = 8
        private const val STAGES = ORDER / 2

        // Cutoff as a fraction of the lower of the two sample rates (7 kHz for 16 kHz)
        const val CUTOFF_FRACTION = 0.4375

        /** Filter for converting between [fromRate] and [toRate], or null if they are equal. */
        fun between(fromRate: Int, toRate: Int): ResamplingLowPass? =
            if (fromRate <= 0 || toRate <= 0 || fromRate == toRate) {
                null
            } else {
                ResamplingLowPass(
                    sampleRate = maxOf(fromRate, toRate),
                    cutoffHz = CUTOFF_FRACTION * minOf(fromRate, toRate),
                )
            }
    }
}
