package com.github.libretube.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class MusicRemovalAudioProcessorTest {

    private class FakeSpeechModel(
        override val sampleRate: Int = 16000,
        override val hopSize: Int = 512,
        override var isLoaded: Boolean = true,
        override val latencySamples: Int = hopSize,
        private val transform: (FloatArray) -> FloatArray = { it.copyOf() },
    ) : SpeechModel {
        val processCalls = AtomicInteger(0)
        val resetCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)

        override fun processHop(hop: FloatArray): FloatArray {
            processCalls.incrementAndGet()
            return transform(hop)
        }

        override fun reset() {
            resetCalls.incrementAndGet()
        }

        override fun close() {
            closeCalls.incrementAndGet()
            isLoaded = false
        }
    }

    private fun pcm16Buffer(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            for (s in samples) putShort(s)
            flip()
        }

    private fun drainShorts(
        processor: MusicRemovalAudioProcessor,
        minShorts: Int,
        timeoutMs: Long = 2000L,
    ): ShortArray {
        val collected = ArrayList<Short>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (collected.size < minShorts && System.currentTimeMillis() < deadline) {
            val out = processor.getOutput()
            if (out.hasRemaining()) {
                out.order(ByteOrder.nativeOrder())
                while (out.hasRemaining()) collected.add(out.short)
            } else {
                Thread.sleep(1)
            }
        }
        return collected.toShortArray()
    }

    private fun drainUntilEnded(
        processor: MusicRemovalAudioProcessor,
        timeoutMs: Long = 2000L,
    ): ShortArray {
        val collected = ArrayList<Short>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!processor.isEnded() && System.currentTimeMillis() < deadline) {
            val out = processor.getOutput()
            if (out.hasRemaining()) {
                out.order(ByteOrder.nativeOrder())
                while (out.hasRemaining()) collected.add(out.short)
            } else {
                Thread.sleep(1)
            }
        }
        return collected.toShortArray()
    }

    private fun loadAssetBytes(filename: String): ByteArray =
        sequenceOf(File("src/main/assets/$filename"), File("app/src/main/assets/$filename"))
            .first { it.exists() }
            .readBytes()

    @Test
    fun `when Music Removal is off, multi-channel native-rate audio passes through without invoking engine`() {
        val model = FakeSpeechModel()
        val processor =
            MusicRemovalAudioProcessor(
                engine = MusicRemovalEngine(modelProvider = { model }),
                isMusicRemovalEnabled = { false },
            )
        try {
            val inputFormat = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
            val outputFormat = processor.configure(inputFormat)
            assertEquals(inputFormat, outputFormat)
            processor.flush()

            val input = ShortArray(2048) { i -> ((i * 17) % 10000 - 5000).toShort() }
            processor.queueInput(pcm16Buffer(input))
            val out = processor.getOutput().order(ByteOrder.nativeOrder())
            assertEquals(input.size * 2, out.remaining())
            for (expected in input) assertEquals(expected, out.short)
            assertEquals(0, model.processCalls.get())
        } finally {
            processor.reset()
        }
    }

    @Test
    fun `stereo 48 kHz input resamples with anti-aliasing to 16 kHz mono output`() {
        val model = FakeSpeechModel(sampleRate = 16000, hopSize = 512)
        val processor =
            MusicRemovalAudioProcessor(
                engine =
                    MusicRemovalEngine(
                        modelProvider = { model },
                        config = MusicRemovalConfig(enablePostFilter = false, logTelemetry = false),
                    ),
                isMusicRemovalEnabled = { true },
            )
        try {
            val outputFormat =
                processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            assertEquals(16000, outputFormat.sampleRate)
            assertEquals(1, outputFormat.channelCount)
            processor.flush()

            // Feed 14 kHz tone (above 8 kHz Nyquist at 16 kHz): anti-aliasing must suppress it
            val frames = 48000 / 2 // 0.5 s stereo
            val stereo =
                ShortArray(frames * 2) { idx ->
                    val frame = idx / 2
                    val sample = (0.5 * sin(2.0 * PI * 14000.0 * frame / 48000.0) * 32767.0).toInt()
                    sample.toShort()
                }
            processor.queueInput(pcm16Buffer(stereo))
            val out = drainShorts(processor, minShorts = 512 * 10)
            assertTrue(out.size >= 512 * 10)
            val tail = out.sliceArray(512 * 4 until out.size)
            val tailRms = sqrt(tail.map { (it / 32767.0) * (it / 32767.0) }.average())
            assertTrue(
                "Expected 14 kHz tone suppressed after 48k->16k decimation, rms=$tailRms",
                tailRms < 0.01,
            )
        } finally {
            processor.reset()
        }
    }

    @Test
    fun `Overload Mute silences every emitted sample across output resampling and reports via callback`() {
        val clock = AtomicLong(0L)
        var slow = true
        val model =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                clock.addAndGet(if (slow) 80_000_000L else 2_000_000L)
                FloatArray(hop.size) { 0.8f }
            }
        val muteEvents = ArrayList<Boolean>()
        var enabled = false
        val processor =
            MusicRemovalAudioProcessor(
                engine =
                    MusicRemovalEngine(
                        modelProvider = { model },
                        config =
                            MusicRemovalConfig(
                                nanoTimeProvider = { clock.get() },
                                overloadSustainHops = 1,
                                enablePostFilter = false,
                                logTelemetry = false,
                            ),
                    ),
                isMusicRemovalEnabled = { enabled },
                onOverloadMuteChanged = { synchronized(muteEvents) { muteEvents.add(it) } },
            )
        try {
            // Start OFF at 48 kHz stereo so output format stays 48 kHz stereo when toggling ON
            processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            processor.flush()

            // Toggle ON while overloaded -> every emitted 48 kHz stereo sample must be exact 0
            enabled = true
            val oneHopStereo = ShortArray(1600 * 2) { 20000 }
            processor.queueInput(pcm16Buffer(oneHopStereo))
            val mutedOut = drainShorts(processor, minShorts = 1500 * 2)
            assertTrue(mutedOut.isNotEmpty())
            assertTrue(
                "Overload Mute must silence every resampled sample",
                mutedOut.all { it == 0.toShort() },
            )
            assertTrue(processor.isMutedForOverload)
            assertTrue(synchronized(muteEvents) { muteEvents.contains(true) })

            // Recover -> callback reports false and audio fades in from silence
            slow = false
            processor.queueInput(pcm16Buffer(oneHopStereo))
            val recoveredOut = drainShorts(processor, minShorts = 1500 * 2)
            assertFalse(processor.isMutedForOverload)
            assertTrue(synchronized(muteEvents) { muteEvents.lastOrNull() == false })
            assertTrue(abs(recoveredOut.first().toInt()) < 500)
            assertTrue(recoveredOut.any { it > 10000 })
        } finally {
            processor.reset()
        }
    }

    @Test
    fun `ring-buffer backpressure drops overflow without blocking caller thread`() {
        val blockWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val blockingModel =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                workerEntered.countDown()
                blockWorker.await(2, TimeUnit.SECONDS)
                hop.copyOf()
            }
        val processor =
            MusicRemovalAudioProcessor(
                engine = MusicRemovalEngine(modelProvider = { blockingModel }),
                isMusicRemovalEnabled = { true },
                ringBufferCapacityHops = 4,
                outputWaitTimeoutMs = 0L,
            )
        try {
            processor.configure(AudioProcessor.AudioFormat(16000, 1, C.ENCODING_PCM_16BIT))
            processor.flush()

            // First hop wakes worker and blocks inside model; next 12 hops overflow the 4-slot ring
            processor.queueInput(pcm16Buffer(ShortArray(512) { 1000 }))
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS))
            processor.queueInput(pcm16Buffer(ShortArray(512 * 12) { 1000 }))
            assertTrue("Expected overflow hops dropped", processor.droppedInputHops > 0L)
        } finally {
            blockWorker.countDown()
            processor.reset()
        }
    }

    @Test
    fun `flush on seek discards stale in-flight failing hop without re-muting and aborts EOS drain`() {
        val startedSlowHop = CountDownLatch(1)
        val releaseSlowHop = CountDownLatch(1)
        val failNext = AtomicBoolean(true)
        val model =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                if (failNext.getAndSet(false)) {
                    startedSlowHop.countDown()
                    releaseSlowHop.await(2, TimeUnit.SECONDS)
                    throw IllegalStateException("Stale pre-seek failure")
                }
                FloatArray(hop.size) { 0.5f }
            }
        val processor =
            MusicRemovalAudioProcessor(
                engine =
                    MusicRemovalEngine(
                        modelProvider = { model },
                        config = MusicRemovalConfig(enablePostFilter = false, logTelemetry = false),
                    ),
                isMusicRemovalEnabled = { true },
            )
        try {
            processor.configure(AudioProcessor.AudioFormat(16000, 1, C.ENCODING_PCM_16BIT))
            processor.flush()

            processor.queueInput(pcm16Buffer(ShortArray(512) { 5000 }))
            assertTrue(startedSlowHop.await(1, TimeUnit.SECONDS))

            // Flush while pre-seek failing hop is in flight, then release it
            processor.flush()
            releaseSlowHop.countDown()

            // Post-seek audio must play cleanly without getting muted by the stale pre-seek error
            processor.queueInput(pcm16Buffer(ShortArray(512 * 2) { 5000 }))
            val postSeek = drainShorts(processor, minShorts = 512)
            assertTrue(postSeek.any { it > 10000 })
            assertFalse(processor.isMutedForOverload)

            // Queue EOS then immediately flush (seek during drain): old audio is discarded
            processor.queueEndOfStream()
            processor.flush()
            assertFalse(processor.isEnded())
            assertEquals(0, processor.getOutput().remaining())
        } finally {
            releaseSlowHop.countDown()
            processor.reset()
        }
    }

    @Test
    fun `end-to-end integration through OrtSpeechRuntime shared cache drains full latencySamples`() {
        OrtSpeechRuntime.clearSharedInstancesForTests()
        try {
            val smallBytes = loadAssetBytes(OrtSpeechRuntime.ASSET_FILENAME)
            val vadBytes = loadAssetBytes(OrtSpeechRuntime.VAD_ASSET_FILENAME)
            val modelLoader = { FastEnhancerSpeechModel(smallBytes) }
            val gateLoader = { SileroVadGate(vadBytes) }

            // Warm up shared primary model and shared VAD gate
            val warmedModel = OrtSpeechRuntime.getOrCreateShared(modelLoader)
            val warmedGate = OrtSpeechRuntime.getOrCreateSharedGate(gateLoader)
            assertEquals(256, warmedModel.latencySamples)
            assertTrue(warmedGate.isLoaded)

            val engine =
                MusicRemovalEngine(
                    modelProvider = { OrtSpeechRuntime.getOrCreateShared(modelLoader) },
                    gateProvider = { OrtSpeechRuntime.getOrCreateSharedGate(gateLoader) },
                    config = MusicRemovalConfig(logTelemetry = false),
                )
            val processor =
                MusicRemovalAudioProcessor(
                    engine = engine,
                    isMusicRemovalEnabled = { true },
                )
            try {
                val outFmt =
                    processor.configure(AudioProcessor.AudioFormat(16000, 1, C.ENCODING_PCM_16BIT))
                assertEquals(16000, outFmt.sampleRate)
                processor.flush()

                // Feed 1 full hop (512) + 128 partial samples, then end of stream.
                // Total emitted must equal 512 + 128 + 1024 (256 model + 256 post-filter + 512 VAD
                // lookahead)
                processor.queueInput(pcm16Buffer(ShortArray(512 + 128) { 1000 }))
                processor.queueEndOfStream()
                val emitted = drainUntilEnded(processor)
                assertTrue(processor.isEnded())
                assertEquals(512 + 128 + 1024, emitted.size)
            } finally {
                processor.reset()
            }

            // Shared cache stays alive after processor reset (close() delegates to reset())
            assertTrue(warmedModel.isLoaded)
            assertTrue(warmedGate.isLoaded)
        } finally {
            OrtSpeechRuntime.clearSharedInstancesForTests()
        }
    }

    @Test
    fun `slow exiting worker after reset and restart does not close engine or break new worker`() {
        val slowHopEntered = CountDownLatch(1)
        val releaseSlowHop = CountDownLatch(1)
        val blockFirstHop = AtomicBoolean(true)
        val model =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                if (blockFirstHop.getAndSet(false)) {
                    slowHopEntered.countDown()
                    releaseSlowHop.await(3, TimeUnit.SECONDS)
                    FloatArray(hop.size) { 0.1f }
                } else {
                    FloatArray(hop.size) { 0.5f }
                }
            }
        val processor =
            MusicRemovalAudioProcessor(
                engine =
                    MusicRemovalEngine(
                        modelProvider = { model },
                        config =
                            MusicRemovalConfig(
                                overloadSustainHops = 100,
                                enablePostFilter = false,
                                logTelemetry = false,
                            ),
                    ),
                isMusicRemovalEnabled = { true },
            )
        try {
            processor.configure(AudioProcessor.AudioFormat(16000, 1, C.ENCODING_PCM_16BIT))
            processor.flush()

            // Trigger worker 1 and wait until it blocks inside processHop (> 500 ms join timeout).
            processor.queueInput(pcm16Buffer(ShortArray(512 * 2) { 5000 }))
            assertTrue(slowHopEntered.await(1, TimeUnit.SECONDS))

            // Reset times out while worker 1 is still blocked, then immediately restart for track
            // 2.
            processor.reset()
            processor.configure(AudioProcessor.AudioFormat(16000, 1, C.ENCODING_PCM_16BIT))
            processor.flush()
            processor.queueInput(pcm16Buffer(ShortArray(512 * 2) { 5000 }))

            // Release worker 1 after worker 2 has already started; worker 1 must not close engine.
            releaseSlowHop.countDown()

            val out = drainShorts(processor, minShorts = 512)
            assertTrue("Expected processed output from restarted worker", out.any { it > 10000 })
            assertFalse(processor.isMutedForOverload)
            assertTrue(model.isLoaded)
            assertEquals(0, model.closeCalls.get())
        } finally {
            releaseSlowHop.countDown()
            processor.reset()
        }
        assertEquals(1, model.closeCalls.get())
    }
}
