package com.github.libretube.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRemovalAudioProcessorTest {

    private class FakeSpeechModel(
        override val isLoaded: Boolean = true,
        override val sampleRate: Int = TEST_SAMPLE_RATE,
        override val hopSize: Int = TEST_HOP_SIZE,
        private val transform: (FloatArray) -> FloatArray = { hop ->
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        },
    ) : SpeechModel {
        var hopsProcessed = 0

        override fun processHop(hop: FloatArray): FloatArray {
            assertEquals(hopSize, hop.size)
            hopsProcessed++
            return transform(hop)
        }
    }

    @Test
    fun `when Music Removal is off, multi-channel native-rate audio passes through without downmixing, resampling, or model invocation`() {
        var modelProviderCalled = false
        val model = FakeSpeechModel()
        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = {
                    modelProviderCalled = true
                    model
                },
                isMusicRemovalEnabled = { false },
            )

        val inputFormat = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val outFormat = processor.configure(inputFormat)
        assertEquals(44100, outFormat.sampleRate)
        assertEquals(2, outFormat.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, outFormat.encoding)
        assertFalse(
            "Expected modelProvider not to be called when Music Removal is off",
            modelProviderCalled,
        )
        processor.flush()

        // 100 stereo frames at 44.1 kHz with opposite L/R channels (would cancel to 0 if downmixed
        // to mono)
        val frameCount = 100
        val inputBuffer =
            ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount) {
            inputBuffer.putShort((10000 + i).toShort())
            inputBuffer.putShort((-10000 - i).toShort())
        }
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val output = processor.output

        assertFalse(
            "Expected modelProvider not to be called during passthrough",
            modelProviderCalled,
        )
        assertEquals(0, model.hopsProcessed)
        assertEquals(frameCount * 2 * 2, output.remaining())
        for (i in 0 until frameCount) {
            assertEquals("Left channel mismatch at frame $i", (10000 + i).toShort(), output.short)
            assertEquals("Right channel mismatch at frame $i", (-10000 - i).toShort(), output.short)
        }
    }

    @Test
    fun `working speech model mixes stereo to mono, resamples to 48kHz, and outputs processed mono PCM`() {
        val model = FakeSpeechModel()
        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { model },
                isMusicRemovalEnabled = { true },
            )

        val outFormat = processor.configure(AudioFormat(24000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(48000, outFormat.sampleRate)
        assertEquals(1, outFormat.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, outFormat.encoding)
        processor.flush()

        // 256 stereo frames at 24 kHz -> 512 mono samples at 48 kHz (1 hop)
        val inputFrames = 256
        val inputBuffer =
            ByteBuffer.allocateDirect(inputFrames * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(inputFrames) {
            // Left = 10000, Right = 10000 -> Mono = 10000; model scales by 0.5 -> ~5000
            inputBuffer.putShort(10000)
            inputBuffer.putShort(10000)
        }
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val output = processor.output

        assertEquals(1, model.hopsProcessed)
        assertEquals(512 * 2, output.remaining())
        val firstSample = output.short.toInt()
        assertTrue("Expected ~5000, got $firstSample", abs(firstSample - 5000) <= 5)
    }

    @Test
    fun `model that fails to load, throws, or is slower than 1_5x playback rate outputs silence and reports overload`() {
        fun makeInputHopBuffer(): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { buf.putShort(12000) }
            buf.flip()
            return buf
        }

        fun assertAllSilence(output: ByteBuffer) {
            assertEquals(512 * 2, output.remaining())
            while (output.hasRemaining()) {
                assertEquals(0, output.short.toInt())
            }
        }

        // Case 1: Model failed to load
        val unloadedProcessor =
            MusicRemovalAudioProcessor(
                modelProvider = { FakeSpeechModel(isLoaded = false) },
                isMusicRemovalEnabled = { true },
            )
        unloadedProcessor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        unloadedProcessor.flush()
        unloadedProcessor.queueInput(makeInputHopBuffer())
        assertAllSilence(unloadedProcessor.output)

        // Case 2: Model throws during processHop
        val throwingProcessor =
            MusicRemovalAudioProcessor(
                modelProvider = { FakeSpeechModel { throw RuntimeException("Inference error") } },
                isMusicRemovalEnabled = { true },
            )
        throwingProcessor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        throwingProcessor.flush()
        throwingProcessor.queueInput(makeInputHopBuffer())
        assertAllSilence(throwingProcessor.output)

        // Case 3: Model is too slow (< 1.5x playback rate: 1 hop = 10.67ms, taking 9ms -> ~1.18x <
        // 1.5x)
        var fakeClockNs = 0L
        var loggedDevice: String? = null
        var loggedRate: Double? = null
        val slowModel = FakeSpeechModel { hop ->
            fakeClockNs += 9_000_000L // 9 ms per 10.67 ms hop (~1.18x realtime)
            hop
        }
        val slowProcessor =
            MusicRemovalAudioProcessor(
                modelProvider = { slowModel },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { fakeClockNs },
                overloadSustainHops = 1,
                onOverloadMuted = { device, rate ->
                    loggedDevice = device
                    loggedRate = rate
                },
            )
        slowProcessor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        slowProcessor.flush()
        assertFalse(slowProcessor.isMutedForOverload)

        slowProcessor.queueInput(makeInputHopBuffer())
        assertAllSilence(slowProcessor.output)
        assertTrue(slowProcessor.isMutedForOverload)
        assertFalse(loggedDevice.isNullOrBlank())
        assertTrue("Expected rate < 1.5, got $loggedRate", loggedRate != null && loggedRate < 1.5)
    }

    @Test
    fun `speed-aware overload threshold keeps 1_3x processing playing at 1_0x speed but mutes at 1_5x speed`() {
        var fakeClockNs = 0L
        var playbackSpeed = 1.0f
        // ~1.30x realtime: 10.667 ms hop processed in 8.2 ms
        val modelAt1_3x = FakeSpeechModel { hop ->
            fakeClockNs += 8_200_000L
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }
        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { modelAt1_3x },
                isMusicRemovalEnabled = { true },
                playbackSpeedProvider = { playbackSpeed },
                nanoTimeProvider = { fakeClockNs },
                overloadSustainHops = 5,
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        fun queueHop(): ShortArray {
            val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { buf.putShort(10000) }
            buf.flip()
            processor.queueInput(buf)
            val out = processor.output
            return ShortArray(512) { out.short }
        }

        // At 1.0x playback speed, sustained 1.3x realtime processing is comfortably above the 1.0x
        // threshold
        repeat(10) {
            val out = queueHop()
            assertFalse(
                "Expected no overload mute at 1.0x speed with 1.3x processing",
                processor.isMutedForOverload,
            )
            assertTrue(
                "Expected processed speech (~5000), got ${out.first()}",
                abs(out.first() - 5000) <= 10,
            )
        }

        // At 1.5x playback speed, 1.3x realtime processing cannot keep up and must mute after
        // sustain hops
        playbackSpeed = 1.5f
        repeat(5) {
            queueHop()
        }
        val mutedOut = queueHop()
        assertTrue(
            "Expected overload mute at 1.5x speed with 1.3x processing",
            processor.isMutedForOverload,
        )
        assertTrue("Expected all silence when muted for overload", mutedOut.all { it.toInt() == 0 })
    }

    @Test
    fun `transient single-hop jitter does not drop audio frames or trigger false overload muting`() {
        var fakeClockNs = 0L
        var hopIndex = 0
        val jitterModel = FakeSpeechModel { hop ->
            hopIndex++
            // Every 3rd hop takes 9 ms (~1.18x), while other hops take 2 ms (~5.33x)
            fakeClockNs += if (hopIndex % 3 == 0) 9_000_000L else 2_000_000L
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }
        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { jitterModel },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { fakeClockNs },
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        repeat(15) {
            val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { buf.putShort(10000) }
            buf.flip()
            processor.queueInput(buf)
            val out = processor.output
            assertEquals(512 * 2, out.remaining())
            val firstSample = out.short.toInt()
            assertTrue(
                "Expected continuous audio (~5000) without dropouts, got $firstSample",
                abs(firstSample - 5000) <= 10,
            )
            assertFalse(
                "Expected no overload mute on transient jitter",
                processor.isMutedForOverload,
            )
        }
    }

    @Test
    fun `switching Music Removal off and on during playback applies a smooth fade without abrupt jump`() {
        var enabled = true
        // Fake model attenuates to 0.2x
        val model = FakeSpeechModel { hop -> FloatArray(hop.size) { i -> hop[i] * 0.2f } }
        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { model },
                isMusicRemovalEnabled = { enabled },
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        fun queueConstantHop(sampleValue: Short = 10000): ShortArray {
            val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { buf.putShort(sampleValue) }
            buf.flip()
            processor.queueInput(buf)
            val out = processor.output
            return ShortArray(512) { out.short }
        }

        // Hop 1 with Music Removal ON -> steady ~2000
        val hopOn = queueConstantHop(10000)
        assertTrue(abs(hopOn.first() - 2000) <= 10)
        assertTrue(abs(hopOn.last() - 2000) <= 10)

        // Toggle OFF mid-playback -> Hop 2 fades smoothly from ~2000 toward ~10000
        enabled = false
        val hopTransitionOff = queueConstantHop(10000)
        val firstSampleOff = hopTransitionOff.first().toInt()
        val midSampleOff = hopTransitionOff[256].toInt()
        val lastSampleOff = hopTransitionOff.last().toInt()

        // No abrupt jump at start of transition (starts near 2000, not 10000)
        assertTrue(
            "Expected smooth start near 2000, got $firstSampleOff",
            firstSampleOff in 1900..2500,
        )
        assertTrue(
            "Expected midpoint between 4000..8000, got $midSampleOff",
            midSampleOff in 4000..8000,
        )
        assertTrue("Expected end near 10000, got $lastSampleOff", lastSampleOff in 9500..10000)

        // Toggle ON again -> Hop 3 fades smoothly from ~10000 back toward ~2000
        enabled = true
        val hopTransitionOn = queueConstantHop(10000)
        val firstSampleOn = hopTransitionOn.first().toInt()
        val lastSampleOn = hopTransitionOn.last().toInt()
        assertTrue(
            "Expected smooth start near 10000, got $firstSampleOn",
            firstSampleOn in 9500..10000,
        )
        assertTrue("Expected end near 2000, got $lastSampleOn", lastSampleOn in 1900..2500)
    }

    @Test
    fun `toggling Music Removal on and off mid-stream from stereo passthrough crossfades smoothly without invoking model when off`() {
        var enabled = false
        val model = FakeSpeechModel { hop -> FloatArray(hop.size) { i -> hop[i] * 0.2f } }
        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { model },
                isMusicRemovalEnabled = { enabled },
            )

        val outFormat = processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(48000, outFormat.sampleRate)
        assertEquals(2, outFormat.channelCount)
        processor.flush()

        fun queueStereoHop(left: Short = 10000, right: Short = 10000): ShortArray {
            val buf = ByteBuffer.allocateDirect(512 * 2 * 2).order(ByteOrder.nativeOrder())
            repeat(512) {
                buf.putShort(left)
                buf.putShort(right)
            }
            buf.flip()
            processor.queueInput(buf)
            val out = processor.output
            val shorts = ShortArray(out.remaining() / 2)
            for (i in shorts.indices) {
                shorts[i] = out.short
            }
            return shorts
        }

        // 1. Passthrough while OFF: stereo preserved, 0 model hops
        val offPassthrough = queueStereoHop(10000, 6000)
        assertEquals(512 * 2, offPassthrough.size)
        assertEquals(10000.toShort(), offPassthrough[0])
        assertEquals(6000.toShort(), offPassthrough[1])
        assertEquals(0, model.hopsProcessed)

        // 2. Toggle ON mid-stream: crossfades from ~10000 toward ~2000 across 512 stereo frames
        enabled = true
        val transitionOn = queueStereoHop(10000, 10000)
        assertEquals(512 * 2, transitionOn.size)
        assertEquals(1, model.hopsProcessed)
        val firstFrameOn = transitionOn[0].toInt()
        val lastFrameOn = transitionOn[transitionOn.size - 2].toInt()
        assertTrue(
            "Expected smooth start near 10000, got $firstFrameOn",
            firstFrameOn in 9500..10000,
        )
        assertTrue("Expected smooth end near 2000, got $lastFrameOn", lastFrameOn in 1900..2500)

        // Steady ON hop -> ~2000
        val steadyOn = queueStereoHop(10000, 10000)
        assertEquals(512 * 2, steadyOn.size)
        assertEquals(2, model.hopsProcessed)
        assertTrue(abs(steadyOn.first() - 2000) <= 10)
        assertTrue(abs(steadyOn.last() - 2000) <= 10)

        // 3. Toggle OFF mid-stream: crossfades from ~2000 back to ~10000 without invoking model
        enabled = false
        val transitionOff = queueStereoHop(10000, 10000)
        assertEquals(512 * 2, transitionOff.size)
        assertEquals("Expected model not to be invoked when toggled off", 2, model.hopsProcessed)
        val firstFrameOff = transitionOff[0].toInt()
        val midFrameOff = transitionOff[256 * 2].toInt()
        val lastFrameOff = transitionOff[transitionOff.size - 2].toInt()
        assertTrue(
            "Expected smooth start near 2000, got $firstFrameOff",
            firstFrameOff in 1900..2500,
        )
        assertTrue(
            "Expected midpoint between 4000..8000, got $midFrameOff",
            midFrameOff in 4000..8000,
        )
        assertTrue("Expected smooth end near 10000, got $lastFrameOff", lastFrameOff in 9500..10000)
    }

    @Test
    fun `playback speed is capped at 1_5x when Music Removal is on`() {
        assertEquals(
            1.0f,
            MusicRemovalAudioProcessor.clampPlaybackSpeed(1.0f, musicRemovalEnabled = true),
            1e-4f,
        )
        assertEquals(
            1.5f,
            MusicRemovalAudioProcessor.clampPlaybackSpeed(1.5f, musicRemovalEnabled = true),
            1e-4f,
        )
        assertEquals(
            1.5f,
            MusicRemovalAudioProcessor.clampPlaybackSpeed(2.0f, musicRemovalEnabled = true),
            1e-4f,
        )
        assertEquals(
            1.5f,
            MusicRemovalAudioProcessor.clampPlaybackSpeed(3.0f, musicRemovalEnabled = true),
            1e-4f,
        )
        assertEquals(
            2.0f,
            MusicRemovalAudioProcessor.clampPlaybackSpeed(2.0f, musicRemovalEnabled = false),
            1e-4f,
        )
    }

    @Test
    fun `FastEnhancerSpeechModel loads fastenhancer_s and silero_vad and gates non-speech musical tones to silence`() {
        val modelFile =
            sequenceOf(
                    java.io.File("src/main/assets/fastenhancer_s.onnx"),
                    java.io.File("app/src/main/assets/fastenhancer_s.onnx"),
                )
                .first { it.exists() }
        val vadFile =
            sequenceOf(
                    java.io.File("src/main/assets/silero_vad.onnx"),
                    java.io.File("app/src/main/assets/silero_vad.onnx"),
                )
                .first { it.exists() }

        val model =
            SpeechEnhancementPipeline(
                FastEnhancerSpeechModel(modelFile.readBytes()),
                vadGate = SileroVadGate(vadFile.readBytes()),
            )
        try {
            assertTrue("Expected fastenhancer_s.onnx + silero_vad.onnx to load", model.isLoaded)
            assertEquals(48000, model.sampleRate)
            assertEquals(512, model.hopSize)

            // Pure musical chord (A4=440Hz, C#5=554.37Hz, E5=659.25Hz) with no speech
            var sampleIdx = 0
            repeat(6) {
                val hop =
                    FloatArray(FastEnhancerSpeechModel.HOP_SIZE) {
                        val t = sampleIdx++.toDouble() / FastEnhancerSpeechModel.SAMPLE_RATE
                        (0.2f * kotlin.math.sin(2.0 * Math.PI * 440.0 * t) +
                                0.15f * kotlin.math.sin(2.0 * Math.PI * 554.37 * t) +
                                0.15f * kotlin.math.sin(2.0 * Math.PI * 659.25 * t))
                            .toFloat()
                    }
                val out = model.processHop(hop)
                assertEquals(FastEnhancerSpeechModel.HOP_SIZE, out.size)
                assertTrue(out.all { !it.isNaN() && !it.isInfinite() })
                // Non-speech musical tones should be gated to silence
                assertTrue(
                    "Expected non-speech musical chord to be gated to silence",
                    out.all { it == 0f },
                )
            }
        } finally {
            model.close()
        }

        val corruptModel = FastEnhancerSpeechModel(ByteArray(16) { 0x42 })
        assertFalse(corruptModel.isLoaded)

        val corruptVad = SileroVadGate(ByteArray(16) { 0x42 })
        assertFalse(corruptVad.isLoaded)
    }

    @Test
    fun `multi-hop streaming preserves speech and gates music without state resets during concurrent warmup or secondary player init`() {
        val modelBytes =
            sequenceOf(
                    java.io.File("src/main/assets/fastenhancer_s.onnx"),
                    java.io.File("app/src/main/assets/fastenhancer_s.onnx"),
                )
                .first { it.exists() }
                .readBytes()
        val vadBytes =
            sequenceOf(
                    java.io.File("src/main/assets/silero_vad.onnx"),
                    java.io.File("app/src/main/assets/silero_vad.onnx"),
                )
                .first { it.exists() }
                .readBytes()

        fun buildHop(hopIdx: Int): FloatArray {
            return FloatArray(FastEnhancerSpeechModel.HOP_SIZE) { i ->
                val sampleIdx = hopIdx * FastEnhancerSpeechModel.HOP_SIZE + i
                val t = sampleIdx.toDouble() / FastEnhancerSpeechModel.SAMPLE_RATE
                if (hopIdx < 6) {
                    // Speech-band harmonic signal (fundamental 150 Hz + formants 450 Hz, 1200 Hz,
                    // 2400 Hz)
                    val env = 0.5 + 0.5 * kotlin.math.sin(2.0 * Math.PI * 5.0 * t)
                    (env *
                            (0.30 * kotlin.math.sin(2.0 * Math.PI * 150.0 * t) +
                                0.20 * kotlin.math.sin(2.0 * Math.PI * 450.0 * t) +
                                0.12 * kotlin.math.sin(2.0 * Math.PI * 1200.0 * t) +
                                0.08 * kotlin.math.sin(2.0 * Math.PI * 2400.0 * t)))
                        .toFloat()
                } else {
                    // Pure non-speech musical chord (A4=440Hz, C#5=554.37Hz, E5=659.25Hz)
                    (0.2f * kotlin.math.sin(2.0 * Math.PI * 440.0 * t) +
                            0.15f * kotlin.math.sin(2.0 * Math.PI * 554.37 * t) +
                            0.15f * kotlin.math.sin(2.0 * Math.PI * 659.25 * t))
                        .toFloat()
                }
            }
        }

        val totalHops = 9
        val hops = List(totalHops) { buildHop(it) }

        // 1. Uninterrupted baseline reference run (speech enhancement + post-filter + VAD music
        // gate)
        val baselineOutputs = ArrayList<FloatArray>(totalHops)
        val baselineSpeechOnlyOutputs = ArrayList<FloatArray>(6)
        val resetMidStreamSpeechOutputs = ArrayList<FloatArray>(6)

        val baselineModel =
            SpeechEnhancementPipeline(
                FastEnhancerSpeechModel(modelBytes),
                vadGate = SileroVadGate(vadBytes),
            )
        val baselineSpeechOnly = SpeechEnhancementPipeline(FastEnhancerSpeechModel(modelBytes))
        val resetSpeechOnly = SpeechEnhancementPipeline(FastEnhancerSpeechModel(modelBytes))
        try {
            for (h in 0 until totalHops) {
                baselineOutputs.add(baselineModel.processHop(hops[h]).copyOf())
            }
            for (h in 0 until 6) {
                baselineSpeechOnlyOutputs.add(baselineSpeechOnly.processHop(hops[h]).copyOf())
                if (h == 3) {
                    // Prove that a mid-stream reset alters subsequent hop output
                    resetSpeechOnly.reset()
                }
                resetMidStreamSpeechOutputs.add(resetSpeechOnly.processHop(hops[h]).copyOf())
            }
        } finally {
            baselineModel.close()
            baselineSpeechOnly.close()
            resetSpeechOnly.close()
        }

        // Non-speech musical hops (hops 6..8) must be gated to silence
        for (h in 6 until totalHops) {
            assertTrue(
                "Expected musical hop $h to be gated to silence",
                baselineOutputs[h].all { it == 0f },
            )
        }
        // Speech-enhanced hop 3 must have non-zero preserved signal and differ if reset mid-stream
        assertTrue(
            "Expected speech enhancement to produce non-zero output on hop 3",
            baselineSpeechOnlyOutputs[3].any { abs(it) > 1e-4f },
        )
        assertFalse(
            "Expected mid-stream reset at hop 3 to alter output (proving recurrent state sensitivity)",
            baselineSpeechOnlyOutputs[3].contentEquals(resetMidStreamSpeechOutputs[3]),
        )

        // 2. Interleaved run with concurrent background warmup and secondary player initialization
        OrtSpeechRuntime.clearSharedInstancesForTests()
        try {
            val sharedSpeechOnlyLoader = {
                SpeechEnhancementPipeline(FastEnhancerSpeechModel(modelBytes))
            }
            val activeSpeechModel = OrtSpeechRuntime.warmup(sharedSpeechOnlyLoader)
            for (h in 0 until 6) {
                if (h in 1..5) {
                    // Simulate background warmup & secondary player init mid-stream
                    val warmed = OrtSpeechRuntime.warmup(sharedSpeechOnlyLoader)
                    assertTrue(warmed === activeSpeechModel)

                    val secondaryProcessor =
                        MusicRemovalAudioProcessor(
                            modelProvider = {
                                OrtSpeechRuntime.getOrCreateShared(sharedSpeechOnlyLoader)
                            },
                            isMusicRemovalEnabled = { true },
                        )
                    secondaryProcessor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
                    secondaryProcessor.flush()
                    secondaryProcessor.reset()
                }
                val actualHop = activeSpeechModel.processHop(hops[h]).copyOf()
                org.junit.Assert.assertArrayEquals(
                    "Speech enhancement state was reset at hop $h during warmup/secondary player init",
                    baselineSpeechOnlyOutputs[h],
                    actualHop,
                    1e-6f,
                )
            }

            OrtSpeechRuntime.clearSharedInstancesForTests()
            val sharedFullLoader = {
                SpeechEnhancementPipeline(
                    FastEnhancerSpeechModel(modelBytes),
                    vadGate = SileroVadGate(vadBytes),
                )
            }
            val activeFullModel = OrtSpeechRuntime.warmup(sharedFullLoader)
            for (h in 0 until totalHops) {
                // Trigger concurrent warmup on a background thread and secondary processor init
                // between hops
                val warmupThread = Thread {
                    OrtSpeechRuntime.warmup(sharedFullLoader)
                }
                warmupThread.start()
                warmupThread.join()

                val secondaryProcessor =
                    MusicRemovalAudioProcessor(
                        modelProvider = {
                            OrtSpeechRuntime.getOrCreateShared(sharedFullLoader)
                        },
                        isMusicRemovalEnabled = { true },
                    )
                secondaryProcessor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
                secondaryProcessor.flush()
                secondaryProcessor.reset()

                val actualHop = activeFullModel.processHop(hops[h]).copyOf()
                org.junit.Assert.assertArrayEquals(
                    "Full speech + VAD state was reset at hop $h during concurrent warmup/secondary player init",
                    baselineOutputs[h],
                    actualHop,
                    1e-6f,
                )
            }
        } finally {
            OrtSpeechRuntime.clearSharedInstancesForTests()
        }
    }

    @Test
    fun `FastEnhancerSpeechModel loads fastenhancer_t fallback model independently from primary model`() {
        val primaryFile =
            sequenceOf(
                    java.io.File("src/main/assets/${OrtSpeechRuntime.ASSET_FILENAME}"),
                    java.io.File("app/src/main/assets/${OrtSpeechRuntime.ASSET_FILENAME}"),
                )
                .first { it.exists() }
        val fallbackFile =
            sequenceOf(
                    java.io.File("src/main/assets/${OrtSpeechRuntime.FALLBACK_ASSET_FILENAME}"),
                    java.io.File("app/src/main/assets/${OrtSpeechRuntime.FALLBACK_ASSET_FILENAME}"),
                )
                .first { it.exists() }
        val vadFile =
            sequenceOf(
                    java.io.File("src/main/assets/${OrtSpeechRuntime.VAD_ASSET_FILENAME}"),
                    java.io.File("app/src/main/assets/${OrtSpeechRuntime.VAD_ASSET_FILENAME}"),
                )
                .first { it.exists() }

        OrtSpeechRuntime.clearSharedInstancesForTests()
        try {
            val primary = OrtSpeechRuntime.getOrCreateShared {
                SpeechEnhancementPipeline(
                    FastEnhancerSpeechModel(primaryFile.readBytes()),
                    vadGate = SileroVadGate(vadFile.readBytes()),
                )
            }
            val fallback = OrtSpeechRuntime.getOrCreateSharedFallback {
                SpeechEnhancementPipeline(
                    FastEnhancerSpeechModel(fallbackFile.readBytes()),
                    vadGate = SileroVadGate(vadFile.readBytes()),
                )
            }
            assertTrue("Expected primary FastEnhancer-S to load", primary.isLoaded)
            assertTrue("Expected fallback FastEnhancer-T to load", fallback.isLoaded)
            assertFalse(
                "Expected primary and fallback shared instances to be distinct",
                primary === fallback,
            )

            // Non-speech musical chord should be gated to silence on FastEnhancer-T too
            var sampleIdx = 0
            repeat(6) {
                val hop =
                    FloatArray(FastEnhancerSpeechModel.HOP_SIZE) {
                        val t = sampleIdx++.toDouble() / FastEnhancerSpeechModel.SAMPLE_RATE
                        (0.2f * kotlin.math.sin(2.0 * Math.PI * 440.0 * t) +
                                0.15f * kotlin.math.sin(2.0 * Math.PI * 554.37 * t))
                            .toFloat()
                    }
                val out = fallback.processHop(hop)
                assertEquals(FastEnhancerSpeechModel.HOP_SIZE, out.size)
                assertTrue(
                    "Expected fallback model to gate pure music to silence",
                    out.all { it == 0f },
                )
            }
        } finally {
            OrtSpeechRuntime.clearSharedInstancesForTests()
        }
    }

    @Test
    fun `SpeechPostFilter fuses real FFT and IFFT passes with identical spectral filtering output`() {
        // Independent textbook O(N^2) DFT/IDFT reference for a single 512-point real sub-frame
        fun referenceSpectralSubFrame(
            inFrame: FloatArray,
            enhFrame: FloatArray,
            smoothedGain: FloatArray,
            window: FloatArray,
            minBin: Int,
            maxBin: Int,
        ): FloatArray {
            val n = 512
            val numBins = n / 2 + 1
            val inRe = DoubleArray(n)
            val inIm = DoubleArray(n)
            val enhRe = DoubleArray(n)
            val enhIm = DoubleArray(n)
            for (k in 0 until n) {
                var sr0 = 0.0
                var si0 = 0.0
                var sr1 = 0.0
                var si1 = 0.0
                for (t in 0 until n) {
                    val angle = -2.0 * Math.PI * k * t / n
                    val c = kotlin.math.cos(angle)
                    val s = kotlin.math.sin(angle)
                    val x = inFrame[t].toDouble() * window[t]
                    val y = enhFrame[t].toDouble() * window[t]
                    sr0 += x * c
                    si0 += x * s
                    sr1 += y * c
                    si1 += y * s
                }
                inRe[k] = sr0
                inIm[k] = si0
                enhRe[k] = sr1
                enhIm[k] = si1
            }
            val rawMask = FloatArray(numBins)
            for (k in minBin..maxBin) {
                val xMag = kotlin.math.sqrt(inRe[k] * inRe[k] + inIm[k] * inIm[k]).toFloat()
                val yMag = kotlin.math.sqrt(enhRe[k] * enhRe[k] + enhIm[k] * enhIm[k]).toFloat()
                val ratio = (yMag / (xMag + 1e-7f)).coerceIn(0f, 1f)
                rawMask[k] =
                    when {
                        ratio < 0.10f -> 0f
                        ratio < 0.52f -> {
                            val norm = (ratio - 0.10f) / (0.52f - 0.10f)
                            norm * norm
                        }
                        else -> 1f
                    }
            }
            for (k in 0 until numBins) {
                val target =
                    if (k in minBin..maxBin) {
                        0.25f * rawMask[(k - 1).coerceAtLeast(minBin)] +
                            0.50f * rawMask[k] +
                            0.25f * rawMask[(k + 1).coerceAtMost(maxBin)]
                    } else {
                        0f
                    }
                val alpha = if (target > smoothedGain[k]) 0.65f else 0.35f
                val g = alpha * target + (1f - alpha) * smoothedGain[k]
                smoothedGain[k] = g
                enhRe[k] *= g
                enhIm[k] *= g
                if (k in 1 until (numBins - 1)) {
                    enhRe[n - k] *= g
                    enhIm[n - k] *= g
                }
            }
            return FloatArray(n) { t ->
                var acc = 0.0
                for (k in 0 until n) {
                    val angle = 2.0 * Math.PI * k * t / n
                    acc += enhRe[k] * kotlin.math.cos(angle) - enhIm[k] * kotlin.math.sin(angle)
                }
                ((acc / n) * window[t]).toFloat()
            }
        }

        val postFilter = SpeechPostFilter(FastEnhancerSpeechModel.SAMPLE_RATE)
        val n = 512
        val subHop = 256
        val window = FloatArray(n) { i -> kotlin.math.sin(Math.PI * (i + 0.5) / n).toFloat() }
        val minBin = ((95.0 * n) / FastEnhancerSpeechModel.SAMPLE_RATE).toInt().coerceAtLeast(1)
        val maxBin =
            ((7500.0 * n) / FastEnhancerSpeechModel.SAMPLE_RATE).toInt().coerceAtMost(n / 2)

        // Independent reference state (without post-biquads, so we feed biquad-equivalent or
        // compare pre-biquad vs full)
        // Wait: applySpectralFilter runs postHighPass + postLowPass after OLA, so we check both:
        // 1. fftPassCount == 3 per hop (down from 6)
        // 2. Output is finite, preserves speech-band energy, attenuates out-of-band noise, and
        // matches OLA + biquads
        val refDelayedInput = FloatArray(n)
        val refPrevInTail = FloatArray(subHop)
        val refPrevEnhTail = FloatArray(subHop)
        val refOlaTail = FloatArray(subHop)
        val refSmoothedGain = FloatArray(n / 2 + 1) { 1f }

        // Create deterministic Biquad replicas matching SpeechPostFilter's 100 Hz HPF & 7200 Hz LPF
        fun makeHpf(): (FloatArray) -> FloatArray {
            val w0 = 2.0 * Math.PI * 100.0 / FastEnhancerSpeechModel.SAMPLE_RATE
            val cosW0 = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / kotlin.math.sqrt(2.0)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
            val b1 = (-(1.0 + cosW0) / a0).toFloat()
            val b2 = b0
            val a1 = (-2.0 * cosW0 / a0).toFloat()
            val a2 = ((1.0 - alpha) / a0).toFloat()
            var x1 = 0f
            var x2 = 0f
            var y1 = 0f
            var y2 = 0f
            return { buf ->
                FloatArray(buf.size) { i ->
                    val x = buf[i]
                    val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1
                    x1 = x
                    y2 = y1
                    y1 = y
                    y
                }
            }
        }
        fun makeLpf(): (FloatArray) -> FloatArray {
            val w0 = 2.0 * Math.PI * 7200.0 / FastEnhancerSpeechModel.SAMPLE_RATE
            val cosW0 = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / kotlin.math.sqrt(2.0)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 - cosW0) / 2.0 / a0).toFloat()
            val b1 = ((1.0 - cosW0) / a0).toFloat()
            val b2 = b0
            val a1 = (-2.0 * cosW0 / a0).toFloat()
            val a2 = ((1.0 - alpha) / a0).toFloat()
            var x1 = 0f
            var x2 = 0f
            var y1 = 0f
            var y2 = 0f
            return { buf ->
                FloatArray(buf.size) { i ->
                    val x = buf[i]
                    val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1
                    x1 = x
                    y2 = y1
                    y1 = y
                    y
                }
            }
        }
        val refPostHpf = makeHpf()
        val refPostLpf = makeLpf()

        repeat(4) { hopIdx ->
            val inHop =
                FloatArray(n) { i ->
                    val t = (hopIdx * n + i).toDouble() / FastEnhancerSpeechModel.SAMPLE_RATE
                    (0.35 * kotlin.math.sin(2.0 * Math.PI * 300.0 * t) +
                            0.20 * kotlin.math.sin(2.0 * Math.PI * 1200.0 * t) +
                            0.10 * kotlin.math.sin(2.0 * Math.PI * 5000.0 * t))
                        .toFloat()
                }
            val enhHop =
                FloatArray(n) { i ->
                    val t = (hopIdx * n + i).toDouble() / FastEnhancerSpeechModel.SAMPLE_RATE
                    (0.32 * kotlin.math.sin(2.0 * Math.PI * 300.0 * t) +
                            0.18 * kotlin.math.sin(2.0 * Math.PI * 1200.0 * t))
                        .toFloat()
                }

            val actual = postFilter.applySpectralFilter(inHop, enhHop).copyOf()

            val in0 =
                FloatArray(n).apply {
                    System.arraycopy(refPrevInTail, 0, this, 0, subHop)
                    System.arraycopy(refDelayedInput, 0, this, subHop, subHop)
                }
            val enh0 =
                FloatArray(n).apply {
                    System.arraycopy(refPrevEnhTail, 0, this, 0, subHop)
                    System.arraycopy(enhHop, 0, this, subHop, subHop)
                }
            val f0 = referenceSpectralSubFrame(in0, enh0, refSmoothedGain, window, minBin, maxBin)
            val f1 =
                referenceSpectralSubFrame(
                    refDelayedInput,
                    enhHop,
                    refSmoothedGain,
                    window,
                    minBin,
                    maxBin,
                )

            val expected = FloatArray(n)
            for (i in 0 until subHop) expected[i] = refOlaTail[i] + f0[i]
            for (i in 0 until subHop) {
                expected[subHop + i] = f0[subHop + i] + f1[i]
                refOlaTail[i] = f1[subHop + i]
            }
            System.arraycopy(refDelayedInput, subHop, refPrevInTail, 0, subHop)
            System.arraycopy(enhHop, subHop, refPrevEnhTail, 0, subHop)
            System.arraycopy(inHop, 0, refDelayedInput, 0, n)

            val expectedFiltered = refPostLpf(refPostHpf(expected))
            org.junit.Assert.assertArrayEquals(
                "Fused FFT spectral filter output diverged from reference at hop $hopIdx",
                expectedFiltered,
                actual,
                1e-4f,
            )
        }

        // 4 hops * 3 FFTs/hop (2 fused real forward FFTs + 1 fused real inverse FFT) = 12 FFT calls
        // (down from 24)
        assertEquals(12L, postFilter.fftPassCount)
    }

    @Test
    fun `SileroVadGate anti-aliased 48k-to-16k decimation suppresses high-frequency musical aliasing while preserving speech band`() {
        val vadBytes =
            sequenceOf(
                    java.io.File("src/main/assets/silero_vad.onnx"),
                    java.io.File("app/src/main/assets/silero_vad.onnx"),
                )
                .first { it.exists() }
                .readBytes()

        val vadGate = SileroVadGate(vadBytes)
        try {
            assertTrue(vadGate.isLoaded)

            fun measureDecimatedRms(freqHz: Double, numHops: Int = 6): Float {
                vadGate.reset()
                val out16k = FloatArray(FastEnhancerSpeechModel.HOP_SIZE)
                var sumSq = 0.0
                var totalOut = 0
                var sampleIdx = 0
                repeat(numHops) { hopIdx ->
                    val hop =
                        FloatArray(FastEnhancerSpeechModel.HOP_SIZE) {
                            val t = sampleIdx++.toDouble() / FastEnhancerSpeechModel.SAMPLE_RATE
                            (0.4 * kotlin.math.sin(2.0 * Math.PI * freqHz * t)).toFloat()
                        }
                    val count = vadGate.decimateHop48kForTest(hop, out16k)
                    // Skip first 2 hops for IIR filter settling
                    if (hopIdx >= 2) {
                        for (i in 0 until count) {
                            sumSq += out16k[i] * out16k[i]
                        }
                        totalOut += count
                    }
                }
                return kotlin.math.sqrt(sumSq / totalOut.coerceAtLeast(1)).toFloat()
            }

            // 1 kHz speech-band tone (input RMS = 0.4 / sqrt(2) = 0.2828) must pass unattenuated
            val speechBandRms = measureDecimatedRms(1000.0)
            assertTrue(
                "Expected 1 kHz tone to pass near unity (~0.28), got $speechBandRms",
                speechBandRms > 0.25f,
            )

            // 12 kHz & 14 kHz high-frequency tones (above 8 kHz Nyquist for 16 kHz) must be
            // strongly attenuated
            // (Old 3-sample boxcar gave ~0.094 RMS at 12 kHz and ~0.060 RMS at 14 kHz, aliasing to
            // 4 kHz & 2 kHz!)
            val alias12kRms = measureDecimatedRms(12000.0)
            val alias14kRms = measureDecimatedRms(14000.0)
            assertTrue(
                "Expected >= 30 dB attenuation at 12 kHz (RMS < 0.01), got $alias12kRms",
                alias12kRms < 0.01f,
            )
            assertTrue(
                "Expected >= 40 dB attenuation at 14 kHz (RMS < 0.003), got $alias14kRms",
                alias14kRms < 0.003f,
            )
        } finally {
            vadGate.close()
        }
    }

    @Test
    fun `queuing multi-hop burst never blocks caller thread and ring-buffer backpressure drops overflow without deadlocking`() {
        val workerEnteredFirstHop = java.util.concurrent.CountDownLatch(1)
        val releaseWorker = java.util.concurrent.CountDownLatch(1)
        val blockingModel = FakeSpeechModel { hop ->
            workerEnteredFirstHop.countDown()
            releaseWorker.await(2, java.util.concurrent.TimeUnit.SECONDS)
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }

        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { blockingModel },
                isMusicRemovalEnabled = { true },
                ringBufferCapacityHops = 4,
                outputWaitTimeoutMs = 0L,
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        try {
            // Queue hop 0 so worker picks it up and blocks inside processHop
            val hop0 = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { hop0.putShort(10000) }
            hop0.flip()
            processor.queueInput(hop0)
            assertTrue(
                "Worker did not start processing hop 0 in time",
                workerEnteredFirstHop.await(1, java.util.concurrent.TimeUnit.SECONDS),
            )

            // Queue an 8-hop burst while worker is stalled on hop 0 (capacity = 4)
            val burst = ByteBuffer.allocateDirect(8 * 512 * 2).order(ByteOrder.nativeOrder())
            repeat(8 * 512) { burst.putShort(10000) }
            burst.flip()

            val startNs = System.nanoTime()
            processor.queueInput(burst)
            val queueElapsedMs = (System.nanoTime() - startNs) / 1_000_000L

            assertTrue(
                "Expected non-blocking queueInput (< 50 ms), took ${queueElapsedMs}ms",
                queueElapsedMs < 50L,
            )
            assertEquals(
                "Expected non-blocking output to be empty while worker stalled",
                0,
                processor.output.remaining(),
            )
            assertEquals(
                "Expected 4 overflow hops dropped due to capacity=4 backpressure",
                4L,
                processor.droppedInputHops,
            )

            // Unblock worker and drain all 5 accepted hops (hop 0 + 4 buffered hops)
            releaseWorker.countDown()
            processor.queueEndOfStream()

            var totalOutputBytes = 0
            val deadlineMs = System.currentTimeMillis() + 2000L
            while (!processor.isEnded && System.currentTimeMillis() < deadlineMs) {
                val out = processor.output
                totalOutputBytes += out.remaining()
                if (!out.hasRemaining()) Thread.sleep(2)
            }
            totalOutputBytes += processor.output.remaining()

            assertTrue("Expected processor to reach ended state", processor.isEnded)
            assertEquals(5, blockingModel.hopsProcessed)
            assertEquals(5 * 512 * 2, totalOutputBytes)
        } finally {
            releaseWorker.countDown()
            processor.reset()
        }
    }

    @Test
    fun `flush on seek clears queued ring-buffer hops, discards in-flight stale hop, and resets model before post-seek audio`() {
        val preSeekInFlightEntered = java.util.concurrent.CountDownLatch(1)
        val releasePreSeekInFlight = java.util.concurrent.CountDownLatch(1)
        val hopCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val resetCount = java.util.concurrent.atomic.AtomicInteger(0)

        val statefulModel =
            object : SpeechModel {
                override val isLoaded: Boolean = true
                override val sampleRate: Int = TEST_SAMPLE_RATE
                override val hopSize: Int = TEST_HOP_SIZE

                override fun processHop(hop: FloatArray): FloatArray {
                    val idx = hopCounter.getAndIncrement()
                    if (idx == 1) {
                        // Stall hop 1 in-flight so flush() happens mid-inference with additional
                        // hops queued
                        preSeekInFlightEntered.countDown()
                        releasePreSeekInFlight.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    return hop.copyOf()
                }

                override fun reset() {
                    resetCount.incrementAndGet()
                }
            }

        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { statefulModel },
                isMusicRemovalEnabled = { true },
                ringBufferCapacityHops = 8,
                outputWaitTimeoutMs = 100L,
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        fun makeConstBuffer(sample: Short, hops: Int = 1): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(hops * 512 * 2).order(ByteOrder.nativeOrder())
            repeat(hops * 512) { buf.putShort(sample) }
            buf.flip()
            return buf
        }

        try {
            // 1. Queue hop 0 (value 7000) and wait until worker finishes it into outputRing
            // (unread)
            processor.queueInput(makeConstBuffer(7000))
            val waitHop0Deadline = System.currentTimeMillis() + 1000L
            while (hopCounter.get() < 1 && System.currentTimeMillis() < waitHop0Deadline) {
                Thread.sleep(2)
            }
            // Wait a tiny bit for hop 0 to land in outputRing
            Thread.sleep(10)

            // 2. Queue hop 1 (stalls in-flight) + hops 2, 3 (queued in inputRing) with pre-seek
            // value 7000
            processor.queueInput(makeConstBuffer(7000, hops = 3))
            assertTrue(
                "Worker did not enter in-flight hop 1",
                preSeekInFlightEntered.await(1, java.util.concurrent.TimeUnit.SECONDS),
            )

            // 3. Simulate seek -> flush() immediately clears outputRing and bumps generation
            val resetsBeforeFlush = resetCount.get()
            processor.flush()
            releasePreSeekInFlight.countDown()

            // Immediately after flush without queuing new audio, no stale pre-seek output may be
            // emitted
            val immediateAfterFlush = processor.output
            assertEquals(
                "Expected zero stale output immediately after flush",
                0,
                immediateAfterFlush.remaining(),
            )

            // 4. Queue post-seek hop with distinct value 20000
            processor.queueInput(makeConstBuffer(20000))
            val postSeekOut = processor.output
            assertEquals(512 * 2, postSeekOut.remaining())
            val firstPostSeekSample = postSeekOut.short.toInt()
            assertTrue(
                "Expected post-seek sample (~20000), got stale or wrong sample $firstPostSeekSample",
                abs(firstPostSeekSample - 20000) <= 10,
            )
            assertTrue(
                "Expected model.reset() to be called on worker after flush",
                resetCount.get() > resetsBeforeFlush,
            )
            // Hops 2 and 3 (queued pre-seek) must have been discarded: total processHop calls = hop
            // 0 + hop 1 + 1 post-seek = 3
            assertEquals(
                "Expected queued pre-seek hops 2 & 3 to be skipped without inference",
                3,
                hopCounter.get(),
            )
        } finally {
            releasePreSeekInFlight.countDown()
            processor.reset()
        }
    }

    @Test
    fun `sustained slow inference on primary model automatically downshifts to fallback model without muting speech`() {
        var fakeClockNs = 0L
        val slowPrimaryModel = FakeSpeechModel { hop ->
            fakeClockNs +=
                10_000_000L // 10 ms per 10.67 ms hop (~1.07x < 1.2x required at 1.0x speed)
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }
        val fastFallbackModel = FakeSpeechModel { hop ->
            fakeClockNs += 2_000_000L // 2 ms per 10.67 ms hop (~5.33x)
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }

        var overloadCalled = false
        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { slowPrimaryModel },
                fallbackModelProvider = { fastFallbackModel },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { fakeClockNs },
                overloadSustainHops = 6,
                onOverloadMuted = { _, _ -> overloadCalled = true },
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        try {
            fun queueAndReadHop(): ShortArray {
                val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
                repeat(512) { buf.putShort(10000) }
                buf.flip()
                processor.queueInput(buf)
                val out = processor.output
                assertEquals(512 * 2, out.remaining())
                return ShortArray(512) { out.short }
            }

            // Process 10 hops: primary should downshift to fallback cleanly without ever muting
            repeat(10) {
                val out = queueAndReadHop()
                assertFalse(
                    "Expected speech to never mute during automatic downshift",
                    processor.isMutedForOverload,
                )
                assertTrue(
                    "Expected audible speech (~5000), got ${out.first()}",
                    abs(out.first() - 5000) <= 15,
                )
            }

            assertTrue(
                "Expected processor to have downshifted to fallback model",
                processor.isUsingFallbackModel,
            )
            assertFalse("Expected onOverloadMuted not to be invoked", overloadCalled)
            assertTrue(
                "Expected primary model to stop receiving hops after downshift",
                slowPrimaryModel.hopsProcessed < 6,
            )
            assertTrue(
                "Expected fallback model to process subsequent hops",
                fastFallbackModel.hopsProcessed >= 4,
            )
        } finally {
            processor.reset()
        }
    }

    @Test
    fun `when both primary and fallback models are overloaded or fallback fails, audio mutes safely`() {
        fun makeInputHop(): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { buf.putShort(10000) }
            buf.flip()
            return buf
        }

        // Case A: Both primary and fallback models are too slow
        var fakeClockNs = 0L
        var loggedRate: Double? = null
        val slowPrimary = FakeSpeechModel { hop ->
            fakeClockNs += 10_000_000L
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }
        val slowFallback = FakeSpeechModel { hop ->
            fakeClockNs += 10_000_000L
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }
        val bothSlowProcessor =
            MusicRemovalAudioProcessor(
                modelProvider = { slowPrimary },
                fallbackModelProvider = { slowFallback },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { fakeClockNs },
                overloadSustainHops = 4,
                onOverloadMuted = { _, rate -> loggedRate = rate },
            )
        bothSlowProcessor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        bothSlowProcessor.flush()

        try {
            repeat(10) {
                bothSlowProcessor.queueInput(makeInputHop())
                bothSlowProcessor.output
            }
            assertTrue(
                "Expected downshift to fallback before muting",
                bothSlowProcessor.isUsingFallbackModel,
            )
            assertTrue(
                "Expected overload mute when both tiers are too slow",
                bothSlowProcessor.isMutedForOverload,
            )
            assertTrue(loggedRate != null && loggedRate < 1.2)

            bothSlowProcessor.queueInput(makeInputHop())
            val mutedOut = bothSlowProcessor.output
            assertEquals(512 * 2, mutedOut.remaining())
            while (mutedOut.hasRemaining()) {
                assertEquals(0, mutedOut.short.toInt())
            }
        } finally {
            bothSlowProcessor.reset()
        }

        // Case B: Primary is slow and fallback model fails to load
        var clockB = 0L
        val failingFallbackProcessor =
            MusicRemovalAudioProcessor(
                modelProvider = {
                    FakeSpeechModel { hop ->
                        clockB += 10_000_000L
                        hop
                    }
                },
                fallbackModelProvider = { FakeSpeechModel(isLoaded = false) },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { clockB },
                overloadSustainHops = 4,
            )
        failingFallbackProcessor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        failingFallbackProcessor.flush()

        try {
            repeat(4) {
                failingFallbackProcessor.queueInput(makeInputHop())
                failingFallbackProcessor.output
            }
            assertTrue(
                "Expected safe mute when fallback fails to load",
                failingFallbackProcessor.isMutedForOverload,
            )
            failingFallbackProcessor.queueInput(makeInputHop())
            val out = failingFallbackProcessor.output
            assertEquals(512 * 2, out.remaining())
            while (out.hasRemaining()) {
                assertEquals(0, out.short.toInt())
            }
        } finally {
            failingFallbackProcessor.reset()
        }
    }

    @Test
    fun `pre-flush in-flight failing or slow hop does not re-mute after onFlush clears overload state`() {
        val preFlushEntered = java.util.concurrent.CountDownLatch(1)
        val releasePreFlush = java.util.concurrent.CountDownLatch(1)
        val preFlushDone = java.util.concurrent.CountDownLatch(1)
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        var fakeClockNs = 0L
        var overloadCallbackCount = 0

        val model =
            object : SpeechModel {
                override val isLoaded: Boolean = true
                override val sampleRate: Int = TEST_SAMPLE_RATE
                override val hopSize: Int = TEST_HOP_SIZE

                override fun processHop(hop: FloatArray): FloatArray {
                    val idx = callCount.getAndIncrement()
                    if (idx == 0) {
                        preFlushEntered.countDown()
                        releasePreFlush.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        fakeClockNs += 20_000_000L // Slow pre-flush hop (< 1.2x)
                        try {
                            throw RuntimeException("Pre-flush failure")
                        } finally {
                            preFlushDone.countDown()
                        }
                    }
                    fakeClockNs += 1_000_000L // Fast post-flush hop
                    return FloatArray(hop.size) { i -> hop[i] * 0.5f }
                }
            }

        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { model },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { fakeClockNs },
                overloadSustainHops = 1,
                outputWaitTimeoutMs = 500L,
                onOverloadMuted = { _, _ -> overloadCallbackCount++ },
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        fun makeHopBuffer(sample: Short = 10000): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { buf.putShort(sample) }
            buf.flip()
            return buf
        }

        try {
            processor.queueInput(makeHopBuffer(10000))
            assertTrue(
                "Worker did not enter pre-flush hop",
                preFlushEntered.await(1, java.util.concurrent.TimeUnit.SECONDS),
            )

            // Seek/flush happens while hop 0 is still inside processHop
            processor.flush()
            releasePreFlush.countDown()
            assertTrue(
                "Pre-flush hop did not finish",
                preFlushDone.await(1, java.util.concurrent.TimeUnit.SECONDS),
            )
            // Allow worker thread a moment to run past processSingleHop
            Thread.sleep(20)

            assertFalse(
                "Stale pre-flush hop must not set isMutedForOverload",
                processor.isMutedForOverload,
            )
            assertEquals(
                "Stale pre-flush hop must not trigger overload callback",
                0,
                overloadCallbackCount,
            )

            // Post-flush hop must emit audible processed audio, not silence
            processor.queueInput(makeHopBuffer(10000))
            val out = processor.output
            assertEquals(512 * 2, out.remaining())
            val firstSample = out.short.toInt()
            assertTrue(
                "Expected audible post-flush audio (~5000), got $firstSample",
                abs(firstSample - 5000) <= 10,
            )
            assertFalse(processor.isMutedForOverload)
        } finally {
            releasePreFlush.countDown()
            processor.reset()
        }
    }

    @Test
    fun `workerRunning is cleared in finally so worker restarts cleanly after abnormal exit`() {
        val firstWorkerFailed = java.util.concurrent.CountDownLatch(1)
        var throwFatalError = true

        val model = FakeSpeechModel { hop ->
            if (throwFatalError) {
                firstWorkerFailed.countDown()
                throw Error("Simulated abnormal worker exit")
            }
            FloatArray(hop.size) { i -> hop[i] * 0.5f }
        }

        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { model },
                isMusicRemovalEnabled = { true },
                outputWaitTimeoutMs = 500L,
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        fun makeHopBuffer(sample: Short = 10000): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
            repeat(512) { buf.putShort(sample) }
            buf.flip()
            return buf
        }

        try {
            processor.queueInput(makeHopBuffer(10000))
            assertTrue(
                "First worker did not run",
                firstWorkerFailed.await(1, java.util.concurrent.TimeUnit.SECONDS),
            )
            // Wait briefly for first worker thread to finish its finally block
            Thread.sleep(50)

            throwFatalError = false
            processor.flush()
            processor.queueInput(makeHopBuffer(10000))

            val out = processor.output
            assertEquals("Expected restarted worker to emit 1 hop", 512 * 2, out.remaining())
            val firstSample = out.short.toInt()
            assertTrue(
                "Expected processed audio (~5000) from restarted worker, got $firstSample",
                abs(firstSample - 5000) <= 10,
            )
        } finally {
            processor.reset()
        }
    }

    @Test
    fun `sizes buffers from non-48kHz non-512-hop primary model and keeps output AudioFormat stable across fallback downshift`() {
        val primary = FakeSpeechModel(sampleRate = 16000, hopSize = 256)
        val fallback = FakeSpeechModel(sampleRate = 24000, hopSize = 320)
        var clockNs = 0L

        val slowPrimary =
            FakeSpeechModel(sampleRate = 16000, hopSize = 256) { hop ->
                primary.processHop(hop)
                // 32 ms per 16 ms hop (~0.5x rate) -> triggers downshift
                clockNs += 32_000_000L
                FloatArray(hop.size) { i -> hop[i] * 0.5f }
            }

        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { slowPrimary },
                fallbackModelProvider = { fallback },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { clockNs },
                overloadSustainHops = 2,
                downshiftSustainHops = 1,
            )

        val outFormat = processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(16000, outFormat.sampleRate)
        assertEquals(1, outFormat.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, outFormat.encoding)
        processor.flush()

        try {
            // Queue 6 hops at 16 kHz / 256-sample hop (each hop = 768 stereo frames at 48 kHz)
            repeat(6) {
                val inputBuf = ByteBuffer.allocateDirect(768 * 2 * 2).order(ByteOrder.nativeOrder())
                repeat(768) {
                    inputBuf.putShort(10000)
                    inputBuf.putShort(10000)
                }
                inputBuf.flip()
                processor.queueInput(inputBuf)
                val out = processor.output
                assertEquals(256 * 2, out.remaining())
            }

            assertTrue("Expected downshift to fallback model", processor.isUsingFallbackModel)
            assertTrue("Expected fallback model to process hops", fallback.hopsProcessed > 0)
            // Output AudioFormat must remain 16 kHz mono across fallback downshift
            val reconfiguredOut = processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            assertEquals(16000, reconfiguredOut.sampleRate)
            assertFalse(processor.isMutedForOverload)
        } finally {
            processor.reset()
        }
    }

    @Test
    fun `reports overload mute changes through injected callback port`() {
        val muteChanges = mutableListOf<Boolean>()
        var clockNs = 0L
        val slowModel =
            FakeSpeechModel(sampleRate = 48000, hopSize = 512) { hop ->
                clockNs += 25_000_000L
                hop.copyOf()
            }

        val processor =
            MusicRemovalAudioProcessor(
                modelProvider = { slowModel },
                isMusicRemovalEnabled = { true },
                nanoTimeProvider = { clockNs },
                overloadSustainHops = 2,
                onOverloadMuteChanged = { muted -> muteChanges.add(muted) },
            )
        processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        try {
            repeat(3) {
                val buf = ByteBuffer.allocateDirect(512 * 2).order(ByteOrder.nativeOrder())
                repeat(512) { buf.putShort(10000) }
                buf.flip()
                processor.queueInput(buf)
                processor.output
            }
            assertTrue(processor.isMutedForOverload)
            assertEquals(listOf(true), muteChanges)

            processor.flush()
            assertFalse(processor.isMutedForOverload)
            assertEquals(listOf(true, false), muteChanges)
        } finally {
            processor.reset()
        }
    }

    private companion object {
        private const val TEST_SAMPLE_RATE = 48000
        private const val TEST_HOP_SIZE = 512
    }
}
