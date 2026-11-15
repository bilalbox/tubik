package com.github.libretube.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrtSpeechRuntimeTest {

    private fun loadAssetBytes(filename: String): ByteArray {
        return sequenceOf(
                File("src/main/assets/$filename"),
                File("app/src/main/assets/$filename"),
            )
            .first { it.exists() }
            .readBytes()
    }

    @Test
    fun `openSession requests XNNPACK and falls back to CPU provider when registration fails`() {
        val modelBytes = loadAssetBytes("fastenhancer_s_16k.onnx")

        val defaultSession = OrtSpeechRuntime.openSession(modelBytes, intraOpThreads = 2)
        try {
            assertTrue("Expected session to request XNNPACK", defaultSession.isXnnpackRequested)
        } finally {
            defaultSession.close()
        }

        var xnnpackAttempts = 0
        val fallbackSession =
            OrtSpeechRuntime.openSession(
                modelBytes = modelBytes,
                intraOpThreads = 2,
                xnnpackRegistrar = {
                    xnnpackAttempts++
                    throw UnsupportedOperationException("Simulated XNNPACK unavailable on host")
                },
            )
        try {
            assertEquals(1, xnnpackAttempts)
            assertTrue(fallbackSession.isXnnpackRequested)
            assertFalse(fallbackSession.isXnnpackEnabled)
            assertTrue(fallbackSession.inputInfo.containsKey("wav_in"))
        } finally {
            fallbackSession.close()
        }

        val vadBytes = loadAssetBytes("silero_vad.onnx")
        var vadXnnpackAttempts = 0
        val fallbackVadSession =
            OrtSpeechRuntime.openSession(
                modelBytes = vadBytes,
                intraOpThreads = 1,
                xnnpackRegistrar = {
                    vadXnnpackAttempts++
                    throw UnsupportedOperationException("Simulated XNNPACK unavailable on host")
                },
            )
        try {
            assertEquals(1, vadXnnpackAttempts)
            assertTrue(fallbackVadSession.isXnnpackRequested)
            assertFalse(fallbackVadSession.isXnnpackEnabled)
            fallbackVadSession.bindDirectFloatInput("input", longArrayOf(1L, 576L))
            fallbackVadSession.bindDirectFloatInput("state", longArrayOf(2L, 1L, 128L))
            fallbackVadSession.bindScalarLongInput("sr", 16000L)
            fallbackVadSession.run().use { result ->
                val out = result.get(0) as OnnxTensor
                assertEquals(1, out.floatBuffer.remaining())
            }
        } finally {
            fallbackVadSession.close()
        }
    }

    private fun writeSine(buf: FloatBuffer, amplitude: Float) {
        buf.clear()
        for (i in 0 until buf.capacity()) {
            buf.put(
                (amplitude * kotlin.math.sin(2.0 * Math.PI * 180.0 * i / 48000.0)).toFloat(),
            )
        }
        buf.flip()
    }

    @Test
    fun `speech session reuses persistent direct-buffer input tensors across inference runs without re-allocating`() {
        val speechSession =
            OrtSpeechRuntime.openSession(
                loadAssetBytes("fastenhancer_s_16k.onnx"),
                intraOpThreads = 2,
            )
        try {
            val wavInfo = speechSession.inputInfo["wav_in"]!!.info as TensorInfo
            val wavShape = wavInfo.shape.map { if (it > 0) it else 512L }.toLongArray()
            val wavBuf = speechSession.bindDirectFloatInput("wav_in", wavShape)
            assertTrue("Expected wav_in buffer to be direct", wavBuf.isDirect)

            val cacheBufs =
                speechSession.inputInfo.entries
                    .filter { it.key.startsWith("cache_in_") }
                    .sortedBy { it.key.removePrefix("cache_in_").toIntOrNull() ?: Int.MAX_VALUE }
                    .map { (name, nodeInfo) ->
                        val shape =
                            (nodeInfo.info as TensorInfo)
                                .shape
                                .map { dim -> if (dim > 0) dim else 1L }
                                .toLongArray()
                        speechSession.bindDirectFloatInput(name, shape).also { buf ->
                            assertTrue("Expected $name buffer to be direct", buf.isDirect)
                        }
                    }

            val initialTensors = speechSession.boundTensorIdentities()
            assertEquals(6, initialTensors.size)
            assertEquals(6, speechSession.allocatedTensorCount)

            repeat(4) {
                writeSine(wavBuf, 0.25f)
                speechSession.run().use { result ->
                    val outTensor = result.get(0) as OnnxTensor
                    assertEquals(wavBuf.capacity(), outTensor.floatBuffer.remaining())
                    for (i in cacheBufs.indices) {
                        val cacheOut = result.get(i + 1) as OnnxTensor
                        val target = cacheBufs[i]
                        target.clear()
                        target.put(cacheOut.floatBuffer)
                        target.flip()
                    }
                }
            }

            assertEquals(initialTensors, speechSession.boundTensorIdentities())
            assertEquals(6, speechSession.allocatedTensorCount)
        } finally {
            speechSession.close()
        }
    }

    @Test
    fun `VAD session reuses persistent direct-buffer and scalar tensors across inference runs without re-allocating`() {
        val vadSession =
            OrtSpeechRuntime.openSession(loadAssetBytes("silero_vad.onnx"), intraOpThreads = 1)
        try {
            val vadInputBuf = vadSession.bindDirectFloatInput("input", longArrayOf(1L, 576L))
            val vadStateBuf = vadSession.bindDirectFloatInput("state", longArrayOf(2L, 1L, 128L))
            vadSession.bindScalarLongInput("sr", 16000L)
            assertTrue(vadInputBuf.isDirect)
            assertTrue(vadStateBuf.isDirect)

            val initialTensors = vadSession.boundTensorIdentities()
            assertEquals(setOf("input", "state", "sr"), initialTensors.keys)
            assertEquals(3, vadSession.allocatedTensorCount)

            repeat(3) {
                writeSine(vadInputBuf, 0.2f)
                vadSession.run().use { vadResult ->
                    val stateOut = vadResult.get(1) as OnnxTensor
                    vadStateBuf.clear()
                    vadStateBuf.put(stateOut.floatBuffer)
                    vadStateBuf.flip()
                }
            }

            assertEquals(initialTensors, vadSession.boundTensorIdentities())
            assertEquals(3, vadSession.allocatedTensorCount)
        } finally {
            vadSession.close()
        }
    }

    @Test
    fun `FastEnhancerSpeechModel presents 16 kHz 512-sample hops built from two 256-sample model hops`() {
        val model = FastEnhancerSpeechModel(loadAssetBytes(OrtSpeechRuntime.ASSET_FILENAME))
        try {
            assertTrue(model.isLoaded)
            assertEquals(16000, model.sampleRate)
            assertEquals(512, model.hopSize)
            assertEquals(32_000_000L, model.hopDurationNs)
            // The model's own hop, which is also how far its output lags its input
            assertEquals(256, model.latencySamples)

            val out = model.processHop(FloatArray(512) { i -> if (i % 64 == 0) 0.25f else 0f })
            assertEquals(512, out.size)
            assertTrue(out.all { it.isFinite() })
        } finally {
            model.close()
        }
    }

    @Test
    fun `SpeechModel derives hopDurationNs from each model's sampleRate and hopSize`() {
        val model16k =
            object : SpeechModel {
                override val isLoaded: Boolean = true
                override val sampleRate: Int = 16000
                override val hopSize: Int = 160

                override fun processHop(hop: FloatArray): FloatArray = hop
            }
        assertEquals(10_000_000L, model16k.hopDurationNs)

        val failedModel = OrtSpeechRuntime.getOrCreateShared { error("Simulated failure") }
        assertFalse(failedModel.isLoaded)
        assertEquals(0L, failedModel.hopDurationNs)
    }

    @Test
    fun `shared primary and fallback caches are independent, survive close via reset, and clear on test reset`() {
        assertEquals("fastenhancer_s_16k.onnx", OrtSpeechRuntime.ASSET_FILENAME)
        assertEquals("fastenhancer_t_16k.onnx", OrtSpeechRuntime.FALLBACK_ASSET_FILENAME)
        assertEquals("silero_vad.onnx", OrtSpeechRuntime.VAD_ASSET_FILENAME)

        class TrackingSpeechModel(
            override val sampleRate: Int = 16000,
            override val hopSize: Int = 160,
        ) : SpeechModel {
            override var isLoaded: Boolean = true
                private set

            var resetCalls = 0
            var closeCalls = 0

            override fun processHop(hop: FloatArray): FloatArray = hop

            override fun reset() {
                resetCalls++
            }

            override fun close() {
                closeCalls++
                isLoaded = false
            }
        }

        OrtSpeechRuntime.clearSharedInstancesForTests()
        try {
            val failed = OrtSpeechRuntime.getOrCreateShared { error("Simulated asset error") }
            assertFalse(failed.isLoaded)

            var primaryBuilds = 0
            val primaryUnderlying = TrackingSpeechModel()
            val primary1 = OrtSpeechRuntime.warmup {
                primaryBuilds++
                primaryUnderlying
            }
            val primary2 = OrtSpeechRuntime.getOrCreateShared {
                error("Should reuse cached primary")
            }
            assertTrue(primary1 === primary2)
            assertEquals(1, primaryBuilds)

            var fallbackBuilds = 0
            val fallbackUnderlying = TrackingSpeechModel()
            val fallback1 = OrtSpeechRuntime.getOrCreateSharedFallback {
                fallbackBuilds++
                fallbackUnderlying
            }
            val fallback2 = OrtSpeechRuntime.getOrCreateSharedFallback {
                error("Should reuse cached fallback")
            }
            assertTrue(fallback1 === fallback2)
            assertFalse(primary1 === fallback1)
            assertEquals(1, fallbackBuilds)

            primary1.close()
            assertEquals(1, primaryUnderlying.resetCalls)
            assertEquals(0, primaryUnderlying.closeCalls)
            assertTrue(primary1.isLoaded)

            OrtSpeechRuntime.clearSharedInstancesForTests()
            assertEquals(1, primaryUnderlying.closeCalls)
            assertEquals(1, fallbackUnderlying.closeCalls)
        } finally {
            OrtSpeechRuntime.clearSharedInstancesForTests()
        }
    }
}
