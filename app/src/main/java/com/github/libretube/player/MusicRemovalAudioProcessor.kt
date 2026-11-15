package com.github.libretube.player

import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.github.libretube.BuildConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

private class SpscHopRingBuffer(
    val capacity: Int,
    hopSize: Int,
) {
    private class Slot(hopSize: Int) {
        val samples = FloatArray(hopSize)
        var enabled: Boolean = true
        var previousEnabled: Boolean? = null
        var generation: Long = 0L
    }

    private val slots = Array(capacity) { Slot(hopSize) }
    private val writeIdx = AtomicLong(0L)
    private val readIdx = AtomicLong(0L)

    val isEmpty: Boolean
        get() = writeIdx.get() <= readIdx.get()

    val size: Int
        get() = (writeIdx.get() - readIdx.get()).toInt().coerceAtLeast(0)

    fun offer(
        samples: FloatArray,
        enabled: Boolean,
        previousEnabled: Boolean?,
        generation: Long,
    ): Boolean {
        val w = writeIdx.get()
        val r = readIdx.get()
        if (w - r >= capacity) return false
        val slot = slots[(w % capacity).toInt()]
        System.arraycopy(samples, 0, slot.samples, 0, samples.size.coerceAtMost(slot.samples.size))
        slot.enabled = enabled
        slot.previousEnabled = previousEnabled
        slot.generation = generation
        writeIdx.set(w + 1L)
        return true
    }

    fun peekGeneration(): Long? {
        val r = readIdx.get()
        val w = writeIdx.get()
        if (r >= w) return null
        return slots[(r % capacity).toInt()].generation
    }

    fun pollInto(
        destSamples: FloatArray,
        onMeta: (enabled: Boolean, previousEnabled: Boolean?, generation: Long) -> Unit,
    ): Boolean {
        val r = readIdx.get()
        val w = writeIdx.get()
        if (r >= w) return false
        val slot = slots[(r % capacity).toInt()]
        System.arraycopy(
            slot.samples,
            0,
            destSamples,
            0,
            destSamples.size.coerceAtMost(slot.samples.size),
        )
        onMeta(slot.enabled, slot.previousEnabled, slot.generation)
        readIdx.set(r + 1L)
        return true
    }

    fun discardOne(): Boolean {
        val r = readIdx.get()
        val w = writeIdx.get()
        if (r >= w) return false
        readIdx.set(r + 1L)
        return true
    }
}

