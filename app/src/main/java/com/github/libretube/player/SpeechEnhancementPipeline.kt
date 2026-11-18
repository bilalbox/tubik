package com.github.libretube.player

class SpeechEnhancementPipeline(
    private val primaryModel: SpeechModel,
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

    override val isLoaded: Boolean
        get() = primaryModel.isLoaded

    private val silentHopBuf = FloatArray(hopSize.coerceAtLeast(0))

    // The gate decides on hop N while hop N - lookahead is being emitted, so it is already open
    // when a word starts instead of fading in over the word's first consonant.
    private val gateLookahead: GateLookahead? =
        vadGate
            ?.takeIf { hopSize > 0 && sampleRate > 0 }
            ?.prepare(sampleRate, hopSize)
            ?.takeIf { it > 0 }
            ?.let { GateLookahead(hops = it, hopSize = hopSize) }

    // Model lag, plus the post-filter's overlap-add half hop, plus the gate's lookahead
    override val latencySamples: Int =
        primaryModel.latencySamples +
            (if (postFilter != null) SpeechPostFilter.OUTPUT_DELAY_SAMPLES else 0) +
            (gateLookahead?.hops ?: 0) * hopSize.coerceAtLeast(0)

    // Previous and current input hop; the span the model output lines up with starts
    // latencySamples before the current hop.
    private val inputHistory = FloatArray(2 * hopSize.coerceAtLeast(0))
    private val alignedInputStart =
        (hopSize - primaryModel.latencySamples).coerceIn(0, hopSize.coerceAtLeast(0))

    override fun processHop(hop: FloatArray): FloatArray {
        check(isLoaded) { "SpeechEnhancementPipeline has no loaded model" }
        require(hop.size == hopSize) { "Expected hop of $hopSize, got ${hop.size}" }

        val pf = postFilter
        return if (pf == null) {
            processWithoutPostFilter(hop)
        } else {
            processWithPostFilter(pf, hop)
        }
    }

    private fun processWithPostFilter(pf: SpeechPostFilter, hop: FloatArray): FloatArray {
        val preFiltered = pf.preFilterInput(hop)
        val rawEnhanced = primaryModel.processHop(preFiltered)
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

    private fun processWithoutPostFilter(hop: FloatArray): FloatArray {
        val rawEnhanced = primaryModel.processHop(hop)
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
        postFilter?.reset()
        vadGate?.reset()
        gateLookahead?.reset()
        inputHistory.fill(0f)
    }

    override fun close() {
        runCatching { vadGate?.close() }
        runCatching { primaryModel.close() }
    }

    /**
     * Fixed delay of [hops] hops for the audio the gate is applied to. Each `delay*` call swaps the
     * newest hop in and returns the hop from [hops] calls ago; [advance] moves to the next slot.
     */
    private class GateLookahead(
        val hops: Int,
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

    companion object {
        private const val POST_FILTER_HOP_SIZE = 512
        private const val FRAME_RATIO_EPS = 1e-6f
        private const val MAX_FRAME_RATIO = 1.5f
    }
}
