package com.github.libretube.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEnhancementPipelineTest {

    private class FakeSpeechModel(
        override val sampleRate: Int = 48000,
        override val hopSize: Int = 512,
        override var isLoaded: Boolean = true,
        private val transform: (FloatArray) -> FloatArray = { it.copyOf() },
    ) : SpeechModel {
        var processHopCalls = 0
        var resetCalls = 0
        var closeCalls = 0
        var lastInputHop: FloatArray? = null

        override fun processHop(hop: FloatArray): FloatArray {
            require(hop.size == hopSize) { "Expected hop of $hopSize, got ${hop.size}" }
            processHopCalls++
            lastInputHop = hop.copyOf()
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

    private fun constantHop(value: Float) = FloatArray(512) { value }

    @Test
    fun `gate lookahead emits the hop from lookaheadHops calls ago so audio before the gate opened is kept`() {
        val lookahead = 3
        val openAtCall = 5
        val pipeline =
            SpeechEnhancementPipeline(
                primaryModel = FakeSpeechModel(),
                vadGate = ScriptedGate(lookaheadHops = lookahead, openAtCall = openAtCall),
                postFilter = null,
            )

        // Hop n carries the constant (n + 1) / 100 so each output hop identifies its source hop
        val outputs = List(9) { n -> pipeline.processHop(constantHop((n + 1) / 100f)).copyOf() }

        for (n in 0 until openAtCall) {
            assertTrue(
                "Expected hop $n to be silent while gate is closed",
                outputs[n].all { it == 0f },
            )
        }
        // The gate starts opening on call 5; the audio it fades in is hop 5 - 3 = 2, not hop 5
        val fadeIn = outputs[openAtCall]
        val fadedSource = (openAtCall - lookahead + 1) / 100f
        assertEquals(0f, fadeIn.first(), 1e-6f)
        assertEquals(fadedSource, fadeIn.last(), 1e-6f)
        for (n in openAtCall + 1 until outputs.size) {
            val expected = (n - lookahead + 1) / 100f
            assertTrue(
                "Expected output $n to be source hop ${n - lookahead}",
                outputs[n].all { abs(it - expected) < 1e-6f },
            )
        }
    }

    @Test
    fun `gate lookahead only delays the post-filtered output and reset drops audio held from before a seek`() {
        val lookahead = 4
        val direct =
            SpeechEnhancementPipeline(
                primaryModel = FakeSpeechModel(),
                vadGate = ScriptedGate(lookaheadHops = 0),
            )
        val delayed =
            SpeechEnhancementPipeline(
                primaryModel = FakeSpeechModel(),
                vadGate = ScriptedGate(lookaheadHops = lookahead),
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
            directOut.add(direct.processHop(hop).copyOf())
            delayedOut.add(delayed.processHop(hop).copyOf())
        }
        // The delayed post-filter was primed with silent hops, so its per-bin gains start from a
        // different point; compare once that smoothing has died away. What remains is float32
        // rounding noise in the 100 Hz biquads (~1e-5), hence the tolerance.
        val settleHops = 16
        for (n in lookahead + settleHops until totalHops) {
            org.junit.Assert.assertArrayEquals(
                "Expected delayed output $n to equal direct output ${n - lookahead}",
                directOut[n - lookahead],
                delayedOut[n],
                1e-4f,
            )
        }
        val settledRms = kotlin.math.sqrt(delayedOut.last().map { it * it }.average())
        assertTrue(
            "Expected delayed pipeline to pass the signal, rms=$settledRms",
            settledRms > 0.1,
        )

        delayed.reset()
        repeat(lookahead) { n ->
            val out = delayed.processHop(nextHop())
            assertTrue(
                "Expected hop $n after reset to carry no pre-seek audio",
                out.all { abs(it) < 1e-6f },
            )
        }
    }

    @Test
    fun `pipeline exposes primary model geometry and delegates hop processing through postFilter`() {
        val primary = FakeSpeechModel(sampleRate = 16000, hopSize = 512)
        val pipeline = SpeechEnhancementPipeline(primaryModel = primary)

        assertEquals(16000, pipeline.sampleRate)
        assertEquals(512, pipeline.hopSize)
        assertTrue(pipeline.isLoaded)

        // Feed two hops of 1 kHz speech-band sine wave (1st hop fills 1-hop delay line)
        var sampleIdx = 0
        var lastOut = FloatArray(0)
        repeat(4) {
            val hop =
                FloatArray(512) {
                    val t = sampleIdx++.toDouble() / 16000.0
                    (0.5 * sin(2.0 * PI * 1000.0 * t)).toFloat()
                }
            lastOut = pipeline.processHop(hop)
        }

        assertEquals(4, primary.processHopCalls)
        assertEquals(512, lastOut.size)
        val rms = kotlin.math.sqrt(lastOut.map { it * it }.average())
        assertTrue("Expected speech-band signal to pass through pipeline, rms=$rms", rms > 0.1)
    }

    @Test
    fun `pipeline uses native 16kHz VAD without 3-to-1 decimation when model sampleRate is 16000`() {
        val vadFile =
            sequenceOf(
                    java.io.File("src/main/assets/silero_vad.onnx"),
                    java.io.File("app/src/main/assets/silero_vad.onnx"),
                )
                .first { it.exists() }
        val vadGate = SileroVadGate(vadFile.readBytes())
        assertTrue(vadGate.isLoaded)

        val primary16k = FakeSpeechModel(sampleRate = 16000, hopSize = 512)
        val pipeline =
            SpeechEnhancementPipeline(
                primaryModel = primary16k,
                vadGate = vadGate,
            )

        try {
            // 1 hop of 512 samples at 16 kHz is already 1 full 512-sample Silero VAD frame.
            // If 48kHz 3:1 decimation were used by mistake, only 170 samples would accumulate and
            // Silero VAD would not evaluate on hop 1 (lastSpeechProb would remain 0f).
            val firstHop =
                FloatArray(512) { i ->
                    val t = i.toDouble() / 16000.0
                    (0.4 * sin(2.0 * PI * 440.0 * t)).toFloat()
                }
            pipeline.processHop(firstHop)
            assertTrue(
                "Expected Silero VAD to evaluate after 1 hop at 16 kHz, got prob=${vadGate.lastSpeechProb}",
                vadGate.lastSpeechProb > 0f,
            )

            // Non-speech 440 Hz musical tone should remain gated to silence
            var sampleIdx = 512
            var lastOut = FloatArray(0)
            repeat(15) {
                val hop =
                    FloatArray(512) {
                        val t = sampleIdx++.toDouble() / 16000.0
                        (0.4 * sin(2.0 * PI * 440.0 * t)).toFloat()
                    }
                lastOut = pipeline.processHop(hop)
            }
            val rms = kotlin.math.sqrt(lastOut.map { it * it }.average())
            assertTrue("Expected 440Hz tone at 16kHz to be gated to silence, rms=$rms", rms < 0.01)
        } finally {
            pipeline.close()
        }
    }
}