@OptIn(UnstableApi::class)
class MusicRemovalAudioProcessor(
    private val modelProvider: () -> SpeechModel,
    private val isMusicRemovalEnabled: () -> Boolean,
    private val playbackSpeedProvider: () -> Float = { 1.0f },
    private val fallbackModelProvider: (() -> SpeechModel)? = null,
    private val nanoTimeProvider: () -> Long = { System.nanoTime() },
    private val overloadSustainHops: Int? = null,
    private val downshiftSustainHops: Int? = null,
    private val ringBufferCapacityHops: Int = DEFAULT_RING_BUFFER_CAPACITY_HOPS,
    private val outputWaitTimeoutMs: Long = DEFAULT_OUTPUT_WAIT_TIMEOUT_MS,
    private val onOverloadMuteChanged: (Boolean) -> Unit = {},
    private val logTelemetry: Boolean = BuildConfig.DEBUG,
    private val onOverloadMuted: (device: String, measuredRate: Double) -> Unit = { device, rate ->
        runCatching {
            Log.w(
                TAG,
                "Music Removal overload on $device: measured rate ${"%.2f".format(rate)}x, muting audio",
            )
        }
    },
) : BaseAudioProcessor() {

    private val effectiveRingCapacity = ringBufferCapacityHops.coerceAtLeast(2)
    private var inputRing = SpscHopRingBuffer(effectiveRingCapacity, DEFAULT_HOP_SIZE)
    private var outputRing = SpscHopRingBuffer(effectiveRingCapacity, DEFAULT_HOP_SIZE)

    private val flushGeneration = AtomicLong(0L)
    private val workerHandledFlushGen = AtomicLong(0L)
    private val workerRunning = AtomicBoolean(false)
    private val workerInFlight = AtomicBoolean(false)
    private val overloadStateLock = Any()
    private val outputWaiterThread = AtomicReference<Thread?>(null)
    private val workerThreadRef = AtomicReference<Thread?>(null)

    @Volatile private var endOfStreamQueued = false

    @Volatile
    var droppedInputHops: Long = 0L
        private set

    // Worker-owned state (only accessed on the worker thread once started)
    @Volatile private var speechModel: SpeechModel? = null
    private var fallbackSpeechModel: SpeechModel? = null
    private var fallbackGeometryAdapter: SpeechEnhancementPipeline.FallbackGeometryAdapter? = null
    private var workerInHop = FloatArray(DEFAULT_HOP_SIZE)
    private var workerLastProcessedSample: Float = 0f
    private var workerHasProcessedHops = false
    private var pendingFallbackCrossfade = false
    private var silentHop = FloatArray(DEFAULT_HOP_SIZE)
    private var crossfadeBuf = FloatArray(DEFAULT_HOP_SIZE)
    private var emaElapsedNs: Double = 0.0
    private var slowHopStreak: Int = 0
    private var recoveryHopStreak: Int = 0
    private var telemetryHops = 0
    private var telemetryElapsedNs = 0L
    private var telemetryWorstHopNs = 0L

    // Overload timing in hops of the current model geometry (see applyOverloadTiming)
    @Volatile private var sustainHops = DEFAULT_OVERLOAD_SUSTAIN_HOPS
    @Volatile private var downshiftHops = DEFAULT_OVERLOAD_SUSTAIN_HOPS / 2
    @Volatile private var recoveryHops = 1
    @Volatile private var emaAlpha = 1.0

    // Producer-owned state (only accessed on the AudioProcessor caller thread)
    @Volatile
    var isMutedForOverload: Boolean = false
        private set

    @Volatile
    var isUsingFallbackModel: Boolean = false
        private set

    @Volatile private var targetSampleRate: Int = DEFAULT_SAMPLE_RATE
    @Volatile private var targetHopSize: Int = DEFAULT_HOP_SIZE
    private var resamplePhase = 0.0
    private var prevMonoSample = 0f
    private var hasPrevMonoSample = false
    private var hopAccumulator = FloatArray(DEFAULT_HOP_SIZE)
    private var monoInputBuf = FloatArray(4096)
    private var resampledBuf = FloatArray(8192)
    private var outResampledBuf = FloatArray(2048)
    private var drainHopBuf = FloatArray(DEFAULT_HOP_SIZE)
    private var outResamplePhase = 0.0
    private var prevOutSample = 0f
    private var hasPrevOutSample = false
    private var hopFill = 0
    private var producerLastEnabledState: Boolean? = null
    private var inputLowPass: ResamplingLowPass? = null
    private var outputLowPass: ResamplingLowPass? = null

    init {
        applyOverloadTiming(DEFAULT_SAMPLE_RATE, DEFAULT_HOP_SIZE)
    }

    /** Converts the overload timing from milliseconds to hops of the given model geometry. */
    private fun applyOverloadTiming(sampleRate: Int, hopSize: Int) {
        val hopMs = MS_PER_SECOND * hopSize / sampleRate
        fun hopsFor(ms: Double) = Math.ceil(ms / hopMs - HOP_COUNT_EPSILON).toInt().coerceAtLeast(1)
        val sustain = overloadSustainHops ?: hopsFor(OVERLOAD_SUSTAIN_MS)
        sustainHops = sustain
        downshiftHops = downshiftSustainHops ?: (sustain / 2).coerceAtLeast(1)
        recoveryHops = hopsFor(RECOVERY_MS)
        emaAlpha = 1.0 - Math.exp(-hopMs / EMA_TIME_CONSTANT_MS)
    }

    private fun ensureWorkerStarted() {
        if (workerRunning.get()) return
        if (!workerRunning.compareAndSet(false, true)) return
        val thread =
            Thread({ runWorkerLoop() }, "MusicRemoval-Worker").apply {
                isDaemon = true
            }
        workerThreadRef.set(thread)
        thread.start()
    }

    private fun stopWorker() {
        if (!workerRunning.compareAndSet(true, false)) {
            workerThreadRef.getAndSet(null)?.let { t ->
                LockSupport.unpark(t)
                runCatching { t.join(500L) }
            }
            return
        }
        val thread = workerThreadRef.getAndSet(null)
        if (thread != null) {
            LockSupport.unpark(thread)
            if (Thread.currentThread() !== thread) {
                runCatching { thread.join(500L) }
            }
        }
    }

    private fun runWorkerLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        try {
            while (workerRunning.get()) {
                handlePendingWorkerFlush()
                workerInFlight.set(true)

                var polledEnabled = true
                var polledPrevEnabled: Boolean? = null
                var polledGen = 0L
                val polled =
                    inputRing.pollInto(workerInHop) { enabled, prevEnabled, gen ->
                        polledEnabled = enabled
                        polledPrevEnabled = prevEnabled
                        polledGen = gen
                    }

                if (!polled) {
                    workerInFlight.set(false)
                    LockSupport.unpark(outputWaiterThread.get())
                    if (!workerRunning.get()) break
                    if (
                        !inputRing.isEmpty || workerHandledFlushGen.get() != flushGeneration.get()
                    ) {
                        continue
                    }
                    LockSupport.park()
                    continue
                }

                val currentGen = flushGeneration.get()
                if (polledGen != currentGen) {
                    handlePendingWorkerFlush()
                    continue
                }

                val processed =
                    processSingleHop(workerInHop, polledEnabled, polledPrevEnabled, polledGen)

                val postGen = flushGeneration.get()
                if (postGen != polledGen || !workerRunning.get()) {
                    // Discard stale hop from before seek/flush/reset
                    handlePendingWorkerFlush()
                    continue
                }

                while (workerRunning.get() && flushGeneration.get() == polledGen) {
                    if (outputRing.offer(processed, polledEnabled, polledPrevEnabled, polledGen)) {
                        LockSupport.unpark(outputWaiterThread.get())
                        break
                    }
                    LockSupport.parkNanos(1_000_000L)
                }
            }
        } finally {
            workerInFlight.set(false)
            if (workerHasProcessedHops) {
                workerHasProcessedHops = false
                runCatching { speechModel?.close() }
                runCatching { fallbackSpeechModel?.close() }
            }
            speechModel = null
            fallbackSpeechModel = null
            fallbackGeometryAdapter = null
            isUsingFallbackModel = false
            workerThreadRef.compareAndSet(Thread.currentThread(), null)
            workerRunning.set(false)
            LockSupport.unpark(outputWaiterThread.get())
        }
    }

    private fun handlePendingWorkerFlush() {
        val currentGen = flushGeneration.get()
        if (workerHandledFlushGen.get() == currentGen) return

        while (true) {
            val headGen = inputRing.peekGeneration() ?: break
            if (headGen < currentGen) {
                inputRing.discardOne()
            } else {
                break
            }
        }
        workerLastProcessedSample = 0f
        pendingFallbackCrossfade = false
        emaElapsedNs = 0.0
        slowHopStreak = 0
        recoveryHopStreak = 0
        if (workerHasProcessedHops) {
            workerHasProcessedHops = false
            runCatching { speechModel?.reset() }
            runCatching { fallbackSpeechModel?.reset() }
        }
        fallbackGeometryAdapter?.reset()
        workerHandledFlushGen.set(currentGen)
    }

    private fun hasPendingAsyncWork(): Boolean {
        return !inputRing.isEmpty || workerInFlight.get()
    }

    private fun configureGeometry(sampleRate: Int, hopSize: Int) {
        if (sampleRate <= 0 || hopSize <= 0) return
        if (sampleRate == targetSampleRate && hopSize == targetHopSize) return
        applyOverloadTiming(sampleRate, hopSize)
        targetSampleRate = sampleRate
        if (hopSize != targetHopSize) {
            targetHopSize = hopSize
            inputRing = SpscHopRingBuffer(effectiveRingCapacity, hopSize)
            outputRing = SpscHopRingBuffer(effectiveRingCapacity, hopSize)
            workerInHop = FloatArray(hopSize)
            silentHop = FloatArray(hopSize)
            crossfadeBuf = FloatArray(hopSize)
            hopAccumulator = FloatArray(hopSize)
            drainHopBuf = FloatArray(hopSize)
            hopFill = 0
        }
    }

    private fun shouldInitializeGeometryOnInput(isEnabled: Boolean): Boolean =
        isEnabled && speechModel == null && !workerRunning.get() && hopFill == 0

    private fun ensurePrimaryGeometry() {
        val model =
            speechModel ?: runCatching { modelProvider() }.getOrNull()?.also { speechModel = it }
        if (model.hasValidGeometry()) {
            val loaded = model ?: return
            configureGeometry(loaded.sampleRate, loaded.hopSize)
        }
    }

    private fun SpeechModel?.hasValidGeometry(): Boolean =
        this != null && isLoaded && sampleRate > 0 && hopSize > 0

    private fun SpeechModel.differsInGeometry(rate: Int, hop: Int): Boolean =
        sampleRate > 0 && hopSize > 0 && (sampleRate != rate || hopSize != hop)

    private fun ensureActiveModel(): SpeechModel? {
        if (isUsingFallbackModel) {
            return fallbackSpeechModel
                ?: fallbackModelProvider?.let { provider ->
                    runCatching { provider() }
                        .getOrNull()
                        ?.also { model ->
                            fallbackSpeechModel = model
                        }
                }
        }
        return speechModel
            ?: runCatching { modelProvider() }
                .getOrNull()
                ?.also { model ->
                    speechModel = model
                }
    }

    private fun tryDownshiftToFallback(): Boolean {
        if (isUsingFallbackModel) return false
        val provider = fallbackModelProvider ?: return false
        val fallback =
            fallbackSpeechModel
                ?: runCatching { provider() }
                    .getOrNull()
                    ?.also {
                        fallbackSpeechModel = it
                    }
        if (fallback == null || !fallback.isLoaded) {
            return false
        }
        isUsingFallbackModel = true
        pendingFallbackCrossfade = true
        emaElapsedNs = 0.0
        slowHopStreak = 0
        recoveryHopStreak = 0
        runCatching { speechModel?.reset() }
        fallbackGeometryAdapter =
            if (fallback.differsInGeometry(targetSampleRate, targetHopSize)) {
                SpeechEnhancementPipeline.FallbackGeometryAdapter(
                    externalRate = targetSampleRate,
                    externalHopSize = targetHopSize,
                    internalRate = fallback.sampleRate,
                    internalHopSize = fallback.hopSize,
                )
            } else {
                null
            }
        return true
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!isMusicRemovalEnabled()) {
            return inputAudioFormat
        }
        ensurePrimaryGeometry()
        return AudioFormat(targetSampleRate, 1, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val isEnabled = isMusicRemovalEnabled()
        if (
            !isEnabled &&
                producerLastEnabledState != true &&
                hopFill == 0 &&
                inputRing.isEmpty &&
                outputRing.isEmpty &&
                !workerInFlight.get() &&
                inputAudioFormat.sampleRate == outputAudioFormat.sampleRate &&
                inputAudioFormat.channelCount == outputAudioFormat.channelCount &&
                inputAudioFormat.encoding == outputAudioFormat.encoding
        ) {
            producerLastEnabledState = false
            clearOverloadMute()
            val outBuffer = replaceOutputBuffer(inputBuffer.remaining())
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        if (shouldInitializeGeometryOnInput(isEnabled)) {
            ensurePrimaryGeometry()
        }
        ensureWorkerStarted()

        val inChannelCount = inputAudioFormat.channelCount
        val inSampleRate = inputAudioFormat.sampleRate
        val inEncoding = inputAudioFormat.encoding
        val bytesPerSample = if (inEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = inChannelCount * bytesPerSample
        val frameCount = inputBuffer.remaining() / frameSize
        if (frameCount <= 0) return

        if (monoInputBuf.size < frameCount) {
            monoInputBuf = FloatArray(frameCount * 2)
        }
        val invChannels = 1f / inChannelCount
        if (inEncoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until frameCount) {
                var sum = 0f
                for (ch in 0 until inChannelCount) {
                    sum += inputBuffer.float.coerceIn(-1f, 1f)
                }
                monoInputBuf[i] = sum * invChannels
            }
        } else {
            val scale = invChannels / 32768f
            for (i in 0 until frameCount) {
                var sum = 0
                for (ch in 0 until inChannelCount) {
                    sum += inputBuffer.short.toInt()
                }
                monoInputBuf[i] = sum * scale
            }
        }

        val resampledCount = resampleToModelRate(monoInputBuf, frameCount, inSampleRate)
        val hopLen = targetHopSize
        val gen = flushGeneration.get()
        var pushedAny = false

        for (i in 0 until resampledCount) {
            hopAccumulator[hopFill++] = resampledBuf[i]
            if (hopFill == hopLen) {
                hopFill = 0
                val prevEnabled = producerLastEnabledState
                producerLastEnabledState = isEnabled
                if (inputRing.offer(hopAccumulator, isEnabled, prevEnabled, gen)) {
                    pushedAny = true
                } else {
                    droppedInputHops++
                }
            }
        }

        if (pushedAny) {
            LockSupport.unpark(workerThreadRef.get())
        }
    }

    override fun getOutput(): ByteBuffer {
        if (hasPendingOutput()) {
            return super.getOutput()
        }

        if (outputRing.isEmpty && outputWaitTimeoutMs > 0L && hasPendingAsyncWork()) {
            val deadlineNs = System.nanoTime() + outputWaitTimeoutMs * 1_000_000L
            outputWaiterThread.set(Thread.currentThread())
            try {
                while (outputRing.isEmpty && hasPendingAsyncWork()) {
                    val remainingNs = deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) break
                    LockSupport.parkNanos(remainingNs.coerceAtMost(5_000_000L))
                }
            } finally {
                outputWaiterThread.compareAndSet(Thread.currentThread(), null)
            }
        }

        val readyHops = outputRing.size
        if (readyHops <= 0) {
            return super.getOutput()
        }

        val hopLen = targetHopSize
        val outChannelCount = outputAudioFormat.channelCount.coerceAtLeast(1)
        val outSampleRate = outputAudioFormat.sampleRate.takeIf { it > 0 } ?: targetSampleRate
        val outEncoding = outputAudioFormat.encoding
        val outBytesPerSample = if (outEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val maxFramesPerHop =
            if (outSampleRate == targetSampleRate) {
                hopLen
            } else {
                ((hopLen.toDouble() * outSampleRate.toDouble() / targetSampleRate.toDouble())
                    .toInt()) + 4
            }

        val outBuffer =
            replaceOutputBuffer(readyHops * maxFramesPerHop * outChannelCount * outBytesPerSample)
        val currentGen = flushGeneration.get()
        var drained = 0
        while (drained < readyHops) {
            var hopGen = 0L
            if (!outputRing.pollInto(drainHopBuf) { _, _, gen -> hopGen = gen }) {
                break
            }
            drained++
            if (hopGen != currentGen) continue

            val outFrames: FloatArray
            val outFrameCount: Int
            if (outSampleRate == targetSampleRate) {
                outFrames = drainHopBuf
                outFrameCount = hopLen
            } else {
                outFrameCount = resampleFromModelRate(drainHopBuf, hopLen, outSampleRate)
                outFrames = outResampledBuf
            }

            if (outEncoding == C.ENCODING_PCM_FLOAT) {
                for (j in 0 until outFrameCount) {
                    val s = outFrames[j].coerceIn(-1f, 1f)
                    for (ch in 0 until outChannelCount) {
                        outBuffer.putFloat(s)
                    }
                }
            } else {
                for (j in 0 until outFrameCount) {
                    val s = outFrames[j]
                    val pcm16 =
                        when {
                            s >= 1f -> Short.MAX_VALUE
                            s <= -1f -> Short.MIN_VALUE
                            else -> (s * 32767f).toInt().toShort()
                        }
                    for (ch in 0 until outChannelCount) {
                        outBuffer.putShort(pcm16)
                    }
                }
            }
        }
        outBuffer.flip()
        LockSupport.unpark(workerThreadRef.get())
        return super.getOutput()
    }

    override fun onQueueEndOfStream() {
        endOfStreamQueued = true
        LockSupport.unpark(workerThreadRef.get())
    }

    override fun isEnded(): Boolean {
        return super.isEnded() && !hasPendingAsyncWork() && outputRing.isEmpty
    }

    private fun processSingleHop(
        hop: FloatArray,
        enabled: Boolean,
        previousEnabled: Boolean?,
        polledGen: Long,
    ): FloatArray {
        if (!enabled) {
            clearOverloadForGeneration(polledGen)
            slowHopStreak = 0
            if (previousEnabled == true) {
                // Fading from ON (last processed sample) to OFF (passthrough hop) without invoking
                // model
                val faded = crossfadeFromLastSample(hop)
                if (faded.isNotEmpty()) {
                    workerLastProcessedSample = faded[faded.size - 1]
                }
                return faded
            }
            if (hop.isNotEmpty()) {
                workerLastProcessedSample = hop[hop.size - 1]
            }
            return hop
        }

        val processed = runModelOnHop(hop, polledGen)
        val result =
            if (previousEnabled == false) {
                // Fading from OFF (passthrough hop) to ON (processed)
                crossfade(from = hop, to = processed)
            } else {
                processed
            }
        if (result.isNotEmpty()) {
            workerLastProcessedSample = result[result.size - 1]
        }
        return result
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

    private fun crossfade(from: FloatArray, to: FloatArray): FloatArray =
        crossfadeIntoBuffer(from.size, { i -> from[i] }, to)

    private fun crossfadeFromLastSample(to: FloatArray): FloatArray {
        val startSample = workerLastProcessedSample
        return crossfadeIntoBuffer(to.size, { startSample }, to)
    }

    private fun clearOverloadMute(expectedGen: Long? = null) {
        if (!isMutedForOverload) return
        var notifyClear = false
        synchronized(overloadStateLock) {
            val staleGen =
                expectedGen != null &&
                    (flushGeneration.get() != expectedGen || !workerRunning.get())
            if (!staleGen && isMutedForOverload) {
                isMutedForOverload = false
                notifyClear = true
            }
        }
        if (notifyClear) {
            onOverloadMuteChanged(false)
        }
    }

    private fun clearOverloadForGeneration(polledGen: Long) {
        clearOverloadMute(expectedGen = polledGen)
    }

    private fun markOverload(rate: Double, polledGen: Long) {
        var shouldNotify = false
        synchronized(overloadStateLock) {
            if (flushGeneration.get() != polledGen || !workerRunning.get()) return
            if (!isMutedForOverload) {
                isMutedForOverload = true
                shouldNotify = true
            }
        }
        if (shouldNotify) {
            onOverloadMuteChanged(true)
            val device =
                "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty {
                    "Unknown Device"
                }
            onOverloadMuted(device, rate)
        }
    }

    private fun runModelOnHop(hop: FloatArray, polledGen: Long): FloatArray {
        var model = ensureActiveModel()
        if (model == null || !model.isLoaded) {
            if (!isUsingFallbackModel && tryDownshiftToFallback()) {
                model = ensureActiveModel()
            }
            if (model == null || !model.isLoaded) {
                markOverload(0.0, polledGen)
                return silentHop
            }
        }

        return try {
            workerHasProcessedHops = true
            val wasFallbackPending = pendingFallbackCrossfade
            pendingFallbackCrossfade = false

            val startNs = nanoTimeProvider()
            val adapter = if (isUsingFallbackModel) fallbackGeometryAdapter else null
            val rawProcessed =
                if (adapter != null) {
                    adapter.process(hop, model)
                } else {
                    model.processHop(hop)
                }
            val elapsedNs = (nanoTimeProvider() - startNs).coerceAtLeast(1L).toDouble()
            val hopDurNs =
                if (adapter != null && targetSampleRate > 0 && targetHopSize > 0) {
                    ((targetHopSize * 1_000_000_000L) / targetSampleRate).toDouble()
                } else {
                    model.hopDurationNs.toDouble()
                }
            val instantRate = hopDurNs / elapsedNs

            val processed =
                if (wasFallbackPending) {
                    crossfadeFromLastSample(rawProcessed)
                } else {
                    rawProcessed
                }

            emaElapsedNs =
                if (emaElapsedNs <= 0.0) {
                    elapsedNs
                } else {
                    (1.0 - emaAlpha) * emaElapsedNs + emaAlpha * elapsedNs
                }
            val smoothedRate = hopDurNs / emaElapsedNs
            val requiredRate = requiredProcessingRate(playbackSpeedProvider())
            if (logTelemetry) {
                recordTelemetry(elapsedNs.toLong(), hopDurNs)
            }

            if (instantRate < requiredRate && smoothedRate < requiredRate) {
                slowHopStreak++
                recoveryHopStreak = 0
                if (!isUsingFallbackModel && fallbackModelProvider != null) {
                    if (slowHopStreak >= downshiftHops && !tryDownshiftToFallback()) {
                        markOverload(smoothedRate, polledGen)
                    }
                } else if (slowHopStreak >= sustainHops) {
                    markOverload(smoothedRate, polledGen)
                }
            } else {
                slowHopStreak = (slowHopStreak - 2).coerceAtLeast(0)
                if (isMutedForOverload) {
                    recoveryHopStreak++
                    if (recoveryHopStreak >= recoveryHops || sustainHops <= 1) {
                        clearOverloadForGeneration(polledGen)
                        recoveryHopStreak = 0
                    }
                }
            }

            if (isMutedForOverload) {
                silentHop
            } else {
                processed
            }
        } catch (_: Exception) {
            if (!isUsingFallbackModel && tryDownshiftToFallback()) {
                return runModelOnHop(hop, polledGen)
            }
            markOverload(0.0, polledGen)
            silentHop
        }
    }

    /** Logs the measured processing rate every few seconds of audio (debug builds only). */
    private fun recordTelemetry(elapsedNs: Long, hopDurNs: Double) {
        telemetryHops++
        telemetryElapsedNs += elapsedNs
        if (elapsedNs > telemetryWorstHopNs) telemetryWorstHopNs = elapsedNs
        val audioNs = telemetryHops * hopDurNs
        if (audioNs < TELEMETRY_INTERVAL_NS) return
        val rate = audioNs / telemetryElapsedNs.coerceAtLeast(1L)
        val line =
            "model=${if (isUsingFallbackModel) "fallback" else "primary"} " +
                "$targetSampleRate Hz, hop ${"%.1f".format(hopDurNs / NS_PER_MS)} ms: " +
                "${"%.1f".format(rate)}x realtime, worst hop " +
                "${"%.2f".format(telemetryWorstHopNs / NS_PER_MS)} ms, " +
                "dropped input hops $droppedInputHops, muted=$isMutedForOverload"
        runCatching { Log.d(TAG, line) }
        telemetryHops = 0
        telemetryElapsedNs = 0L
        telemetryWorstHopNs = 0L
    }

    /** Reuses [current] if it already converts between these two rates, else builds a new one. */
    private fun lowPassFor(
        current: ResamplingLowPass?,
        highRate: Int,
        lowRate: Int,
    ): ResamplingLowPass? =
        current?.takeIf {
            it.sampleRate == highRate && it.cutoffHz == ResamplingLowPass.CUTOFF_FRACTION * lowRate
        } ?: ResamplingLowPass.between(highRate, lowRate)

    private fun resampleToModelRate(monoInput: FloatArray, inCount: Int, inSampleRate: Int): Int {
        if (inCount <= 0) return 0
        if (inSampleRate == targetSampleRate) {
            if (resampledBuf.size < inCount) {
                resampledBuf = FloatArray(inCount * 2)
            }
            System.arraycopy(monoInput, 0, resampledBuf, 0, inCount)
            return inCount
        }

        if (inSampleRate > targetSampleRate) {
            // Decimating: remove what would alias into the model's band first
            val lowPass = lowPassFor(inputLowPass, inSampleRate, targetSampleRate)
            inputLowPass = lowPass
            lowPass?.processInPlace(monoInput, inCount)
        }

        val step = inSampleRate.toDouble() / targetSampleRate.toDouble()
        val maxOut = ((inCount / step).toInt()) + 4
        if (resampledBuf.size < maxOut) {
            resampledBuf = FloatArray(maxOut * 2)
        }
        var outCount = 0

        if (!hasPrevMonoSample) {
            prevMonoSample = monoInput[0]
            hasPrevMonoSample = true
        }

        while (resamplePhase < inCount) {
            val idx = resamplePhase.toInt()
            val frac = (resamplePhase - idx).toFloat()
            val s0 = if (idx == 0) prevMonoSample else monoInput[idx - 1]
            val s1 = monoInput[idx]
            resampledBuf[outCount++] = s0 + (s1 - s0) * frac
            resamplePhase += step
        }

        resamplePhase -= inCount
        prevMonoSample = monoInput[inCount - 1]
        return outCount
    }

    private fun resampleFromModelRate(
        monoInput: FloatArray,
        inCount: Int,
        outSampleRate: Int,
    ): Int {
        if (inCount <= 0) return 0
        val step = targetSampleRate.toDouble() / outSampleRate.toDouble()
        val maxOut = ((inCount / step).toInt()) + 4
        if (outResampledBuf.size < maxOut) {
            outResampledBuf = FloatArray(maxOut * 2)
        }
        var outCount = 0

        if (!hasPrevOutSample) {
            prevOutSample = monoInput[0]
            hasPrevOutSample = true
        }

        while (outResamplePhase < inCount) {
            val idx = outResamplePhase.toInt()
            val frac = (outResamplePhase - idx).toFloat()
            val s0 = if (idx == 0) prevOutSample else monoInput[idx - 1]
            val s1 = monoInput[idx]
            outResampledBuf[outCount++] = s0 + (s1 - s0) * frac
            outResamplePhase += step
        }

        outResamplePhase -= inCount
        prevOutSample = monoInput[inCount - 1]

        if (outSampleRate > targetSampleRate) {
            // Interpolating: remove the images linear interpolation leaves above the model band
            val lowPass = lowPassFor(outputLowPass, outSampleRate, targetSampleRate)
            outputLowPass = lowPass
            lowPass?.processInPlace(outResampledBuf, outCount)
        }
        return outCount
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onFlush() {
        var notifyClear = false
        synchronized(overloadStateLock) {
            flushGeneration.incrementAndGet()
            if (isMutedForOverload) {
                isMutedForOverload = false
                notifyClear = true
            }
        }
        if (notifyClear) {
            onOverloadMuteChanged(false)
        }
        endOfStreamQueued = false
        resamplePhase = 0.0
        hasPrevMonoSample = false
        outResamplePhase = 0.0
        hasPrevOutSample = false
        hopFill = 0
        producerLastEnabledState = null
        inputLowPass?.reset()
        outputLowPass?.reset()
        while (outputRing.discardOne()) {
            // Drain stale pre-seek output hops immediately on caller thread
        }
        LockSupport.unpark(workerThreadRef.get())
    }

    override fun onReset() {
        onFlush()
        stopWorker()
        while (outputRing.discardOne()) {
            // Ensure outputRing is empty after worker exit
        }
        while (inputRing.discardOne()) {
            // Ensure inputRing is empty after worker exit
        }
        clearOverloadMute()
    }

    companion object {
        private const val TAG = "MusicRemovalProcessor"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_HOP_SIZE = 512
        const val MIN_PROCESSING_RATE = 1.5
        const val OVERLOAD_HEADROOM_FACTOR = 1.2
        const val MAX_SPEED_WITH_MUSIC_REMOVAL = 1.5f
        private const val MIN_PLAYBACK_SPEED = 0.25f

        const val DEFAULT_RING_BUFFER_CAPACITY_HOPS = 64
        const val DEFAULT_OUTPUT_WAIT_TIMEOUT_MS = 100L

        // Require ~480 ms of sustained slow rate before muting for overload, preventing single-hop
        // GC or scheduler spikes from dropping audio frames. Converted to hops per model geometry:
        // 45 hops at 48 kHz / 512, 15 hops at 16 kHz / 512.
        private const val OVERLOAD_SUSTAIN_MS = 480.0
        const val DEFAULT_OVERLOAD_SUSTAIN_HOPS = 45
        private const val RECOVERY_MS = 160.0
        // Keeps an exact multiple (480 ms / 10.667 ms = 45.0) from rounding up to the next hop
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

        fun clampPlaybackSpeed(speed: Float, musicRemovalEnabled: Boolean): Float {
            return if (musicRemovalEnabled) {
                speed.coerceAtMost(MAX_SPEED_WITH_MUSIC_REMOVAL)
            } else {
                speed
            }
        }
    }
}
