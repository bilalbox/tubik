package com.github.libretube.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.nio.FloatBuffer

class FastEnhancerSpeechModel(
    modelBytes: ByteArray,
) : SpeechModel {

    private var session: OrtSpeechRuntime.Session? = null
    private var wavInSize: Int = HOP_SIZE
    private var wavInArray: FloatArray = FloatArray(HOP_SIZE)
    private var wavInDirectBuffer: FloatBuffer? = null
    private var cacheBuffers: List<FloatBuffer> = emptyList()

    private val outputHopBuf = FloatArray(HOP_SIZE)

    override val sampleRate: Int = SAMPLE_RATE

    override val hopSize: Int = HOP_SIZE

    override var isLoaded: Boolean = false
        private set

    init {
        runCatching {
            // 2 intra-op threads with spin-waiting disabled avoids big.LITTLE barrier stalls
            // on mobile SoCs while parallelizing the per-hop conv/attention blocks.
            val ortSession = OrtSpeechRuntime.openSession(modelBytes, intraOpThreads = 2)
            this.session = ortSession
            val inputInfo = ortSession.inputInfo

            val wavInfo =
                inputInfo["wav_in"]?.info as? TensorInfo
                    ?: throw IllegalArgumentException("Missing wav_in tensor input")
            val rawWavShape = wavInfo.shape
            val inSize =
                if (rawWavShape.size >= 2 && rawWavShape[1] > 0) {
                    rawWavShape[1].toInt()
                } else {
                    HOP_SIZE
                }

            val wavBuf = ortSession.bindDirectFloatInput("wav_in", longArrayOf(1L, inSize.toLong()))

            val caches =
                inputInfo.entries
                    .filter { it.key.startsWith("cache_in_") }
                    .sortedBy { it.key.removePrefix("cache_in_").toIntOrNull() ?: Int.MAX_VALUE }
                    .map { (name, nodeInfo) ->
                        val tensorInfo = nodeInfo.info as TensorInfo
                        val shape =
                            tensorInfo.shape.map { dim -> if (dim > 0) dim else 1L }.toLongArray()
                        ortSession.bindDirectFloatInput(name, shape)
                    }

            this.wavInSize = inSize
            this.wavInArray = FloatArray(inSize)
            this.wavInDirectBuffer = wavBuf
            this.cacheBuffers = caches
            this.isLoaded = true
        }
            .onFailure { t ->
                runCatching { Log.e(TAG, "Failed to load FastEnhancer ONNX model", t) }
                this.isLoaded = false
                close()
            }
    }

    @Synchronized
    override fun processHop(hop: FloatArray): FloatArray {
        check(isLoaded) { "FastEnhancerSpeechModel is not loaded" }
        require(hop.size == HOP_SIZE) {
            "Expected hop of $HOP_SIZE, got ${hop.size}"
        }
        return runNeuralHop(hop)
    }

    private fun runNeuralHop(hop: FloatArray): FloatArray {
        val ortSession = session ?: throw IllegalStateException("ORT session closed")
        val wavBuf = wavInDirectBuffer ?: throw IllegalStateException("ORT input buffer closed")

        if (wavInSize == HOP_SIZE * 2) {
            System.arraycopy(wavInArray, HOP_SIZE, wavInArray, 0, HOP_SIZE)
            System.arraycopy(hop, 0, wavInArray, HOP_SIZE, HOP_SIZE)
        } else {
            System.arraycopy(hop, 0, wavInArray, 0, HOP_SIZE)
        }

        wavBuf.clear()
        wavBuf.put(wavInArray, 0, wavInSize)
        wavBuf.flip()

        ortSession.run().use { result ->
            val wavOutTensor = result.get(0) as OnnxTensor
            val wavOutBuffer = wavOutTensor.floatBuffer
            wavOutBuffer.get(outputHopBuf, 0, HOP_SIZE)

            for (i in cacheBuffers.indices) {
                val cacheOutTensor = result.get(i + 1) as OnnxTensor
                val outBuf = cacheOutTensor.floatBuffer
                val targetBuf = cacheBuffers[i]
                targetBuf.clear()
                targetBuf.put(outBuf)
                targetBuf.flip()
            }
            return outputHopBuf
        }
    }

    @Synchronized
    override fun reset() {
        wavInArray.fill(0f)
        wavInDirectBuffer?.zeroFill()
        for (buf in cacheBuffers) {
            buf.zeroFill()
        }
    }

    @Synchronized
    override fun close() {
        isLoaded = false
        runCatching { session?.close() }
        session = null
        wavInDirectBuffer = null
        cacheBuffers = emptyList()
    }

    companion object {
        private const val TAG = "FastEnhancerModel"
        const val SAMPLE_RATE = 48000
        const val HOP_SIZE = 512
    }
}
