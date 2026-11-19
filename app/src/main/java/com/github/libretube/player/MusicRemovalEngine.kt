package com.github.libretube.player

import android.os.Build
import android.util.Log
import com.github.libretube.BuildConfig

data class MusicRemovalConfig(
    val nanoTimeProvider: () -> Long = { System.nanoTime() },
    val overloadSustainHops: Int? = null,
    val downshiftSustainHops: Int? = null,
    val enablePostFilter: Boolean = true,
    val logTelemetry: Boolean = BuildConfig.DEBUG,
    val onOverloadMuted: (device: String, measuredRate: Double) -> Unit = { device, rate ->
        runCatching {
            Log.w(
                MusicRemovalEngine.TAG,
                "Music Removal overload on $device: measured rate ${"%.2f".format(rate)}x, muting audio",
            )
        }
    },
)

data class MusicRemovalGeometry(
    val sampleRate: Int,
    val hopSize: Int,
)

class HopOutput(
    val samples: FloatArray,
    val validSamples: Int,
    val isMuted: Boolean,
    val startsFromSilence: Boolean,
)

/**
 * Synchronous Music Removal DSP and policy engine.
 *
 * Owns spectral post-filtering, VAD lookahead gating, Overload Mute throughput monitoring, fallback
 * downshift and geometry adaptation, click-free fades, and end-of-stream latency draining behind
 * narrow [SpeechModel] and [SpeechGate] adapter seams.
 */
