package com.github.libretube.player

/**
 * Runs a [SpeechModel] whose sample rate or hop size differs from the processor's, resampling each
 * external hop into internal hops and the model output back, so a fallback with a different
 * geometry can replace the primary model mid-stream without changing the output format.
 */
internal class ModelGeometryAdapter(
    private val externalRate: Int,
    private val externalHopSize: Int,
    private val internalRate: Int,
    private val internalHopSize: Int,
) {
    /** External samples by which output lags input: one internal hop must fill first. */
    val latencySamples: Int =
        ((internalHopSize.toLong() * externalRate + internalRate - 1) / internalRate).toInt()

    private val inResampler = LinearResampler(externalRate, internalRate)
    private val outResampler = LinearResampler(internalRate, externalRate)
    private val internalHopBuf = FloatArray(internalHopSize)
    private var internalHopFill = 0

    private var resampledInBuf =
        FloatArray(((externalHopSize.toLong() * internalRate / externalRate).toInt() + 16))
    private var resampledOutBuf =
        FloatArray(((internalHopSize.toLong() * externalRate / internalRate).toInt() + 16))

    private val fifoCapacity =
        maxOf(externalHopSize * 4, resampledOutBuf.size * 2).coerceAtLeast(externalHopSize * 2)
    private val outFifo = FloatArray(fifoCapacity)
    private var fifoRead = 0
    private var fifoWrite = 0
    private var fifoSize = 0
    private val externalOutBuf = FloatArray(externalHopSize)

    fun process(externalHop: FloatArray, model: SpeechModel): FloatArray {
        val inCount =
            inResampler.resample(externalHop, externalHopSize) { needed ->
                if (resampledInBuf.size < needed) resampledInBuf = FloatArray(needed * 2)
                resampledInBuf
            }

        for (i in 0 until inCount) {
            internalHopBuf[internalHopFill++] = resampledInBuf[i]
            if (internalHopFill == internalHopSize) {
                internalHopFill = 0
                val processedInternal = model.processHop(internalHopBuf)
                val outCount =
                    outResampler.resample(processedInternal, processedInternal.size) { needed ->
                        if (resampledOutBuf.size < needed) {
                            resampledOutBuf = FloatArray(needed * 2)
                        }
                        resampledOutBuf
                    }
                pushFifo(resampledOutBuf, outCount)
            }
        }

        popFifoInto(externalOutBuf, externalHopSize)
        return externalOutBuf
    }

    private fun pushFifo(src: FloatArray, count: Int) {
        for (i in 0 until count) {
            if (fifoSize == outFifo.size) {
                fifoRead = (fifoRead + 1) % outFifo.size
                fifoSize--
            }
            outFifo[fifoWrite] = src[i]
            fifoWrite = (fifoWrite + 1) % outFifo.size
            fifoSize++
        }
    }

    private fun popFifoInto(dest: FloatArray, count: Int) {
        val available = fifoSize.coerceAtMost(count)
        for (i in 0 until available) {
            dest[i] = outFifo[fifoRead]
            fifoRead = (fifoRead + 1) % outFifo.size
            fifoSize--
        }
        for (i in available until count) {
            dest[i] = 0f
        }
    }

    fun reset() {
        inResampler.reset()
        outResampler.reset()
        internalHopFill = 0
        fifoRead = 0
        fifoWrite = 0
        fifoSize = 0
    }
}

private class LinearResampler(
    private val fromRate: Int,
    private val toRate: Int,
) {
    private var phase = 0.0
    private var prevSample = 0f
    private var hasPrev = false

    inline fun resample(
        input: FloatArray,
        inCount: Int,
        ensureOutBuf: (Int) -> FloatArray,
    ): Int =
        when {
            inCount <= 0 -> 0
            fromRate == toRate || fromRate <= 0 || toRate <= 0 -> {
                val out = ensureOutBuf(inCount)
                System.arraycopy(input, 0, out, 0, inCount)
                inCount
            }
            else -> {
                val step = fromRate.toDouble() / toRate.toDouble()
                val maxOut = ((inCount / step).toInt()) + 4
                val out = ensureOutBuf(maxOut)
                var outCount = 0

                if (!hasPrev) {
                    prevSample = input[0]
                    hasPrev = true
                }

                while (phase < inCount) {
                    val idx = phase.toInt()
                    val frac = (phase - idx).toFloat()
                    val s0 = if (idx == 0) prevSample else input[idx - 1]
                    val s1 = input[idx]
                    out[outCount++] = s0 + (s1 - s0) * frac
                    phase += step
                }

                phase -= inCount
                prevSample = input[inCount - 1]
                outCount
            }
        }

    fun reset() {
        phase = 0.0
        prevSample = 0f
        hasPrev = false
    }
}
