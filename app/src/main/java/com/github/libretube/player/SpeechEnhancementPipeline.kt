package com.github.libretube.player

class SpeechEnhancementPipeline(
    private val primaryModel: SpeechModel,
    private val fallbackModelProvider: (() -> SpeechModel)? = null,
    private val vadGate: SpeechGate? = null,
    private val postFilter: SpeechPostFilter? =
        if (primaryModel.sampleRate > 0 && primaryModel.hopSize == POST_FILTER_HOP_SIZE) {
            SpeechPostFilter(primaryModel.sampleRate, primaryModel.latencySamples)
        } else {
            null
        },
) : SpeechModel {

    override val sampleRate: Int = primaryModel.sampleRate

    override val hopSize: Int = primaryModel.hopSize

    private var fallbackModel: SpeechModel? = null
    private var fallbackAdapter: FallbackGeometryAdapter? = null

    var isUsingFallback: Boolean = false
        private set

    override val isLoaded: Boolean
        get() =
            if (isUsingFallback) {
                fallbackModel?.isLoaded == true
            } else {
                primaryModel.isLoaded
            }

    private val silentHopBuf = FloatArray(hopSize.coerceAtLeast(0))

    // The gate decides on hop N while hop N - lookahead is being emitted, so it is already open
    // when a word starts instead of fading in over the word's first consonant.
    private val gateLookahead: GateLookahead? =
        vadGate
            ?.takeIf { hopSize > 0 && sampleRate > 0 }
            ?.prepare(sampleRate, hopSize)
            ?.takeIf { it > 0 }
            ?.let { GateLookahead(hops = it, hopSize = hopSize) }

    // Previous and current input hop; the span the model output lines up with starts
    // latencySamples before the current hop.
    private val inputHistory = FloatArray(2 * hopSize.coerceAtLeast(0))
    private val alignedInputStart =
        (hopSize - primaryModel.latencySamples).coerceIn(0, hopSize.coerceAtLeast(0))

    fun downshiftToFallback(): Boolean {
        val fallback =
            if (!isUsingFallback) {
                fallbackModelProvider?.let { provider ->
                    fallbackModel
                        ?: runCatching { provider() }.getOrNull()?.also { fallbackModel = it }
                }
            } else {
                null
            }
        val canDownshift = fallback != null && fallback.isLoaded
        if (canDownshift) {
            val loadedFallback = fallback ?: return false
            isUsingFallback = true
            runCatching { primaryModel.reset() }
            fallbackAdapter =
                if (loadedFallback.sampleRate != sampleRate || loadedFallback.hopSize != hopSize) {
                    FallbackGeometryAdapter(
                        externalRate = sampleRate,
                        externalHopSize = hopSize,
                        internalRate = loadedFallback.sampleRate,
                        internalHopSize = loadedFallback.hopSize,
                    )
                } else {
                    null
                }
        }
        return canDownshift
    }

    override fun processHop(hop: FloatArray): FloatArray {
        check(isLoaded) { "SpeechEnhancementPipeline has no loaded model" }
        require(hop.size == hopSize) { "Expected hop of $hopSize, got ${hop.size}" }

        val activeModel =
            if (isUsingFallback) {
                fallbackModel ?: error("Fallback model missing")
            } else {
                primaryModel
            }

        val pf = postFilter
        return if (pf == null) {
            processWithoutPostFilter(hop, activeModel)
        } else {
            processWithPostFilter(pf, hop, activeModel)
        }
    }

    private fun processWithPostFilter(
        pf: SpeechPostFilter,
        hop: FloatArray,
        activeModel: SpeechModel,
    ): FloatArray {
        val preFiltered = pf.preFilterInput(hop)
        val rawEnhanced = runActiveModel(activeModel, preFiltered)
        val gate = vadGate ?: return pf.applySpectralFilter(preFiltered, rawEnhanced)

        System.arraycopy(inputHistory, hopSize, inputHistory, 0, hopSize)
        System.arraycopy(preFiltered, 0, inputHistory, hopSize, hopSize)
        val frameRatio = computeFrameRatio(inputHistory, alignedInputStart, rawEnhanced)
        val (startGain, endGain) = gate.processHop(rawEnhanced, sampleRate, frameRatio)

        val lookahead = gateLookahead
        val gatedInput = lookahead?.delayInput(preFiltered) ?: preFiltered
        val gatedEnhanced = lookahead?.delayEnhanced(rawEnhanced) ?: rawEnhanced
        lookahead?.advance()

        return if (startGain == 0f && endGain == 0f) {
            pf.advanceDelayOnly(gatedInput, gatedEnhanced)
            silentHopBuf
        } else {
            val postFiltered = pf.applySpectralFilter(gatedInput, gatedEnhanced)
            if (startGain != 1f || endGain != 1f) {
                applyGainRamp(postFiltered, startGain, endGain)
            }
            postFiltered
        }
    }

    private fun processWithoutPostFilter(hop: FloatArray, activeModel: SpeechModel): FloatArray {
        val rawEnhanced = runActiveModel(activeModel, hop)
        val gate = vadGate ?: return rawEnhanced
        val frameRatio = computeFrameRatio(hop, 0, rawEnhanced)
        val (startGain, endGain) = gate.processHop(rawEnhanced, sampleRate, frameRatio)
        val lookahead = gateLookahead
        val gatedEnhanced = lookahead?.delayEnhanced(rawEnhanced) ?: rawEnhanced
        lookahead?.advance()
        return when {
            startGain == 0f && endGain == 0f -> silentHopBuf
            startGain != 1f || endGain != 1f ->
                gatedEnhanced.copyOf().also { applyGainRamp(it, startGain, endGain) }
            else -> gatedEnhanced
        }
    }

    private fun runActiveModel(model: SpeechModel, hop: FloatArray): FloatArray {
        val adapter = if (isUsingFallback) fallbackAdapter else null
        return if (adapter != null) {
            adapter.process(hop, model)
        } else {
            model.processHop(hop)
        }
    }

    /** RMS of [enhancedHop] over RMS of the equally long span of [input] starting at [start]. */
    private fun computeFrameRatio(input: FloatArray, start: Int, enhancedHop: FloatArray): Float {
        val len = minOf(input.size - start, enhancedHop.size)
        if (len <= 0) return 0f
        var inSq = 0.0
        var outSq = 0.0
        for (i in 0 until len) {
            val x = input[start + i]
            val y = enhancedHop[i]
            inSq += x * x
            outSq += y * y
        }
        val inRms = kotlin.math.sqrt(inSq / len).toFloat()
        val outRms = kotlin.math.sqrt(outSq / len).toFloat()
        return (outRms / (inRms + FRAME_RATIO_EPS)).coerceIn(0f, MAX_FRAME_RATIO)
    }

    private fun applyGainRamp(samples: FloatArray, startGain: Float, endGain: Float) {
        val denom = (samples.size - 1).coerceAtLeast(1).toFloat()
        for (i in samples.indices) {
            val alpha = i / denom
            val g = startGain + (endGain - startGain) * alpha
            samples[i] *= g
        }
    }

    override fun reset() {
        runCatching { primaryModel.reset() }
        runCatching { fallbackModel?.reset() }
        fallbackAdapter?.reset()
        postFilter?.reset()
        vadGate?.reset()
        gateLookahead?.reset()
        inputHistory.fill(0f)
    }

    override fun close() {
        runCatching { vadGate?.close() }
        runCatching { primaryModel.close() }
        runCatching { fallbackModel?.close() }
        fallbackModel = null
        fallbackAdapter = null
    }

    /**
     * Fixed delay of [hops] hops for the audio the gate is applied to. Each `delay*` call swaps the
     * newest hop in and returns the hop from [hops] calls ago; [advance] moves to the next slot.
     */
    private class GateLookahead(
        private val hops: Int,
        private val hopSize: Int,
    ) {
        private val inputRing = Array(hops) { FloatArray(hopSize) }
        private val enhancedRing = Array(hops) { FloatArray(hopSize) }
        private val delayedInput = FloatArray(hopSize)
        private val delayedEnhanced = FloatArray(hopSize)
        private var slot = 0

        fun delayInput(hop: FloatArray): FloatArray = exchange(inputRing[slot], hop, delayedInput)

        fun delayEnhanced(hop: FloatArray): FloatArray =
            exchange(enhancedRing[slot], hop, delayedEnhanced)

        fun advance() {
            slot = (slot + 1) % hops
        }

        private fun exchange(stored: FloatArray, newest: FloatArray, out: FloatArray): FloatArray {
            System.arraycopy(stored, 0, out, 0, hopSize)
            System.arraycopy(newest, 0, stored, 0, hopSize)
            return out
        }

        fun reset() {
            for (i in 0 until hops) {
                inputRing[i].fill(0f)
                enhancedRing[i].fill(0f)
            }
            slot = 0
        }
    }

    internal class FallbackGeometryAdapter(
        private val externalRate: Int,
        private val externalHopSize: Int,
        private val internalRate: Int,
        private val internalHopSize: Int,
    ) {
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

    companion object {
        private const val POST_FILTER_HOP_SIZE = 512
        private const val FRAME_RATIO_EPS = 1e-6f
        private const val MAX_FRAME_RATIO = 1.5f
    }
}