class MusicRemovalEngine(
    modelProvider: () -> SpeechModel,
    fallbackModelProvider: (() -> SpeechModel)? = null,
    gateProvider: (() -> SpeechGate)? = null,
    playbackSpeedProvider: () -> Float = { 1.0f },
    private val config: MusicRemovalConfig = MusicRemovalConfig(),
) : AutoCloseable {

    private val stages =
        StageManager(
            modelProvider = modelProvider,
            fallbackModelProvider = fallbackModelProvider,
            gateProvider = gateProvider,
            enablePostFilter = config.enablePostFilter,
            onGeometryChanged = { rate, hop -> onGeometryUpdated(rate, hop) },
        )
    private val overload = OverloadMonitor(config, playbackSpeedProvider)

    val sampleRate: Int
        get() = stages.sampleRate

    val hopSize: Int
        get() = stages.hopSize

    val isMutedForOverload: Boolean
        get() = overload.isMuted

    val isUsingFallbackModel: Boolean
        get() = stages.isUsingFallbackModel

    val latencySamples: Int
        get() = stages.latencySamples

    private var silentHop = FloatArray(DEFAULT_HOP_SIZE)
    private var crossfadeBuf = FloatArray(DEFAULT_HOP_SIZE)
    private var endOfStreamPadding = FloatArray(DEFAULT_HOP_SIZE)

    private var lastProcessedSample = 0f
    private var emittedMute = false
    private var pendingFallbackCrossfade = false

    init {
        overload.applyTiming(DEFAULT_SAMPLE_RATE, DEFAULT_HOP_SIZE)
    }

    val hasInitializedPrimaryModel: Boolean
        get() = stages.hasInitializedPrimaryModel

    /** Ensures primary model geometry is loaded and overload timing matches its hop duration. */
    fun ensureGeometry(): MusicRemovalGeometry = stages.ensureGeometry()

    /** Processes one model-rate hop synchronously according to Music Removal policy. */
    fun processHop(
        hop: FloatArray,
        enabled: Boolean,
        previousEnabled: Boolean? = null,
    ): HopOutput {
        if (!enabled) {
            return processDisabledHop(hop, previousEnabled)
        }

        val fadesIn = previousEnabled == false || emittedMute
        if (fadesIn) {
            // A fallback crossfade in this hop must also start from silence, not the original audio
            lastProcessedSample = 0f
        }

        val (processed, muted) = runModelOnHop(hop)
        val startsFromSilence = !muted && fadesIn
        val result =
            when {
                muted -> processed
                startsFromSilence -> crossfadeIntoBuffer(processed.size, { 0f }, processed)
                else -> processed
            }
        emittedMute = muted
        if (result.isNotEmpty()) {
            lastProcessedSample = result[result.size - 1]
        }
        return HopOutput(
            samples = result,
            validSamples = result.size,
            isMuted = muted,
            startsFromSilence = startsFromSilence,
        )
    }

    /**
     * Processes the zero-padded final hop, then feeds silence until [validSamples] plus all speech
     * held in model, post-filter, and gate lookahead buffers has been emitted. Stops immediately if
     * [emit] returns `false`.
     */
    fun drainEndOfStream(
        finalHop: FloatArray,
        validSamples: Int,
        enabled: Boolean,
        previousEnabled: Boolean?,
        emit: (HopOutput) -> Boolean,
    ) {
        if (validSamples == 0 && (!enabled || latencySamples == 0)) return
        var output = processHop(finalHop, enabled, previousEnabled)
        var remaining = validSamples + if (enabled) latencySamples else 0
        while (remaining > 0) {
            val count = remaining.coerceAtMost(output.samples.size)
            val chunk =
                HopOutput(
                    samples = output.samples,
                    validSamples = count,
                    isMuted = output.isMuted,
                    startsFromSilence = output.startsFromSilence,
                )
            if (!emit(chunk)) return
            remaining -= count
            if (remaining > 0) {
                endOfStreamPadding.fill(0f)
                output = processHop(endOfStreamPadding, enabled, enabled)
            }
        }
    }

    /** Resets streaming DSP state, overload tracking, and lookahead buffers (e.g. on seek). */
    fun reset() {
        overload.reset()
        lastProcessedSample = 0f
        emittedMute = false
        pendingFallbackCrossfade = false
        stages.reset()
    }

    override fun close() {
        stages.close()
        overload.reset()
    }

    private fun onGeometryUpdated(newRate: Int, newHop: Int) {
        overload.applyTiming(newRate, newHop)
        if (newHop != silentHop.size) {
            silentHop = FloatArray(newHop)
            crossfadeBuf = FloatArray(newHop)
            endOfStreamPadding = FloatArray(newHop)
        }
    }

    private fun processDisabledHop(hop: FloatArray, previousEnabled: Boolean?): HopOutput {
        overload.reset()
        emittedMute = false
        val startSample = lastProcessedSample
        val out =
            if (previousEnabled == true) {
                crossfadeIntoBuffer(hop.size, { startSample }, hop)
            } else {
                hop
            }
        if (out.isNotEmpty()) {
            lastProcessedSample = out[out.size - 1]
        }
        return HopOutput(
            samples = out,
            validSamples = out.size,
            isMuted = false,
            startsFromSilence = false,
        )
    }

    private fun runModelOnHop(hop: FloatArray): Pair<FloatArray, Boolean> {
        val stage = stages.acquireStageOrFallback(onDownshifted = ::onDownshifted)
        val outcome =
            if (stage == null) {
                overload.markOverload(0.0)
                silentHop to true
            } else {
                executeStage(stage, hop)
            }
        return outcome
    }

    private fun executeStage(stage: ProcessingStage, hop: FloatArray): Pair<FloatArray, Boolean> =
        try {
            stages.hasProcessedHops = true
            val wasFallbackPending = pendingFallbackCrossfade
            pendingFallbackCrossfade = false

            val startNs = config.nanoTimeProvider()
            val rawProcessed = stage.process(hop)
            val elapsedNs = (config.nanoTimeProvider() - startNs).coerceAtLeast(1L).toDouble()
            val hopDurNs = stage.hopDurationNs

            val startSample = lastProcessedSample
            val processed =
                if (wasFallbackPending) {
                    crossfadeIntoBuffer(rawProcessed.size, { startSample }, rawProcessed)
                } else {
                    rawProcessed
                }

            val shouldDownshift =
                overload.recordHop(
                    elapsedNs = elapsedNs,
                    hopDurNs = hopDurNs,
                    sampleRate = sampleRate,
                    isUsingFallback = isUsingFallbackModel,
                    hasFallback = stages.hasFallbackProvider,
                )
            if (
                shouldDownshift && !stages.tryDownshiftToFallback(onDownshifted = ::onDownshifted)
            ) {
                overload.markOverload(hopDurNs / elapsedNs)
            }

            if (overload.isMuted) silentHop to true else processed to false
        } catch (_: Exception) {
            if (stages.tryDownshiftToFallback(onDownshifted = ::onDownshifted)) {
                runModelOnHop(hop)
            } else {
                overload.markOverload(0.0)
                silentHop to true
            }
        }

    private fun onDownshifted() {
        pendingFallbackCrossfade = true
        overload.resetStreaksAfterDownshift()
    }

    private inline fun crossfadeIntoBuffer(
        size: Int,
        crossinline fromAt: (Int) -> Float,
        to: FloatArray,
    ): FloatArray {
        val denom = (size - 1).coerceAtLeast(1).toFloat()
        for (i in 0 until size) {
            val alpha = i.toFloat() / denom
            crossfadeBuf[i] = fromAt(i) * (1f - alpha) + to[i] * alpha
        }
        return crossfadeBuf
    }

    private interface ProcessingStage {
        val latencySamples: Int
        val hopDurationNs: Double

        fun process(hop: FloatArray): FloatArray

        fun reset()
    }

    private class OverloadMonitor(
        private val config: MusicRemovalConfig,
        private val playbackSpeedProvider: () -> Float,
    ) {
        @Volatile
        var isMuted: Boolean = false
            private set

        private var emaElapsedNs = 0.0
        private var slowHopStreak = 0
        private var recoveryHopStreak = 0
        private var telemetryHops = 0
        private var telemetryElapsedNs = 0L
        private var telemetryWorstHopNs = 0L

        private var sustainHops = DEFAULT_OVERLOAD_SUSTAIN_HOPS
        private var downshiftHops = DEFAULT_OVERLOAD_SUSTAIN_HOPS / 2
        private var recoveryHops = 1
        private var emaAlpha = 1.0

        fun applyTiming(rate: Int, hop: Int) {
            val hopMs = MS_PER_SECOND * hop / rate
            fun hopsFor(ms: Double) =
                Math.ceil(ms / hopMs - HOP_COUNT_EPSILON).toInt().coerceAtLeast(1)
            val sustain = config.overloadSustainHops ?: hopsFor(OVERLOAD_SUSTAIN_MS)
            sustainHops = sustain
            downshiftHops = config.downshiftSustainHops ?: (sustain / 2).coerceAtLeast(1)
            recoveryHops = hopsFor(RECOVERY_MS)
            emaAlpha = 1.0 - Math.exp(-hopMs / EMA_TIME_CONSTANT_MS)
        }

        /** Returns true if caller should attempt downshifting to the fallback model. */
        fun recordHop(
            elapsedNs: Double,
            hopDurNs: Double,
            sampleRate: Int,
            isUsingFallback: Boolean,
            hasFallback: Boolean,
        ): Boolean {
            val instantRate = hopDurNs / elapsedNs
            emaElapsedNs =
                if (emaElapsedNs <= 0.0) {
                    elapsedNs
                } else {
                    (1.0 - emaAlpha) * emaElapsedNs + emaAlpha * elapsedNs
                }
            val smoothedRate = hopDurNs / emaElapsedNs
            val requiredRate = requiredProcessingRate(playbackSpeedProvider())
            if (config.logTelemetry) {
                recordTelemetry(elapsedNs.toLong(), hopDurNs, sampleRate, isUsingFallback)
            }

            var requestDownshift = false
            if (instantRate < requiredRate && smoothedRate < requiredRate) {
                slowHopStreak++
                recoveryHopStreak = 0
                if (!isUsingFallback && hasFallback) {
                    if (slowHopStreak >= downshiftHops) requestDownshift = true
                } else if (slowHopStreak >= sustainHops) {
                    markOverload(smoothedRate)
                }
            } else {
                slowHopStreak = (slowHopStreak - 2).coerceAtLeast(0)
                if (isMuted) {
                    recoveryHopStreak++
                    if (recoveryHopStreak >= recoveryHops || sustainHops <= 1) {
                        isMuted = false
                        recoveryHopStreak = 0
                    }
                }
            }
            return requestDownshift
        }

        fun markOverload(rate: Double) {
            if (isMuted) return
            isMuted = true
            val device =
                "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty {
                    "Unknown Device"
                }
            config.onOverloadMuted(device, rate)
        }

        fun resetStreaksAfterDownshift() {
            emaElapsedNs = 0.0
            slowHopStreak = 0
            recoveryHopStreak = 0
        }

        fun reset() {
            isMuted = false
            resetStreaksAfterDownshift()
        }

        private fun recordTelemetry(
            elapsedNs: Long,
            hopDurNs: Double,
            sampleRate: Int,
            isUsingFallback: Boolean,
        ) {
            telemetryHops++
            telemetryElapsedNs += elapsedNs
            if (elapsedNs > telemetryWorstHopNs) telemetryWorstHopNs = elapsedNs
            val audioNs = telemetryHops * hopDurNs
            if (audioNs < TELEMETRY_INTERVAL_NS) return
            val rate = audioNs / telemetryElapsedNs.coerceAtLeast(1L)
            val line =
                "model=${if (isUsingFallback) "fallback" else "primary"} " +
                    "$sampleRate Hz, hop ${"%.1f".format(hopDurNs / NS_PER_MS)} ms: " +
                    "${"%.1f".format(rate)}x realtime, worst hop " +
                    "${"%.2f".format(telemetryWorstHopNs / NS_PER_MS)} ms, " +
                    "muted=$isMuted"
            runCatching { Log.d(TAG, line) }
            telemetryHops = 0
            telemetryElapsedNs = 0L
            telemetryWorstHopNs = 0L
        }
    }

    private class StageManager(
        private val modelProvider: () -> SpeechModel,
        private val fallbackModelProvider: (() -> SpeechModel)?,
        private val gateProvider: (() -> SpeechGate)?,
        private val enablePostFilter: Boolean,
        private val onGeometryChanged: (Int, Int) -> Unit,
    ) {
        @Volatile
        var sampleRate: Int = DEFAULT_SAMPLE_RATE
            private set

        @Volatile
        var hopSize: Int = DEFAULT_HOP_SIZE
            private set

        @Volatile
        var isUsingFallbackModel: Boolean = false
            private set

        val hasFallbackProvider: Boolean
            get() = fallbackModelProvider != null

        val latencySamples: Int
            get() = (if (isUsingFallbackModel) fallbackStage else primaryStage)?.latencySamples ?: 0

        private var primaryModel: SpeechModel? = null
        private var fallbackModel: SpeechModel? = null
        private var resolvedGate: SpeechGate? = null
        private var primaryStage: SpeechStage? = null
        private var fallbackStage: ProcessingStage? = null
        var hasProcessedHops = false

        val hasInitializedPrimaryModel: Boolean
            get() = primaryModel != null

        fun ensureGeometry(): MusicRemovalGeometry {
            val loaded = ensurePrimaryModel()?.takeIf { it.isLoaded }
            if (loaded != null && loaded.sampleRate > 0 && loaded.hopSize > 0) {
                configureGeometry(loaded.sampleRate, loaded.hopSize)
            }
            return MusicRemovalGeometry(sampleRate, hopSize)
        }

        fun acquireStageOrFallback(onDownshifted: () -> Unit): ProcessingStage? {
            val current = ensureActiveStage()
            return if (current == null && tryDownshiftToFallback(onDownshifted)) {
                ensureActiveStage()
            } else {
                current
            }
        }

        fun tryDownshiftToFallback(onDownshifted: () -> Unit): Boolean {
            if (isUsingFallbackModel || !isGateAvailable()) return false
            val gate = resolvedGate
            runCatching { primaryModel?.reset() }
            runCatching { gate?.reset() }
            val stage = buildFallbackStage(gate)
            val succeeded = stage != null
            if (succeeded) {
                fallbackStage = stage
                isUsingFallbackModel = true
                onDownshifted()
            }
            return succeeded
        }

        fun reset() {
            if (hasProcessedHops) {
                hasProcessedHops = false
                primaryStage?.reset() ?: runCatching { primaryModel?.reset() }
                fallbackStage?.reset() ?: runCatching { fallbackModel?.reset() }
                runCatching { resolvedGate?.reset() }
            }
        }

        fun close() {
            if (hasProcessedHops) {
                hasProcessedHops = false
                runCatching { primaryModel?.close() }
                runCatching { fallbackModel?.close() }
                runCatching { resolvedGate?.close() }
            }
            primaryModel = null
            fallbackModel = null
            resolvedGate = null
            primaryStage = null
            fallbackStage = null
            isUsingFallbackModel = false
        }

        private fun ensureActiveStage(): ProcessingStage? =
            when {
                !isGateAvailable() -> null
                isUsingFallbackModel -> fallbackStage ?: buildFallbackStage(resolvedGate)
                else ->
                    primaryStage
                        ?: ensurePrimaryModel()
                            ?.takeIf { it.isLoaded }
                            ?.let { model ->
                                configureGeometry(model.sampleRate, model.hopSize)
                                SpeechStage(model, resolvedGate, enablePostFilter).also {
                                    primaryStage = it
                                }
                            }
            }

        private fun buildFallbackStage(gate: SpeechGate?): ProcessingStage? {
            val fallback =
                fallbackModel
                    ?: fallbackModelProvider?.let { provider ->
                        runCatching { provider() }.getOrNull()?.also { fallbackModel = it }
                    }
            return fallback
                ?.takeIf { it.isLoaded }
                ?.let { loaded ->
                    val stage = SpeechStage(loaded, gate, enablePostFilter)
                    if (loaded.sampleRate != sampleRate || loaded.hopSize != hopSize) {
                        GeometryAdaptedStage(
                            externalRate = sampleRate,
                            externalHopSize = hopSize,
                            internalRate = loaded.sampleRate,
                            internalHopSize = loaded.hopSize,
                            delegate = stage,
                        )
                    } else {
                        stage
                    }
                }
        }

        private fun ensurePrimaryModel(): SpeechModel? =
            primaryModel ?: runCatching { modelProvider() }.getOrNull()?.also { primaryModel = it }

        private fun isGateAvailable(): Boolean {
            val provider = gateProvider ?: return true
            val gate =
                resolvedGate ?: runCatching { provider() }.getOrNull()?.also { resolvedGate = it }
            return gate != null && gate.isLoaded
        }

        private fun configureGeometry(newRate: Int, newHop: Int) {
            if (newRate <= 0 || newHop <= 0) return
            if (newRate == sampleRate && newHop == hopSize) return
            sampleRate = newRate
            hopSize = newHop
            onGeometryChanged(newRate, newHop)
        }
    }

    private class SpeechStage(
        private val model: SpeechModel,
        private val vadGate: SpeechGate?,
        enablePostFilter: Boolean,
    ) : ProcessingStage {
        private val sampleRate = model.sampleRate
        private val hopSize = model.hopSize
        private val postFilter: SpeechPostFilter? =
            if (enablePostFilter && sampleRate > 0 && hopSize == POST_FILTER_HOP_SIZE) {
                SpeechPostFilter(sampleRate, model.latencySamples)
            } else {
                null
            }
        private val silentHopBuf = FloatArray(hopSize.coerceAtLeast(0))
        private val gateLookahead: GateLookahead? =
            vadGate
                ?.takeIf { hopSize > 0 && sampleRate > 0 }
                ?.prepare(sampleRate, hopSize)
                ?.takeIf { it > 0 }
                ?.let { GateLookahead(hops = it, hopSize = hopSize) }

        override val latencySamples: Int =
            model.latencySamples +
                (if (postFilter != null) SpeechPostFilter.OUTPUT_DELAY_SAMPLES else 0) +
                (gateLookahead?.hops ?: 0) * hopSize.coerceAtLeast(0)

        override val hopDurationNs: Double = model.hopDurationNs.toDouble()

        private val inputHistory = FloatArray(2 * hopSize.coerceAtLeast(0))
        private val alignedInputStart =
            (hopSize - model.latencySamples).coerceIn(0, hopSize.coerceAtLeast(0))

        override fun process(hop: FloatArray): FloatArray {
            require(hop.size == hopSize) { "Expected hop of $hopSize, got ${hop.size}" }
            val pf = postFilter
            return if (pf == null) processWithoutPostFilter(hop) else processWithPostFilter(pf, hop)
        }

        private fun processWithPostFilter(pf: SpeechPostFilter, hop: FloatArray): FloatArray {
            val preFiltered = pf.preFilterInput(hop)
            val rawEnhanced = model.processHop(preFiltered)
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
            val rawEnhanced = model.processHop(hop)
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

        override fun reset() {
            runCatching { model.reset() }
            postFilter?.reset()
            gateLookahead?.reset()
            inputHistory.fill(0f)
        }

        private fun computeFrameRatio(
            input: FloatArray,
            start: Int,
            enhancedHop: FloatArray,
        ): Float {
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
                samples[i] *= startGain + (endGain - startGain) * alpha
            }
        }
    }

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

    private class GeometryAdaptedStage(
        externalRate: Int,
        private val externalHopSize: Int,
        internalRate: Int,
        private val internalHopSize: Int,
        private val delegate: ProcessingStage,
    ) : ProcessingStage {
        private val inResampler = AudioResampler(externalRate, internalRate)
        private val outResampler = AudioResampler(internalRate, externalRate)
        private val internalHopBuf = FloatArray(internalHopSize)
        private var internalHopFill = 0

        private val fifoCapacity =
            maxOf(
                    externalHopSize * 4,
                    ((internalHopSize.toLong() * externalRate / internalRate).toInt() + 16) * 2,
                )
                .coerceAtLeast(externalHopSize * 2)
        private val outFifo = FloatArray(fifoCapacity)
        private var fifoRead = 0
        private var fifoWrite = 0
        private var fifoSize = 0
        private val externalOutBuf = FloatArray(externalHopSize)

        override val latencySamples: Int =
            ((delegate.latencySamples.toLong() * externalRate) / internalRate).toInt() +
                ((internalHopSize.toLong() * externalRate + internalRate - 1) / internalRate)
                    .toInt()

        override val hopDurationNs: Double =
            ((externalHopSize * 1_000_000_000L) / externalRate).toDouble()

        override fun process(hop: FloatArray): FloatArray {
            val inCount = inResampler.resample(hop, externalHopSize, mutateInput = false)
            val inBuf = inResampler.outputBuffer
            for (i in 0 until inCount) {
                internalHopBuf[internalHopFill++] = inBuf[i]
                if (internalHopFill == internalHopSize) {
                    internalHopFill = 0
                    val processedInternal = delegate.process(internalHopBuf)
                    val outCount =
                        outResampler.resample(
                            processedInternal,
                            processedInternal.size,
                            mutateInput = false,
                        )
                    pushFifo(outResampler.outputBuffer, outCount)
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

        override fun reset() {
            inResampler.reset()
            outResampler.reset()
            delegate.reset()
            internalHopFill = 0
            fifoRead = 0
            fifoWrite = 0
            fifoSize = 0
        }
    }

    companion object {
        internal const val TAG = "MusicRemovalEngine"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_HOP_SIZE = 512
        private const val POST_FILTER_HOP_SIZE = 512
        private const val FRAME_RATIO_EPS = 1e-6f
        private const val MAX_FRAME_RATIO = 1.5f

        const val MIN_PROCESSING_RATE = 1.5
        const val OVERLOAD_HEADROOM_FACTOR = 1.2
        const val MAX_SPEED_WITH_MUSIC_REMOVAL = 1.5f
        private const val MIN_PLAYBACK_SPEED = 0.25f

        private const val OVERLOAD_SUSTAIN_MS = 480.0
        const val DEFAULT_OVERLOAD_SUSTAIN_HOPS = 45
        private const val RECOVERY_MS = 160.0
        private const val HOP_COUNT_EPSILON = 1e-6
        private const val EMA_TIME_CONSTANT_MS = 65.6

        private const val TELEMETRY_INTERVAL_NS = 10_000_000_000.0
        private const val NS_PER_MS = 1_000_000.0
        private const val MS_PER_SECOND = 1000.0

        fun requiredProcessingRate(playbackSpeed: Float): Double {
            val clampedSpeed =
                playbackSpeed.coerceIn(MIN_PLAYBACK_SPEED, MAX_SPEED_WITH_MUSIC_REMOVAL)
            return clampedSpeed.toDouble() * OVERLOAD_HEADROOM_FACTOR
        }

        fun clampPlaybackSpeed(speed: Float, musicRemovalEnabled: Boolean): Float =
            if (musicRemovalEnabled) {
                speed.coerceAtMost(MAX_SPEED_WITH_MUSIC_REMOVAL)
            } else {
                speed
            }
    }
}
