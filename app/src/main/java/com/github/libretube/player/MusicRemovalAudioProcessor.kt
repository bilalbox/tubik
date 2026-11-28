package com.github.libretube.player

import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

/** One hop of audio plus what the producer, worker and drain need to know about it. */
private class HopSlot(hopSize: Int) {
    val samples = FloatArray(hopSize)
    var enabled: Boolean = true
    var previousEnabled: Boolean? = null
    var generation: Long = 0L

    /** Leading samples of [samples] that are played; the rest is internal end-of-stream padding. */
    var validSamples: Int = hopSize

    /** Input only: the zero-padded partial hop queued at end of stream. */
    var isEndOfStream: Boolean = false

    /** Output only: the hop is Overload Mute silence. */
    var isMuted: Boolean = false

    /** Output only: the hop fades in from silence, so no earlier audio may bleed into it. */
    var startsFromSilence: Boolean = false
}

private class SpscHopRingBuffer(
    val capacity: Int,
    hopSize: Int,
) {
    private val slots = Array(capacity) { HopSlot(hopSize) }
    private val writeIdx = AtomicLong(0L)
    private val readIdx = AtomicLong(0L)

    val isEmpty: Boolean
        get() = writeIdx.get() <= readIdx.get()

    val size: Int
        get() = (writeIdx.get() - readIdx.get()).toInt().coerceAtLeast(0)

    /** Copies [samples] into the next free slot, letting [describe] set its metadata. */
    inline fun offer(samples: FloatArray, describe: HopSlot.() -> Unit): Boolean {
        val w = writeIdx.get()
        val r = readIdx.get()
        if (w - r >= capacity) return false
        val slot = slots[(w % capacity).toInt()]
        System.arraycopy(samples, 0, slot.samples, 0, samples.size.coerceAtMost(slot.samples.size))
        slot.validSamples = slot.samples.size
        slot.isEndOfStream = false
        slot.isMuted = false
        slot.startsFromSilence = false
        slot.describe()
        writeIdx.set(w + 1L)
        return true
    }

    fun peekGeneration(): Long? {
        val r = readIdx.get()
        val w = writeIdx.get()
        if (r >= w) return null
        return slots[(r % capacity).toInt()].generation
    }

    /** Copies the oldest hop into [destSamples] and hands its metadata to [read]. */
    inline fun pollInto(destSamples: FloatArray, read: HopSlot.() -> Unit): Boolean {
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
        slot.read()
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

/**
 * ExoPlayer [BaseAudioProcessor] adapter that bridges PCM buffers across a non-blocking SPSC
 * ring-buffer worker thread into [MusicRemovalEngine].
 */
@OptIn(UnstableApi::class)
class MusicRemovalAudioProcessor(
    private val engine: MusicRemovalEngine,
    private val isMusicRemovalEnabled: () -> Boolean,
    private val onOverloadMuteChanged: (Boolean) -> Unit = {},
    private val ringBufferCapacityHops: Int = DEFAULT_RING_BUFFER_CAPACITY_HOPS,
    private val outputWaitTimeoutMs: Long = DEFAULT_OUTPUT_WAIT_TIMEOUT_MS,
) : BaseAudioProcessor() {

    private val effectiveRingCapacity = ringBufferCapacityHops.coerceAtLeast(2)
    @Volatile private var inputRing = SpscHopRingBuffer(effectiveRingCapacity, engine.hopSize)
    @Volatile private var outputRing = SpscHopRingBuffer(effectiveRingCapacity, engine.hopSize)

    private val flushGeneration = AtomicLong(0L)
    private val workerHandledFlushGen = AtomicLong(0L)
    private val nextWorkerId = AtomicLong(0L)
    private val activeWorkerId = AtomicLong(0L)
    private val workerRunning = AtomicBoolean(false)
    private val workerInFlight = AtomicBoolean(false)
    private val workerLifecycleLock = Any()
    private val workerExecutionLock = ReentrantLock()
    private val overloadStateLock = Any()
    private val outputWaiterThread = AtomicReference<Thread?>(null)
    private val workerThreadRef = AtomicReference<Thread?>(null)

    @Volatile private var endOfStreamHopPending = false

    @Volatile
    var droppedInputHops: Long = 0L
        private set

    @Volatile
    var isMutedForOverload: Boolean = false
        private set

    val isUsingFallbackModel: Boolean
        get() = engine.isUsingFallbackModel

    @Volatile private var targetSampleRate: Int = engine.sampleRate
    @Volatile private var targetHopSize: Int = engine.hopSize

    private var workerInHop = FloatArray(targetHopSize)
    private var hopAccumulator = FloatArray(targetHopSize)
    private var drainHopBuf = FloatArray(targetHopSize)
    private var monoInputBuf = FloatArray(INITIAL_MONO_CAPACITY)
    private var hopFill = 0
    private var producerLastEnabledState: Boolean? = null
    private var inputResampler: AudioResampler? = null
    private var outputResampler: AudioResampler? = null

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
        trySyncEngineGeometry()
        return AudioFormat(targetSampleRate, 1, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val isEnabled = isMusicRemovalEnabled()
        if (canFastPassthrough(isEnabled)) {
            producerLastEnabledState = false
            clearOverloadMute()
            val outBuffer = replaceOutputBuffer(inputBuffer.remaining())
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        if (isEnabled && hopFill == 0) {
            if (!engine.hasInitializedPrimaryModel && !workerRunning.get()) {
                trySyncEngineGeometry()
            }
        }
        ensureWorkerStarted()

        val frameCount = downmixToMono(inputBuffer)
        if (frameCount <= 0) return

        val resampler =
            AudioResampler.reuseOrCreate(
                inputResampler,
                inputAudioFormat.sampleRate,
                targetSampleRate,
            )
        inputResampler = resampler
        val resampledCount = resampler.resample(monoInputBuf, frameCount, mutateInput = true)
        enqueueHops(resampler.outputBuffer, resampledCount, isEnabled)
    }

    override fun getOutput(): ByteBuffer {
        if (hasPendingOutput()) {
            return super.getOutput()
        }
        offerEndOfStreamHop()
        awaitOutputIfConfigured()

        val readyHops = outputRing.size
        if (readyHops <= 0) {
            return super.getOutput()
        }

        drainOutputRingToBuffer(readyHops)
        LockSupport.unpark(workerThreadRef.get())
        return super.getOutput()
    }

    override fun onQueueEndOfStream() {
        endOfStreamHopPending = hopFill > 0 || producerLastEnabledState == true
        offerEndOfStreamHop()
        LockSupport.unpark(workerThreadRef.get())
    }

    override fun isEnded(): Boolean {
        offerEndOfStreamHop()
        return super.isEnded() &&
            !endOfStreamHopPending &&
            !hasPendingAsyncWork() &&
            outputRing.isEmpty
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
        endOfStreamHopPending = false
        hopFill = 0
        producerLastEnabledState = null
        inputResampler?.reset()
        outputResampler?.reset()
        while (outputRing.discardOne()) {
            // Drain stale pre-seek output hops immediately on caller thread
        }
        LockSupport.unpark(workerThreadRef.get())
    }

    override fun onReset() {
        onFlush()
        val workerStopped = stopWorker()
        while (outputRing.discardOne()) {
            // Ensure outputRing is empty after worker exit
        }
        if (workerStopped && workerExecutionLock.tryLock()) {
            try {
                while (inputRing.discardOne()) {
                    // Ensure inputRing is empty after worker exit
                }
            } finally {
                workerExecutionLock.unlock()
            }
        }
        clearOverloadMute()
    }

    private fun canFastPassthrough(isEnabled: Boolean): Boolean =
        !isEnabled &&
            producerLastEnabledState != true &&
            hopFill == 0 &&
            inputRing.isEmpty &&
            outputRing.isEmpty &&
            !workerInFlight.get() &&
            inputAudioFormat.sampleRate == outputAudioFormat.sampleRate &&
            inputAudioFormat.channelCount == outputAudioFormat.channelCount &&
            inputAudioFormat.encoding == outputAudioFormat.encoding

    private fun downmixToMono(inputBuffer: ByteBuffer): Int {
        val inChannelCount = inputAudioFormat.channelCount
        val inEncoding = inputAudioFormat.encoding
        val bytesPerSample = if (inEncoding == C.ENCODING_PCM_FLOAT) FLOAT_BYTES else SHORT_BYTES
        val frameCount = inputBuffer.remaining() / (inChannelCount * bytesPerSample)
        if (frameCount <= 0) return 0

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
            val scale = invChannels / PCM16_INPUT_SCALE
            for (i in 0 until frameCount) {
                var sum = 0
                for (ch in 0 until inChannelCount) {
                    sum += inputBuffer.short.toInt()
                }
                monoInputBuf[i] = sum * scale
            }
        }
        return frameCount
    }

    private fun enqueueHops(samples: FloatArray, count: Int, isEnabled: Boolean) {
        val hopLen = targetHopSize
        val gen = flushGeneration.get()
        var pushedAny = false

        for (i in 0 until count) {
            hopAccumulator[hopFill++] = samples[i]
            if (hopFill == hopLen) {
                hopFill = 0
                val prevEnabled = producerLastEnabledState
                producerLastEnabledState = isEnabled
                val offered =
                    inputRing.offer(hopAccumulator) {
                        enabled = isEnabled
                        previousEnabled = prevEnabled
                        generation = gen
                    }
                if (offered) {
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

    private fun awaitOutputIfConfigured() {
        if (!outputRing.isEmpty || outputWaitTimeoutMs <= 0L || !hasPendingAsyncWork()) return
        val deadlineNs = System.nanoTime() + outputWaitTimeoutMs * NS_PER_MS_LONG
        outputWaiterThread.set(Thread.currentThread())
        try {
            while (outputRing.isEmpty && hasPendingAsyncWork()) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) break
                LockSupport.parkNanos(remainingNs.coerceAtMost(WAIT_SLICE_NS))
            }
        } finally {
            outputWaiterThread.compareAndSet(Thread.currentThread(), null)
        }
    }

    private fun drainOutputRingToBuffer(readyHops: Int) {
        val hopLen = targetHopSize
        val outChannelCount = outputAudioFormat.channelCount.coerceAtLeast(1)
        val outSampleRate = outputAudioFormat.sampleRate.takeIf { it > 0 } ?: targetSampleRate
        val outEncoding = outputAudioFormat.encoding
        val outBytesPerSample =
            if (outEncoding == C.ENCODING_PCM_FLOAT) FLOAT_BYTES else SHORT_BYTES
        val maxFramesPerHop =
            if (outSampleRate == targetSampleRate) {
                hopLen
            } else {
                ((hopLen.toDouble() * outSampleRate.toDouble() / targetSampleRate.toDouble())
                    .toInt()) + OUTPUT_SLACK_SAMPLES
            }

        val outBuffer =
            replaceOutputBuffer(readyHops * maxFramesPerHop * outChannelCount * outBytesPerSample)
        val currentGen = flushGeneration.get()
        var drained = 0
        while (drained < readyHops) {
            var hopGen = 0L
            var hopValid = 0
            var hopMuted = false
            var hopFadesIn = false
            val polled =
                outputRing.pollInto(drainHopBuf) {
                    hopGen = generation
                    hopValid = validSamples.coerceAtMost(hopLen)
                    hopMuted = isMuted
                    hopFadesIn = startsFromSilence
                }
            if (!polled) break
            drained++
            if (hopGen != currentGen) continue

            val (outFrames, outFrameCount) =
                prepareOutputFrames(hopValid, outSampleRate, hopMuted, hopFadesIn)
            encodeFrames(outBuffer, outFrames, outFrameCount, outChannelCount, outEncoding)
        }
        outBuffer.flip()
    }

    private fun prepareOutputFrames(
        hopValid: Int,
        outSampleRate: Int,
        hopMuted: Boolean,
        hopFadesIn: Boolean,
    ): Pair<FloatArray, Int> {
        if (hopMuted || hopFadesIn) {
            outputResampler?.resetToSilence()
        }
        val frames: FloatArray
        val frameCount: Int
        if (outSampleRate == targetSampleRate) {
            frames = drainHopBuf
            frameCount = hopValid
        } else {
            val resampler =
                AudioResampler.reuseOrCreate(outputResampler, targetSampleRate, outSampleRate)
            outputResampler = resampler
            if (hopMuted || hopFadesIn) {
                resampler.resetToSilence()
            }
            frameCount = resampler.resample(drainHopBuf, hopValid, mutateInput = false)
            frames = resampler.outputBuffer
        }
        if (hopMuted) frames.fill(0f, 0, frameCount)
        return frames to frameCount
    }

    private fun encodeFrames(
        outBuffer: ByteBuffer,
        frames: FloatArray,
        frameCount: Int,
        outChannelCount: Int,
        outEncoding: Int,
    ) {
        if (outEncoding == C.ENCODING_PCM_FLOAT) {
            for (j in 0 until frameCount) {
                val s = frames[j].coerceIn(-1f, 1f)
                for (ch in 0 until outChannelCount) {
                    outBuffer.putFloat(s)
                }
            }
        } else {
            for (j in 0 until frameCount) {
                val s = frames[j]
                val pcm16 =
                    when {
                        s >= 1f -> Short.MAX_VALUE
                        s <= -1f -> Short.MIN_VALUE
                        else -> (s * PCM16_OUTPUT_SCALE).toInt().toShort()
                    }
                for (ch in 0 until outChannelCount) {
                    outBuffer.putShort(pcm16)
                }
            }
        }
    }

    private fun offerEndOfStreamHop() {
        if (!endOfStreamHopPending) return
        ensureWorkerStarted()
        val validSamples = hopFill
        hopAccumulator.fill(0f, validSamples, hopAccumulator.size)
        val isEnabled = isMusicRemovalEnabled()
        val prevEnabled = producerLastEnabledState
        val gen = flushGeneration.get()
        val offered =
            inputRing.offer(hopAccumulator) {
                enabled = isEnabled
                previousEnabled = prevEnabled
                generation = gen
                this.validSamples = validSamples
                isEndOfStream = true
            }
        if (offered) {
            endOfStreamHopPending = false
            hopFill = 0
            producerLastEnabledState = isEnabled
            LockSupport.unpark(workerThreadRef.get())
        }
    }

    private fun trySyncEngineGeometry() {
        if (!workerExecutionLock.tryLock()) return
        try {
            syncEngineGeometry()
        } finally {
            workerExecutionLock.unlock()
        }
    }

    private fun syncEngineGeometry() {
        val geometry = engine.ensureGeometry()
        if (geometry.sampleRate == targetSampleRate && geometry.hopSize == targetHopSize) return
        targetSampleRate = geometry.sampleRate
        if (geometry.hopSize != targetHopSize) {
            targetHopSize = geometry.hopSize
            inputRing = SpscHopRingBuffer(effectiveRingCapacity, geometry.hopSize)
            outputRing = SpscHopRingBuffer(effectiveRingCapacity, geometry.hopSize)
            workerInHop = FloatArray(geometry.hopSize)
            hopAccumulator = FloatArray(geometry.hopSize)
            drainHopBuf = FloatArray(geometry.hopSize)
            hopFill = 0
        }
    }

    private fun ensureWorkerStarted() {
        synchronized(workerLifecycleLock) {
            if (workerRunning.get()) return
            val workerId = nextWorkerId.incrementAndGet()
            activeWorkerId.set(workerId)
            workerRunning.set(true)
            val thread =
                Thread({ runWorkerLoop(workerId) }, "MusicRemoval-Worker").apply {
                    isDaemon = true
                }
            workerThreadRef.set(thread)
            thread.start()
        }
    }

    private fun stopWorker(): Boolean {
        val thread: Thread?
        synchronized(workerLifecycleLock) {
            workerRunning.set(false)
            thread = workerThreadRef.getAndSet(null)
        }
        if (thread != null) {
            LockSupport.unpark(thread)
            if (Thread.currentThread() !== thread) {
                runCatching { thread.join(WORKER_JOIN_TIMEOUT_MS) }
            }
            return !thread.isAlive
        }
        return true
    }

    private fun runWorkerLoop(myWorkerId: Long) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        workerExecutionLock.lock()
        try {
            while (isActiveWorker(myWorkerId)) {
                handlePendingWorkerFlush()
                workerInFlight.set(true)

                var polledEnabled = true
                var polledPrevEnabled: Boolean? = null
                var polledGen = 0L
                var polledValid = 0
                var polledEndOfStream = false
                val polled =
                    inputRing.pollInto(workerInHop) {
                        polledEnabled = enabled
                        polledPrevEnabled = previousEnabled
                        polledGen = generation
                        polledValid = validSamples
                        polledEndOfStream = isEndOfStream
                    }

                if (!polled) {
                    workerInFlight.set(false)
                    LockSupport.unpark(outputWaiterThread.get())
                    if (!isActiveWorker(myWorkerId)) break
                    if (
                        !inputRing.isEmpty || workerHandledFlushGen.get() != flushGeneration.get()
                    ) {
                        continue
                    }
                    LockSupport.park()
                    continue
                }

                if (!isCurrentGeneration(polledGen, myWorkerId)) {
                    if (!isActiveWorker(myWorkerId)) break
                    handlePendingWorkerFlush()
                    continue
                }

                if (polledEndOfStream) {
                    engine.drainEndOfStream(
                        finalHop = workerInHop,
                        validSamples = polledValid,
                        enabled = polledEnabled,
                        previousEnabled = polledPrevEnabled,
                    ) { chunk ->
                        syncOverloadMuteForGeneration(
                            engine.isMutedForOverload,
                            polledGen,
                            myWorkerId,
                        )
                        offerOutput(chunk, polledGen, myWorkerId)
                    }
                    continue
                }

                val output = engine.processHop(workerInHop, polledEnabled, polledPrevEnabled)
                if (!isCurrentGeneration(polledGen, myWorkerId)) {
                    if (!isActiveWorker(myWorkerId)) break
                    handlePendingWorkerFlush()
                    continue
                }
                syncOverloadMuteForGeneration(engine.isMutedForOverload, polledGen, myWorkerId)
                offerOutput(output, polledGen, myWorkerId)
            }
        } finally {
            try {
                workerInFlight.set(false)
                synchronized(workerLifecycleLock) {
                    if (activeWorkerId.get() == myWorkerId) {
                        engine.close()
                        workerThreadRef.compareAndSet(Thread.currentThread(), null)
                        workerRunning.set(false)
                    }
                }
            } finally {
                workerExecutionLock.unlock()
            }
            LockSupport.unpark(outputWaiterThread.get())
        }
    }

    private fun offerOutput(output: HopOutput, gen: Long, workerId: Long): Boolean {
        while (isCurrentGeneration(gen, workerId)) {
            val offered =
                outputRing.offer(output.samples) {
                    generation = gen
                    validSamples = output.validSamples
                    isMuted = output.isMuted
                    startsFromSilence = output.startsFromSilence
                }
            if (offered) {
                LockSupport.unpark(outputWaiterThread.get())
                return true
            }
            LockSupport.parkNanos(OUTPUT_BACKPRESSURE_PARK_NS)
        }
        return false
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
        engine.reset()
        workerHandledFlushGen.set(currentGen)
    }

    private fun syncOverloadMuteForGeneration(
        muted: Boolean,
        expectedGen: Long,
        workerId: Long,
    ) {
        var changedTo: Boolean? = null
        synchronized(overloadStateLock) {
            if (flushGeneration.get() != expectedGen || !isActiveWorker(workerId)) return
            if (isMutedForOverload != muted) {
                isMutedForOverload = muted
                changedTo = muted
            }
        }
        changedTo?.let(onOverloadMuteChanged)
    }

    private fun clearOverloadMute() {
        if (!isMutedForOverload) return
        var notifyClear = false
        synchronized(overloadStateLock) {
            if (isMutedForOverload) {
                isMutedForOverload = false
                notifyClear = true
            }
        }
        if (notifyClear) {
            onOverloadMuteChanged(false)
        }
    }

    private fun isActiveWorker(workerId: Long): Boolean =
        workerRunning.get() && activeWorkerId.get() == workerId

    private fun isCurrentGeneration(gen: Long, workerId: Long): Boolean =
        isActiveWorker(workerId) && flushGeneration.get() == gen

    private fun hasPendingAsyncWork(): Boolean = !inputRing.isEmpty || workerInFlight.get()

    companion object {
        const val DEFAULT_RING_BUFFER_CAPACITY_HOPS = 64
        const val DEFAULT_OUTPUT_WAIT_TIMEOUT_MS = 100L

        private const val INITIAL_MONO_CAPACITY = 4096
        private const val FLOAT_BYTES = 4
        private const val SHORT_BYTES = 2
        private const val PCM16_INPUT_SCALE = 32768f
        private const val PCM16_OUTPUT_SCALE = 32767f
        private const val OUTPUT_SLACK_SAMPLES = 4
        private const val NS_PER_MS_LONG = 1_000_000L
        private const val WAIT_SLICE_NS = 5_000_000L
        private const val OUTPUT_BACKPRESSURE_PARK_NS = 1_000_000L
        private const val WORKER_JOIN_TIMEOUT_MS = 500L
    }
}
