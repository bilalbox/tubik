package com.github.libretube.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Speech band-pass filter (100 Hz – 7200 Hz) + 512-point overlap-add spectral Wiener post-filter.
 *
 * Uses pre-allocated scratch buffers and precomputed FFT twiddle/bit-reversal tables so the
 * real-time audio loop performs zero heap allocations per hop. Also supports fast-path delay
 * advancement when the downstream VAD gate is closed, bypassing all 6 FFT/IFFT passes.
 */
class SpeechPostFilter(
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {
    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun processInto(input: FloatArray, output: FloatArray) {
            var lx1 = x1
            var lx2 = x2
            var ly1 = y1
            var ly2 = y2
            for (i in 0 until HOP_SIZE) {
                val x = input[i]
                val y = b0 * x + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
                lx2 = lx1
                lx1 = x
                ly2 = ly1
                ly1 = y
                output[i] = y
            }
            x1 = lx1
            x2 = lx2
            y1 = ly1
            y2 = ly2
        }

        fun processInPlace(buffer: FloatArray) {
            processInto(buffer, buffer)
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }
    }

    private val preHighPass = createHighPass(sampleRate, cutoffHz = 100.0)
    private val postHighPass = createHighPass(sampleRate, cutoffHz = 100.0)
    private val postLowPass = createLowPass(sampleRate, cutoffHz = 7200.0)

    private val minBin = ((95.0 * FFT_SIZE) / sampleRate).toInt().coerceAtLeast(1)
    private val maxBin = ((7500.0 * FFT_SIZE) / sampleRate).toInt().coerceAtMost(NUM_BINS - 1)

    // 1-hop delay line for input audio to align with FastEnhancer's 512-sample STFT delay
    private val delayedInputHop = FloatArray(HOP_SIZE)
    private val prevInputTail = FloatArray(SUB_HOP)
    private val prevEnhancedTail = FloatArray(SUB_HOP)
    private val olaTail = FloatArray(SUB_HOP)
    private val smoothedGain = FloatArray(NUM_BINS) { 1f }

    // Pre-allocated scratch buffers (zero heap allocation per hop)
    private val preFilteredBuf = FloatArray(HOP_SIZE)
    private val outHopBuf = FloatArray(HOP_SIZE)
    private val inFrame0 = FloatArray(FFT_SIZE)
    private val enhFrame0 = FloatArray(FFT_SIZE)
    private val xRe = FloatArray(FFT_SIZE)
    private val xIm = FloatArray(FFT_SIZE)
    private val yRe = FloatArray(FFT_SIZE)
    private val yIm = FloatArray(FFT_SIZE)
    private val spec0Re = FloatArray(NUM_BINS)
    private val spec0Im = FloatArray(NUM_BINS)
    private val spec1Re = FloatArray(NUM_BINS)
    private val spec1Im = FloatArray(NUM_BINS)
    private val rawMask = FloatArray(NUM_BINS)

    internal var fftPassCount: Long = 0L
        private set

    fun preFilterInput(hop: FloatArray): FloatArray {
        preHighPass.processInto(hop, preFilteredBuf)
        return preFilteredBuf
    }

    /**
     * Computes the RMS ratio between the aligned input hop and [enhancedHop] without running FFTs.
     */
    fun computeFrameRatio(enhancedHop: FloatArray): Float {
        var inSq = 0.0
        var outSq = 0.0
        for (i in 0 until HOP_SIZE) {
            val x = delayedInputHop[i]
            val y = enhancedHop[i]
            inSq += x * x
            outSq += y * y
        }
        val inRms = sqrt(inSq / HOP_SIZE).toFloat()
        val outRms = sqrt(outSq / HOP_SIZE).toFloat()
        return (outRms / (inRms + 1e-6f)).coerceIn(0f, 1.5f)
    }

    /**
     * Fast-path state update when the VAD gate is completely closed (output gain = 0). Advances the
     * delay buffers without running the 6 FFT/IFFT passes.
     */
    fun advanceDelayOnly(currentInputHop: FloatArray, enhancedHop: FloatArray) {
        olaTail.fill(0f)
        System.arraycopy(delayedInputHop, SUB_HOP, prevInputTail, 0, SUB_HOP)
        System.arraycopy(enhancedHop, SUB_HOP, prevEnhancedTail, 0, SUB_HOP)
        System.arraycopy(currentInputHop, 0, delayedInputHop, 0, HOP_SIZE)
    }

    /**
     * Applies spectral post-filtering + band-pass filtering to [enhancedHop] using the pre-filtered
     * [currentInputHop] as reference.
     *
     * Fuses both real-valued sub-frame forward FFTs (2 real -> 1 complex FFT each) and both
     * real-valued inverse FFTs (2 real -> 1 complex IFFT), executing 3 FFT passes per hop instead
     * of 6.
     */
    fun applySpectralFilter(currentInputHop: FloatArray, enhancedHop: FloatArray): FloatArray {
        // Sub-frame 0: [prevTail (256), firstHalf (256)]
        System.arraycopy(prevInputTail, 0, inFrame0, 0, SUB_HOP)
        System.arraycopy(delayedInputHop, 0, inFrame0, SUB_HOP, SUB_HOP)
        System.arraycopy(prevEnhancedTail, 0, enhFrame0, 0, SUB_HOP)
        System.arraycopy(enhancedHop, 0, enhFrame0, SUB_HOP, SUB_HOP)

        computeMaskedSpectrumInto(inFrame0, enhFrame0, spec0Re, spec0Im)

        // Sub-frame 1: [firstHalf (256), secondHalf (256)]
        computeMaskedSpectrumInto(delayedInputHop, enhancedHop, spec1Re, spec1Im)

        // Pack W[k] = Y0[k] + j * Y1[k] so one 512-point complex IFFT yields both real sub-frames
        val halfN = FFT_SIZE / 2
        yRe[0] = spec0Re[0]
        yIm[0] = spec1Re[0]
        yRe[halfN] = spec0Re[halfN]
        yIm[halfN] = spec1Re[halfN]
        for (k in 1 until halfN) {
            val nk = FFT_SIZE - k
            val r0 = spec0Re[k]
            val i0 = spec0Im[k]
            val r1 = spec1Re[k]
            val i1 = spec1Im[k]
            yRe[k] = r0 - i1
            yIm[k] = i0 + r1
            yRe[nk] = r0 + i1
            yIm[nk] = r1 - i0
        }

        fft512(yRe, yIm, invert = true)
        fftPassCount++

        for (i in 0 until SUB_HOP) {
            val w0 = WINDOW_512[i]
            val w1 = WINDOW_512[SUB_HOP + i]
            val f0First = yRe[i] * w0
            val f0Second = yRe[SUB_HOP + i] * w1
            val f1First = yIm[i] * w0
            val f1Second = yIm[SUB_HOP + i] * w1
            outHopBuf[i] = olaTail[i] + f0First
            outHopBuf[SUB_HOP + i] = f0Second + f1First
            olaTail[i] = f1Second
        }

        // Save tails and advance 1-hop delay line
        System.arraycopy(delayedInputHop, SUB_HOP, prevInputTail, 0, SUB_HOP)
        System.arraycopy(enhancedHop, SUB_HOP, prevEnhancedTail, 0, SUB_HOP)
        System.arraycopy(currentInputHop, 0, delayedInputHop, 0, HOP_SIZE)

        postHighPass.processInPlace(outHopBuf)
        postLowPass.processInPlace(outHopBuf)
        return outHopBuf
    }

    fun postFilter(currentInputHop: FloatArray, enhancedHop: FloatArray): Pair<FloatArray, Float> {
        val frameRatio = computeFrameRatio(enhancedHop)
        val filtered = applySpectralFilter(currentInputHop, enhancedHop)
        return filtered to frameRatio
    }

    private fun computeMaskedSpectrumInto(
        inFrame: FloatArray,
        enhFrame: FloatArray,
        outSpecRe: FloatArray,
        outSpecIm: FloatArray,
    ) {
        for (i in 0 until FFT_SIZE) {
            val w = WINDOW_512[i]
            xRe[i] = inFrame[i] * w
            xIm[i] = enhFrame[i] * w
        }

        fft512(xRe, xIm, invert = false)
        fftPassCount++

        val halfN = FFT_SIZE / 2
        // Unpack DC (k=0) and Nyquist (k=N/2)
        outSpecRe[0] = xIm[0]
        outSpecIm[0] = 0f
        outSpecRe[halfN] = xIm[halfN]
        outSpecIm[halfN] = 0f
        if (maxBin == halfN) {
            val xMag = kotlin.math.abs(xRe[halfN])
            val yMag = kotlin.math.abs(xIm[halfN])
            rawMask[halfN] = computeMaskRatio(xMag, yMag)
        }

        // Unpack k in 1 until N/2 via conjugate symmetry of real inputs
        for (k in 1 until halfN) {
            val nk = FFT_SIZE - k
            val zReK = xRe[k]
            val zImK = xIm[k]
            val zReNK = xRe[nk]
            val zImNK = xIm[nk]

            val inRe = 0.5f * (zReK + zReNK)
            val inIm = 0.5f * (zImK - zImNK)
            val enhRe = 0.5f * (zImK + zImNK)
            val enhIm = 0.5f * (zReNK - zReK)

            outSpecRe[k] = enhRe
            outSpecIm[k] = enhIm

            if (k in minBin..maxBin) {
                val xMag = sqrt(inRe * inRe + inIm * inIm)
                val yMag = sqrt(enhRe * enhRe + enhIm * enhIm)
                rawMask[k] = computeMaskRatio(xMag, yMag)
            }
        }

        // 3-bin frequency smoothing + temporal smoothing to prevent musical-noise artifacts
        for (k in 0 until NUM_BINS) {
            val target =
                if (k in minBin..maxBin) {
                    val left = rawMask[(k - 1).coerceAtLeast(minBin)]
                    val center = rawMask[k]
                    val right = rawMask[(k + 1).coerceAtMost(maxBin)]
                    0.25f * left + 0.50f * center + 0.25f * right
                } else {
                    0f
                }
            val alpha = if (target > smoothedGain[k]) 0.65f else 0.35f
            val g = alpha * target + (1f - alpha) * smoothedGain[k]
            smoothedGain[k] = g

            outSpecRe[k] *= g
            outSpecIm[k] *= g
        }
    }

    private fun computeMaskRatio(xMag: Float, yMag: Float): Float {
        val ratio = (yMag / (xMag + 1e-7f)).coerceIn(0f, 1f)
        return when {
            ratio < MASK_FLOOR -> 0f
            ratio < MASK_KNEE -> {
                val norm = (ratio - MASK_FLOOR) / (MASK_KNEE - MASK_FLOOR)
                norm * norm
            }
            else -> 1f
        }
    }

    fun reset() {
        preHighPass.reset()
        postHighPass.reset()
        postLowPass.reset()
        delayedInputHop.fill(0f)
        prevInputTail.fill(0f)
        prevEnhancedTail.fill(0f)
        olaTail.fill(0f)
        smoothedGain.fill(1f)
        rawMask.fill(0f)
        fftPassCount = 0L
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val HOP_SIZE = 512
        private const val SUB_HOP = 256
        private const val FFT_SIZE = 512
        private const val FFT_STAGES = 9
        private const val NUM_BINS = FFT_SIZE / 2 + 1

        private const val MASK_FLOOR = 0.10f
        private const val MASK_KNEE = 0.52f

        private val WINDOW_512 =
            FloatArray(FFT_SIZE) { n ->
                sin(PI * (n + 0.5) / FFT_SIZE).toFloat()
            }

        private val BIT_REV_512 =
            IntArray(FFT_SIZE).apply {
                var j = 0
                for (i in 1 until FFT_SIZE) {
                    var bit = FFT_SIZE shr 1
                    while (j and bit != 0) {
                        j = j xor bit
                        bit = bit shr 1
                    }
                    j = j xor bit
                    this[i] = j
                }
            }

        // Precomputed twiddle tables for each stage s in 0 until 9 (len = 2^(s+1), half = 2^s)
        private val TWIDDLE_RE =
            Array(FFT_STAGES) { s ->
                val len = 1 shl (s + 1)
                val half = len shr 1
                FloatArray(half) { k -> cos(-2.0 * PI * k / len).toFloat() }
            }

        private val TWIDDLE_IM =
            Array(FFT_STAGES) { s ->
                val len = 1 shl (s + 1)
                val half = len shr 1
                FloatArray(half) { k -> sin(-2.0 * PI * k / len).toFloat() }
            }

        private fun fft512(re: FloatArray, im: FloatArray, invert: Boolean) {
            for (i in 1 until FFT_SIZE) {
                val j = BIT_REV_512[i]
                if (i < j) {
                    val tr = re[i]
                    re[i] = re[j]
                    re[j] = tr
                    val ti = im[i]
                    im[i] = im[j]
                    im[j] = ti
                }
            }
            for (stage in 0 until FFT_STAGES) {
                val len = 1 shl (stage + 1)
                val half = len shr 1
                val twRe = TWIDDLE_RE[stage]
                val twIm = TWIDDLE_IM[stage]
                var i = 0
                if (!invert) {
                    while (i < FFT_SIZE) {
                        for (k in 0 until half) {
                            val uIdx = i + k
                            val vIdx = uIdx + half
                            val wRe = twRe[k]
                            val wIm = twIm[k]
                            val uRe = re[uIdx]
                            val uIm = im[uIdx]
                            val vRe = re[vIdx] * wRe - im[vIdx] * wIm
                            val vIm = re[vIdx] * wIm + im[vIdx] * wRe
                            re[uIdx] = uRe + vRe
                            im[uIdx] = uIm + vIm
                            re[vIdx] = uRe - vRe
                            im[vIdx] = uIm - vIm
                        }
                        i += len
                    }
                } else {
                    while (i < FFT_SIZE) {
                        for (k in 0 until half) {
                            val uIdx = i + k
                            val vIdx = uIdx + half
                            val wRe = twRe[k]
                            val wIm = -twIm[k]
                            val uRe = re[uIdx]
                            val uIm = im[uIdx]
                            val vRe = re[vIdx] * wRe - im[vIdx] * wIm
                            val vIm = re[vIdx] * wIm + im[vIdx] * wRe
                            re[uIdx] = uRe + vRe
                            im[uIdx] = uIm + vIm
                            re[vIdx] = uRe - vRe
                            im[vIdx] = uIm - vIm
                        }
                        i += len
                    }
                }
            }
            if (invert) {
                val invN = 1f / FFT_SIZE
                for (i in 0 until FFT_SIZE) {
                    re[i] *= invN
                    im[i] *= invN
                }
            }
        }

        private fun createHighPass(sampleRate: Int, cutoffHz: Double): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / sqrt(2.0)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
            val b1 = (-(1.0 + cosW0) / a0).toFloat()
            val b2 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
            val a1 = (-2.0 * cosW0 / a0).toFloat()
            val a2 = ((1.0 - alpha) / a0).toFloat()
            return Biquad(b0, b1, b2, a1, a2)
        }

        private fun createLowPass(sampleRate: Int, cutoffHz: Double): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / sqrt(2.0)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 - cosW0) / 2.0 / a0).toFloat()
            val b1 = ((1.0 - cosW0) / a0).toFloat()
            val b2 = ((1.0 - cosW0) / 2.0 / a0).toFloat()
            val a1 = (-2.0 * cosW0 / a0).toFloat()
            val a2 = ((1.0 - alpha) / a0).toFloat()
            return Biquad(b0, b1, b2, a1, a2)
        }
    }
}
