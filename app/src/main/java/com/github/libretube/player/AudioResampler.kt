package com.github.libretube.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming linear resampler paired with an 8th-order Butterworth low-pass filter on the
 * higher-rate side: before decimating it keeps content above the lower Nyquist from aliasing into
 * the speech band, and after interpolating it removes spectral images.
 */
internal class AudioResampler(
    val fromRate: Int,
    val toRate: Int,
) {
    private val lowPass: ButterworthLowPass? =
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate) {
            null
        } else {
            ButterworthLowPass(
                sampleRate = maxOf(fromRate, toRate),
                cutoffHz = CUTOFF_FRACTION * minOf(fromRate, toRate),
            )
        }

    private var phase = 0.0
    private var prevSample = 0f
    private var hasPrev = false
    private var scratchInput = FloatArray(0)
    var outputBuffer: FloatArray = FloatArray(INITIAL_OUT_CAPACITY)
        private set

    fun resample(input: FloatArray, inCount: Int, mutateInput: Boolean = false): Int =
        when {
            inCount <= 0 -> 0
            fromRate == toRate || fromRate <= 0 || toRate <= 0 -> {
                ensureOutputCapacity(inCount)
                System.arraycopy(input, 0, outputBuffer, 0, inCount)
                inCount
            }
            else -> resampleFiltered(input, inCount, mutateInput)
        }

    private fun resampleFiltered(input: FloatArray, inCount: Int, mutateInput: Boolean): Int {
        val source: FloatArray =
            if (fromRate > toRate) {
                val filtered =
                    if (mutateInput) {
                        input
                    } else {
                        if (scratchInput.size < inCount) scratchInput = FloatArray(inCount * 2)
                        System.arraycopy(input, 0, scratchInput, 0, inCount)
                        scratchInput
                    }
                lowPass?.processInPlace(filtered, inCount)
                filtered
            } else {
                input
            }

        val step = fromRate.toDouble() / toRate.toDouble()
        val maxOut = ((inCount / step).toInt()) + OUTPUT_SLACK_SAMPLES
        ensureOutputCapacity(maxOut)
        var outCount = 0

        if (!hasPrev) {
            prevSample = source[0]
            hasPrev = true
        }

        while (phase < inCount) {
            val idx = phase.toInt()
            val frac = (phase - idx).toFloat()
            val s0 = if (idx == 0) prevSample else source[idx - 1]
            val s1 = source[idx]
            outputBuffer[outCount++] = s0 + (s1 - s0) * frac
            phase += step
        }

        phase -= inCount
        prevSample = source[inCount - 1]

        if (toRate > fromRate) {
            lowPass?.processInPlace(outputBuffer, outCount)
        }
        return outCount
    }

    /** Clears filter and previous-sample history to silence so prior audio cannot bleed forward. */
    fun resetToSilence() {
        prevSample = 0f
        hasPrev = true
        lowPass?.reset()
    }

    fun reset() {
        phase = 0.0
        prevSample = 0f
        hasPrev = false
        lowPass?.reset()
    }

    private fun ensureOutputCapacity(needed: Int) {
        if (outputBuffer.size < needed) {
            outputBuffer = FloatArray(needed * 2)
        }
    }

    private class ButterworthLowPass(
        sampleRate: Int,
        cutoffHz: Double,
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
    }

    companion object {
        private const val ORDER = 8
        private const val STAGES = ORDER / 2
        private const val CUTOFF_FRACTION = 0.4375
        private const val INITIAL_OUT_CAPACITY = 2048
        private const val OUTPUT_SLACK_SAMPLES = 4

        fun reuseOrCreate(current: AudioResampler?, fromRate: Int, toRate: Int): AudioResampler =
            current?.takeIf { it.fromRate == fromRate && it.toRate == toRate }
                ?: AudioResampler(fromRate, toRate)
    }
}
