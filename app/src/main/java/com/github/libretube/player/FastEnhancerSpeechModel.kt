package com.github.libretube.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.nio.FloatBuffer

/**
 * Streaming FastEnhancer speech-enhancement model.
 *
 * The bundled models run at 16 kHz with a 256-sample model hop; one [processHop] call feeds
 * [HOP_SIZE] samples through as many model hops as that takes, so the rest of the pipeline works on
 * 512-sample hops whatever the model's own hop is.
 */
class FastEnhancerSpeechModel(
    modelBytes: ByteArray,
    override val sampleRate: Int = SAMPLE_RATE,
) : SpeechModel {

    private var session: OrtSpeechRuntime.Session? = null
    private var modelHopSize: Int = HOP_SIZE
    private var wavInSize: Int = HOP_SIZE
    private var wavInArray: FloatArray = FloatArray(HOP_SIZE)
    private var wavInDirectBuffer: FloatBuffer? = null
    private var cacheBuffers: List<FloatBuffer> = emptyList()

    private val outputHopBuf = FloatArray(HOP_SIZE)

    override val hopSize: Int = HOP_SIZE

    // The model's STFT overlap (n_fft - hop) equals its hop for every bundled export
    override val latencySamples: Int
        get() = modelHopSize

    override var isLoaded: Boolean = false
        private set

    init {
        runCatching {
            // One intra-op thread: a hop is well under a millisecond of work spread over ~250
            // tiny ops, so waking a second (non-spinning) thread costs more than it saves, and
            // inference then runs entirely on the caller's audio-priority thread.
            val ortSession = OrtSpeechRuntime.openSession(modelBytes, intraOpThreads = 1)
            this.session = ortSession
            val inputInfo = ortSession.inputInfo

            val wavInfo =
                inputInfo["wav_in"]?.info as? TensorInfo
                    ?: throw IllegalArgumentException("Missing wav_in tensor input")
            val inSize = wavInfo.shape.lastPositiveDimOr(HOP_SIZE)
            val outSize =
                (ortSession.outputInfo["wav_out"]?.info as? TensorInfo)
                    ?.shape
                    ?.lastPositiveDimOr(inSize) ?: inSize
            require(outSize > 0 && HOP_SIZE % outSize == 0) {
                "Model hop $outSize does not divide pipeline hop $HOP_SIZE"
            }
            require(inSize == outSize || inSize == outSize * 2) {
                "Unsupported wav_in size $inSize for model hop $outSize"
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

            this.modelHopSize = outSize
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
        var offset = 0
        while (offset < HOP_SIZE) {
            runModelHop(hop, offset)
            offset += modelHopSize
        }
        return outputHopBuf
    }

    /**
     * Runs one model hop on hop[offset, offset + modelHopSize) into the same span of the output.
     */
    private fun runModelHop(hop: FloatArray, offset: Int) {
        val ortSession = session ?: throw IllegalStateException("ORT session closed")
        val wavBuf = wavInDirectBuffer ?: throw IllegalStateException("ORT input buffer closed")
        val modelHop = modelHopSize

        if (wavInSize == modelHop * 2) {
            System.arraycopy(wavInArray, modelHop, wavInArray, 0, modelHop)
            System.arraycopy(hop, offset, wavInArray, modelHop, modelHop)
        } else {
            System.arraycopy(hop, offset, wavInArray, 0, modelHop)
        }

        wavBuf.clear()
        wavBuf.put(wavInArray, 0, wavInSize)
        wavBuf.flip()

        ortSession.run().use { result ->
            val wavOutTensor = result.get(0) as OnnxTensor
            wavOutTensor.floatBuffer.get(outputHopBuf, offset, modelHop)

            for (i in cacheBuffers.indices) {
                val cacheOutTensor = result.get(i + 1) as OnnxTensor
                val outBuf = cacheOutTensor.floatBuffer
                val targetBuf = cacheBuffers[i]
                targetBuf.clear()
                targetBuf.put(outBuf)
                targetBuf.flip()
            }
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
        const val SAMPLE_RATE = 16000
        const val HOP_SIZE = 512

        private fun LongArray.lastPositiveDimOr(fallback: Int): Int =
            lastOrNull()?.takeIf { it > 0 }?.toInt() ?: fallback
    }
}
