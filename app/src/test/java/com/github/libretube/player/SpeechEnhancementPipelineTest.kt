package com.github.libretube.player

import kotlin.math.PI
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

    @Test
    fun `pipeline adapts differing fallback model geometry without changing external sampleRate or hopSize`() {
        val primary = FakeSpeechModel(sampleRate = 48000, hopSize = 512)
        val fallback = FakeSpeechModel(sampleRate = 16000, hopSize = 256)
        val pipeline =
            SpeechEnhancementPipeline(
                primaryModel = primary,
                fallbackModelProvider = { fallback },
            )

        assertTrue(pipeline.downshiftToFallback())
        assertTrue(pipeline.isUsingFallback)
        assertEquals(48000, pipeline.sampleRate)
        assertEquals(512, pipeline.hopSize)

        var sampleIdx = 0
        var lastOut = FloatArray(0)
        repeat(12) {
            val hop =
                FloatArray(512) {
                    val t = sampleIdx++.toDouble() / 48000.0
                    (0.5 * sin(2.0 * PI * 1000.0 * t)).toFloat()
                }
            lastOut = pipeline.processHop(hop)
            assertEquals(512, lastOut.size)
        }

        assertEquals(0, primary.processHopCalls)
        assertTrue("Expected fallback model to be invoked", fallback.processHopCalls > 0)
        val rms = kotlin.math.sqrt(lastOut.map { it * it }.average())
        assertTrue("Expected adapted fallback output to preserve 1kHz signal, rms=$rms", rms > 0.1)

        pipeline.reset()
        assertEquals(2, primary.resetCalls)
        assertEquals(1, fallback.resetCalls)
    }
}
