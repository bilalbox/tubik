package com.github.libretube.player

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRemovalEngineTest {

    private class FakeSpeechModel(
        override val sampleRate: Int = 16000,
        override val hopSize: Int = 512,
        override var isLoaded: Boolean = true,
        override val latencySamples: Int = hopSize,
        private val transform: (FloatArray) -> FloatArray = { it.copyOf() },
    ) : SpeechModel {
        var processHopCalls = 0
        var resetCalls = 0
        var closeCalls = 0

        override fun processHop(hop: FloatArray): FloatArray {
            require(hop.size == hopSize) { "Expected hop of $hopSize, got ${hop.size}" }
            processHopCalls++
            return transform(hop)
        }

        override fun reset() {
            resetCalls++
        }

        override fun close() {
            closeCalls++
            isLoaded = false
        }
    }

    private fun loadAssetBytes(filename: String): ByteArray =
        sequenceOf(File("src/main/assets/$filename"), File("app/src/main/assets/$filename"))
            .first { it.exists() }
            .readBytes()

    private fun constantHop(value: Float, size: Int = 512) = FloatArray(size) { value }

    private fun sineHop(
        freqHz: Double,
        sampleRate: Int,
        hopSize: Int = 512,
        startSample: Int = 0,
        amplitude: Float = 0.5f,
    ): FloatArray =
        FloatArray(hopSize) { i ->
            val t = (startSample + i).toDouble() / sampleRate
            (amplitude * sin(2.0 * PI * freqHz * t)).toFloat()
        }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        return sqrt(sumSq / samples.size).toFloat()
    }

    @Test
    fun `playback speed is capped at 1_5x only when Music Removal is on`() {
        assertEquals(1.0f, MusicRemovalEngine.clampPlaybackSpeed(1.0f, true), 0.001f)
        assertEquals(1.5f, MusicRemovalEngine.clampPlaybackSpeed(1.5f, true), 0.001f)
        assertEquals(1.5f, MusicRemovalEngine.clampPlaybackSpeed(2.0f, true), 0.001f)
        assertEquals(1.5f, MusicRemovalEngine.clampPlaybackSpeed(3.0f, true), 0.001f)
        assertEquals(2.0f, MusicRemovalEngine.clampPlaybackSpeed(2.0f, false), 0.001f)
    }

    @Test
    fun `unloaded model, throwing model, or unloaded VAD gate fail closed into Overload Mute`() {
        val unloadedModelEngine =
            MusicRemovalEngine(modelProvider = { FakeSpeechModel(isLoaded = false) })
        val unloadedOut = unloadedModelEngine.processHop(constantHop(0.5f), enabled = true)
        assertTrue(unloadedOut.isMuted)
        assertTrue(unloadedModelEngine.isMutedForOverload)
        assertTrue(unloadedOut.samples.all { it == 0f })

        val throwingEngine =
            MusicRemovalEngine(
                modelProvider = { FakeSpeechModel { throw IllegalStateException("ORT boom") } },
            )
        val throwingOut = throwingEngine.processHop(constantHop(0.5f), enabled = true)
        assertTrue(throwingOut.isMuted)
        assertTrue(throwingEngine.isMutedForOverload)
        assertTrue(throwingOut.samples.all { it == 0f })

        val unloadedGate =
            object : SpeechGate {
                override val isLoaded: Boolean = false

                override fun prepare(sampleRate: Int, hopSize: Int): Int = 1

                override fun processHop(
                    hop: FloatArray,
                    sampleRate: Int,
                    frameRatio: Float,
                ): Pair<Float, Float> = 1f to 1f

                override fun reset() = Unit

                override fun close() = Unit
            }
        val primary = FakeSpeechModel()
        val unloadedGateEngine =
            MusicRemovalEngine(
                modelProvider = { primary },
                gateProvider = { unloadedGate },
            )
        val gateOut = unloadedGateEngine.processHop(constantHop(0.5f), enabled = true)
        assertTrue(gateOut.isMuted)
        assertTrue(unloadedGateEngine.isMutedForOverload)
        assertEquals(0, primary.processHopCalls)
        assertTrue(gateOut.samples.all { it == 0f })
    }

    @Test
    fun `speed-aware overload threshold allows 1_3x at 1_0x speed but mutes at 1_5x speed`() {
        val clock = AtomicLong(0L)
        var speed = 1.0f
        // 16 kHz / 512 hop = 32 ms; 24.6 ms elapsed -> ~1.30x realtime (> 1.2x, < 1.8x)
        val slowModel =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                clock.addAndGet(24_600_000L)
                hop.copyOf()
            }
        val engine =
            MusicRemovalEngine(
                modelProvider = { slowModel },
                playbackSpeedProvider = { speed },
                config =
                    MusicRemovalConfig(
                        nanoTimeProvider = { clock.get() },
                        overloadSustainHops = 2,
                        enablePostFilter = false,
                        logTelemetry = false,
                    ),
            )

        repeat(4) {
            val out = engine.processHop(constantHop(0.4f), enabled = true)
            assertFalse("1.3x processing must not mute at 1.0x speed", out.isMuted)
        }
        assertFalse(engine.isMutedForOverload)

        speed = 1.5f
        repeat(3) { engine.processHop(constantHop(0.4f), enabled = true) }
        assertTrue(
            "1.3x processing must trigger Overload Mute at 1.5x speed",
            engine.isMutedForOverload,
        )
    }

    @Test
    fun `transient single-hop jitter does not trigger false Overload Mute`() {
        val clock = AtomicLong(0L)
        var hopIndex = 0
        val jitterModel =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                val elapsedNs = if (hopIndex++ == 2) 100_000_000L else 5_000_000L
                clock.addAndGet(elapsedNs)
                hop.copyOf()
            }
        val engine =
            MusicRemovalEngine(
                modelProvider = { jitterModel },
                config =
                    MusicRemovalConfig(
                        nanoTimeProvider = { clock.get() },
                        overloadSustainHops = 4,
                        enablePostFilter = false,
                        logTelemetry = false,
                    ),
            )

        repeat(8) {
            val out = engine.processHop(constantHop(0.3f), enabled = true)
            assertFalse("Single-hop spike should not mute", out.isMuted)
        }
        assertFalse(engine.isMutedForOverload)
    }

    @Test
    fun `sustained slow primary downshifts to fallback without muting, and mutes when fallback also fails`() {
        val clock = AtomicLong(0L)
        val slowPrimary =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                clock.addAndGet(60_000_000L)
                FloatArray(hop.size) { 0.3f }
            }
        val fastFallback =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                clock.addAndGet(5_000_000L)
                FloatArray(hop.size) { 0.8f }
            }
        val engine =
            MusicRemovalEngine(
                modelProvider = { slowPrimary },
                fallbackModelProvider = { fastFallback },
                config =
                    MusicRemovalConfig(
                        nanoTimeProvider = { clock.get() },
                        overloadSustainHops = 4,
                        downshiftSustainHops = 2,
                        enablePostFilter = false,
                        logTelemetry = false,
                    ),
            )

        // Two slow hops trigger downshift on hop 2 without ever muting
        val hop1 = engine.processHop(constantHop(0.3f), enabled = true)
        val hop2 = engine.processHop(constantHop(0.3f), enabled = true)
        assertFalse(hop1.isMuted)
        assertFalse(hop2.isMuted)
        assertTrue(engine.isUsingFallbackModel)
        assertFalse(engine.isMutedForOverload)

        // First fallback hop crossfades from last primary sample (0.3f) to fallback (0.8f)
        val hop3 = engine.processHop(constantHop(0.3f), enabled = true)
        assertFalse(hop3.isMuted)
        assertEquals(0.3f, hop3.samples.first(), 1e-4f)
        assertEquals(0.8f, hop3.samples.last(), 1e-4f)

        // When both primary and fallback are overloaded, Overload Mute activates
        val slowFallback =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                clock.addAndGet(60_000_000L)
                hop.copyOf()
            }
        var reportedDevice: String? = null
        val overloadedEngine =
            MusicRemovalEngine(
                modelProvider = { slowPrimary },
                fallbackModelProvider = { slowFallback },
                config =
                    MusicRemovalConfig(
                        nanoTimeProvider = { clock.get() },
                        overloadSustainHops = 2,
                        downshiftSustainHops = 1,
                        enablePostFilter = false,
                        logTelemetry = false,
                        onOverloadMuted = { device, _ -> reportedDevice = device },
                    ),
            )
        repeat(4) { overloadedEngine.processHop(constantHop(0.3f), enabled = true) }
        assertTrue(overloadedEngine.isUsingFallbackModel)
        assertTrue(overloadedEngine.isMutedForOverload)
        assertTrue(!reportedDevice.isNullOrBlank())
    }

    @Test
    fun `primary load failure falls back cleanly even when fallback has a different geometry`() {
        val unloadedPrimary = FakeSpeechModel(sampleRate = 48000, hopSize = 512, isLoaded = false)
        val fallback16k =
            FakeSpeechModel(sampleRate = 16000, hopSize = 160, isLoaded = true) { hop ->
                FloatArray(hop.size) { 0.5f }
            }
        val engine =
            MusicRemovalEngine(
                modelProvider = { unloadedPrimary },
                fallbackModelProvider = { fallback16k },
                config = MusicRemovalConfig(enablePostFilter = false, logTelemetry = false),
            )
        engine.ensureGeometry()

        var sawSignal = false
        repeat(6) {
            val out = engine.processHop(constantHop(0.5f, engine.hopSize), enabled = true)
            assertFalse(out.isMuted)
            assertEquals(engine.hopSize, out.validSamples)
            if (out.samples.any { it > 0.2f }) sawSignal = true
        }
        assertTrue(engine.isUsingFallbackModel)
        assertTrue("Expected adapted fallback speech output", sawSignal)
    }

    @Test
    fun `switching Music Removal off and on crossfades off and fades only processed speech in from silence`() {
        val model = FakeSpeechModel { FloatArray(it.size) { 0.6f } }
        val engine =
            MusicRemovalEngine(
                modelProvider = { model },
                config = MusicRemovalConfig(enablePostFilter = false, logTelemetry = false),
            )

        // ON -> OFF crossfades from last processed sample (0.6f) to passthrough (-0.4f) without
        // invoking the model
        engine.processHop(constantHop(0.2f), enabled = true, previousEnabled = null)
        val callsWhileOn = model.processHopCalls
        val offHop = engine.processHop(constantHop(-0.4f), enabled = false, previousEnabled = true)
        assertEquals(callsWhileOn, model.processHopCalls)
        assertEquals(0.6f, offHop.samples.first(), 1e-4f)
        assertEquals(-0.4f, offHop.samples.last(), 1e-4f)

        // OFF -> ON fades processed speech (0.6f) in from silence (0f), never leaking original
        // audio
        val onHop = engine.processHop(constantHop(-0.9f), enabled = true, previousEnabled = false)
        assertTrue(onHop.startsFromSilence)
        assertEquals(0f, onHop.samples.first(), 1e-6f)
        assertEquals(0.6f, onHop.samples.last(), 1e-4f)
    }

    @Test
    fun `Overload Mute recovery fades processed speech in from silence`() {
        val clock = AtomicLong(0L)
        var slow = true
        val model =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512) { hop ->
                clock.addAndGet(if (slow) 80_000_000L else 2_000_000L)
                FloatArray(hop.size) { 0.7f }
            }
        val engine =
            MusicRemovalEngine(
                modelProvider = { model },
                config =
                    MusicRemovalConfig(
                        nanoTimeProvider = { clock.get() },
                        overloadSustainHops = 1,
                        enablePostFilter = false,
                        logTelemetry = false,
                    ),
            )

        val muted = engine.processHop(constantHop(0.7f), enabled = true)
        assertTrue(muted.isMuted)
        assertTrue(muted.samples.all { it == 0f })

        slow = false
        val recovered = engine.processHop(constantHop(0.7f), enabled = true)
        assertFalse(recovered.isMuted)
        assertTrue(recovered.startsFromSilence)
        assertEquals(0f, recovered.samples.first(), 1e-6f)
        assertEquals(0.7f, recovered.samples.last(), 1e-4f)
    }

    @Test
    fun `gate lookahead preserves word onsets and reset drops pre-seek audio`() {
        val lookahead = 3
        val openAtCall = 5
        val engine =
            MusicRemovalEngine(
                modelProvider = { FakeSpeechModel(sampleRate = 48000, hopSize = 512) },
                gateProvider = { ScriptedGate(lookaheadHops = lookahead, openAtCall = openAtCall) },
                config = MusicRemovalConfig(enablePostFilter = false, logTelemetry = false),
            )

        val outputs =
            List(9) { n ->
                engine.processHop(constantHop((n + 1) / 100f), enabled = true).samples.copyOf()
            }

        for (n in 0 until openAtCall) {
            assertTrue("Expected hop $n silent while gate closed", outputs[n].all { it == 0f })
        }
        val fadeIn = outputs[openAtCall]
        val fadedSource = (openAtCall - lookahead + 1) / 100f
        assertEquals(0f, fadeIn.first(), 1e-6f)
        assertEquals(fadedSource, fadeIn.last(), 1e-6f)
        for (n in openAtCall + 1 until outputs.size) {
            val expected = (n - lookahead + 1) / 100f
            assertTrue(outputs[n].all { abs(it - expected) < 1e-6f })
        }

        engine.reset()
        repeat(lookahead) { n ->
            val afterReset = engine.processHop(constantHop(0.9f), enabled = true).samples
            assertTrue("Expected hop $n after reset silent", afterReset.all { abs(it) < 1e-6f })
        }
    }

    @Test
    fun `post-filter preserves speech band and matches delayed lookahead output`() {
        val lookahead = 4
        val direct =
            MusicRemovalEngine(
                modelProvider = { FakeSpeechModel(sampleRate = 48000, hopSize = 512) },
                gateProvider = { ScriptedGate(lookaheadHops = 0) },
                config = MusicRemovalConfig(enablePostFilter = true, logTelemetry = false),
            )
        val delayed =
            MusicRemovalEngine(
                modelProvider = { FakeSpeechModel(sampleRate = 48000, hopSize = 512) },
                gateProvider = { ScriptedGate(lookaheadHops = lookahead) },
                config = MusicRemovalConfig(enablePostFilter = true, logTelemetry = false),
            )

        var sampleIdx = 0
        fun nextHop() =
            FloatArray(512) {
                val t = sampleIdx++.toDouble() / 48000.0
                (0.5 * sin(2.0 * PI * 1000.0 * t) + 0.2 * sin(2.0 * PI * 310.0 * t)).toFloat()
            }

        val totalHops = 32
        val directOut = ArrayList<FloatArray>()
        val delayedOut = ArrayList<FloatArray>()
        repeat(totalHops) {
            val hop = nextHop()
            directOut.add(direct.processHop(hop, enabled = true).samples.copyOf())
            delayedOut.add(delayed.processHop(hop, enabled = true).samples.copyOf())
        }
        val settleHops = 16
        for (n in lookahead + settleHops until totalHops) {
            assertArrayEquals(
                "Delayed output $n must match direct output ${n - lookahead}",
                directOut[n - lookahead],
                delayedOut[n],
                1e-4f,
            )
        }
        assertTrue(rms(delayedOut.last()) > 0.1f)
    }

    @Test
    fun `drainEndOfStream emits partial final hop plus exact latencySamples and stops on cancellation`() {
        val lookaheadHops = 2
        val modelLatency = 256
        val model =
            FakeSpeechModel(sampleRate = 16000, hopSize = 512, latencySamples = modelLatency)
        val engine =
            MusicRemovalEngine(
                modelProvider = { model },
                gateProvider = { ScriptedGate(lookaheadHops = lookaheadHops) },
                config = MusicRemovalConfig(enablePostFilter = true, logTelemetry = false),
            )
        engine.processHop(constantHop(0.5f), enabled = true)
        // modelLatency (256) + SpeechPostFilter.OUTPUT_DELAY_SAMPLES (256) + lookahead (2 * 512)
        assertEquals(1536, engine.latencySamples)

        val emitted = ArrayList<Int>()
        engine.drainEndOfStream(
            finalHop = constantHop(0.25f),
            validSamples = 100,
            enabled = true,
            previousEnabled = true,
        ) { chunk ->
            emitted.add(chunk.validSamples)
            true
        }
        assertEquals(100 + 1536, emitted.sum())

        var cancelCallbacks = 0
        engine.drainEndOfStream(
            finalHop = constantHop(0.25f),
            validSamples = 100,
            enabled = true,
            previousEnabled = true,
        ) {
            cancelCallbacks++
            false
        }
        assertEquals(1, cancelCallbacks)
    }

    @Test
    fun `real bundled FastEnhancer Small, Tiny, and Silero VAD gate musical tones to silence at 16 kHz`() {
        val smallBytes = loadAssetBytes(OrtSpeechRuntime.ASSET_FILENAME)
        val tinyBytes = loadAssetBytes(OrtSpeechRuntime.FALLBACK_ASSET_FILENAME)
        val vadBytes = loadAssetBytes(OrtSpeechRuntime.VAD_ASSET_FILENAME)

        val vadGate = SileroVadGate(vadBytes)
        val engine =
            MusicRemovalEngine(
                modelProvider = { FastEnhancerSpeechModel(smallBytes) },
                fallbackModelProvider = { FastEnhancerSpeechModel(tinyBytes) },
                gateProvider = { vadGate },
                config = MusicRemovalConfig(logTelemetry = false),
            )
        try {
            val geometry = engine.ensureGeometry()
            assertEquals(16000, geometry.sampleRate)
            assertEquals(512, geometry.hopSize)

            // 1st hop at 16 kHz (512 samples) must evaluate Silero VAD natively
            val firstHop = sineHop(440.0, 16000, 512, startSample = 0, amplitude = 0.4f)
            engine.processHop(firstHop, enabled = true)
            assertTrue(
                "Expected Silero VAD to evaluate on hop 1 at 16 kHz, prob=${vadGate.lastSpeechProb}",
                vadGate.lastSpeechProb > 0f,
            )
            // Exact total pipeline latency: 256 (model) + 256 (post-filter) + 512 (1-hop lookahead)
            assertEquals(1024, engine.latencySamples)

            var lastOut = FloatArray(0)
            for (h in 1..15) {
                val hop = sineHop(440.0, 16000, 512, startSample = h * 512, amplitude = 0.4f)
                lastOut = engine.processHop(hop, enabled = true).samples.copyOf()
            }
            assertTrue(
                "Expected 440 Hz tone gated to silence, rms=${rms(lastOut)}",
                rms(lastOut) < 0.01f,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `AudioResampler 48k-to-16k decimation preserves speech band and suppresses out-of-band aliasing`() {
        fun measureDecimatedRms(freqHz: Double): Float {
            val resampler = AudioResampler(fromRate = 48000, toRate = 16000)
            var sumSq = 0.0
            var total = 0
            for (hopIdx in 0 until 8) {
                val hop = sineHop(freqHz, 48000, 512, startSample = hopIdx * 512, amplitude = 0.4f)
                val count = resampler.resample(hop, hop.size, mutateInput = false)
                if (hopIdx >= 3) {
                    for (i in 0 until count) {
                        val s = resampler.outputBuffer[i]
                        sumSq += s * s
                    }
                    total += count
                }
            }
            return sqrt(sumSq / total.coerceAtLeast(1)).toFloat()
        }

        val speechRms = measureDecimatedRms(1000.0)
        assertTrue("Expected 1 kHz speech tone near ~0.28 RMS, got $speechRms", speechRms > 0.25f)

        val alias12k = measureDecimatedRms(12000.0)
        val alias14k = measureDecimatedRms(14000.0)
        assertTrue("Expected 12 kHz attenuated < 0.01 RMS, got $alias12k", alias12k < 0.01f)
        assertTrue("Expected 14 kHz attenuated < 0.003 RMS, got $alias14k", alias14k < 0.003f)
    }
}
