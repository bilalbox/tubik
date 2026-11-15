package com.github.libretube.player

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object OrtSpeechRuntime {

    private const val TAG = "OrtSpeechRuntime"
    // FastEnhancer Small / Tiny, 16 kHz, trained on DNS-Challenge noise (upstream onnx-dns-v1.0.0)
    const val ASSET_FILENAME = "fastenhancer_s_16k.onnx"
    const val FALLBACK_ASSET_FILENAME = "fastenhancer_t_16k.onnx"
    const val VAD_ASSET_FILENAME = SileroVadGate.ASSET_FILENAME

    private class SharedSpeechModel(
        private val delegate: SpeechModel,
    ) : SpeechModel {
        override val isLoaded: Boolean
            get() = delegate.isLoaded

        override val sampleRate: Int
            get() = delegate.sampleRate

        override val hopSize: Int
            get() = delegate.hopSize

        override val hopDurationNs: Long
            get() = delegate.hopDurationNs

        override fun processHop(hop: FloatArray): FloatArray = delegate.processHop(hop)

        override fun reset() = delegate.reset()

        override fun close() = delegate.reset()

        fun closeUnderlying() {
            runCatching { delegate.close() }
        }
    }

    private class ModelSlot(
        private val assetLabel: String,
    ) {
        @Volatile private var instance: SharedSpeechModel? = null
        private val lock = Any()

        fun getOrCreate(loader: () -> SpeechModel): SpeechModel {
            instance
                ?.takeIf { it.isLoaded }
                ?.let { cached ->
                    return cached
                }
            return synchronized(lock) {
                val existing = instance?.takeIf { it.isLoaded }
                existing ?: loadAndCache(loader)
            }
        }

        private fun loadAndCache(loader: () -> SpeechModel): SpeechModel {
            return runCatching { loader() }
                .fold(
                    onSuccess = { loaded ->
                        if (loaded.isLoaded) {
                            SharedSpeechModel(loaded).also { instance = it }
                        } else {
                            loaded
                        }
                    },
                    onFailure = { t ->
                        runCatching {
                            Log.e(
                                TAG,
                                "Unable to read $assetLabel or $VAD_ASSET_FILENAME from assets",
                                t,
                            )
                        }
                        unloadedSpeechModel()
                    },
                )
        }

        fun clear() {
            synchronized(lock) {
                val existing = instance
                instance = null
                existing?.closeUnderlying()
            }
        }
    }

    private val primarySlot = ModelSlot(ASSET_FILENAME)
    private val fallbackSlot = ModelSlot(FALLBACK_ASSET_FILENAME)

    private fun unloadedSpeechModel(): SpeechModel =
        object : SpeechModel {
            override val isLoaded: Boolean = false
            override val sampleRate: Int = 0
            override val hopSize: Int = 0
            override val hopDurationNs: Long = 0L

            override fun processHop(hop: FloatArray): FloatArray = FloatArray(0)
        }

    fun warmup(context: Context): SpeechModel = fromAssets(context)

    fun warmup(loader: () -> SpeechModel): SpeechModel = getOrCreateShared(loader)

    fun getOrCreateShared(loader: () -> SpeechModel): SpeechModel = primarySlot.getOrCreate(loader)

    fun getOrCreateSharedFallback(loader: () -> SpeechModel): SpeechModel =
        fallbackSlot.getOrCreate(loader)

    fun fromAssets(context: Context): SpeechModel = getOrCreateShared {
        loadAssetModel(context, ASSET_FILENAME)
    }

    fun fallbackFromAssets(context: Context): SpeechModel = getOrCreateSharedFallback {
        loadAssetModel(context, FALLBACK_ASSET_FILENAME)
    }

    internal fun clearSharedInstancesForTests() {
        primarySlot.clear()
        fallbackSlot.clear()
    }

    private fun loadAssetModel(context: Context, assetFilename: String): SpeechModel {
        val appContext = context.applicationContext ?: context
        val modelBytes = appContext.assets.open(assetFilename).use { it.readBytes() }
        val vadBytes = appContext.assets.open(VAD_ASSET_FILENAME).use { it.readBytes() }
        val neuralModel = FastEnhancerSpeechModel(modelBytes = modelBytes)
        if (!neuralModel.isLoaded) {
            neuralModel.close()
            return neuralModel
        }
        val vadGate =
            SileroVadGate(vadBytes).also {
                if (!it.isLoaded) {
                    neuralModel.close()
                    throw IllegalStateException("Silero VAD model failed to load")
                }
            }
        return SpeechEnhancementPipeline(
            primaryModel = neuralModel,
            vadGate = vadGate,
        )
    }

    class Session
    internal constructor(
        private val ortEnv: OrtEnvironment,
        private val ortSession: OrtSession,
        val isXnnpackRequested: Boolean,
        val isXnnpackEnabled: Boolean,
    ) : AutoCloseable {

        private val boundTensors = HashMap<String, OnnxTensor>()

        var allocatedTensorCount: Int = 0
            private set

        val inputInfo: Map<String, NodeInfo>
            get() = ortSession.inputInfo

        val outputInfo: Map<String, NodeInfo>
            get() = ortSession.outputInfo

        fun bindDirectFloatInput(name: String, shape: LongArray): FloatBuffer {
            val elementCount = shape.fold(1L) { acc, dim -> acc * dim }.toInt()
            val directBuffer =
                ByteBuffer.allocateDirect(elementCount * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
            val tensor = OnnxTensor.createTensor(ortEnv, directBuffer, shape)
            bindTensor(name, tensor)
            return directBuffer
        }

        fun bindScalarLongInput(name: String, value: Long) {
            val tensor = OnnxTensor.createTensor(ortEnv, value)
            bindTensor(name, tensor)
        }

        private fun bindTensor(name: String, tensor: OnnxTensor) {
            boundTensors.put(name, tensor)?.let { previous ->
                runCatching { previous.close() }
            }
            allocatedTensorCount++
        }

        fun run(): OrtSession.Result = ortSession.run(boundTensors)

        fun boundTensorIdentities(): Map<String, Int> = boundTensors.mapValues {
            System.identityHashCode(it.value)
        }

        override fun close() {
            val tensors = boundTensors.values.toList()
            boundTensors.clear()
            for (tensor in tensors) {
                runCatching { tensor.close() }
            }
            runCatching { ortSession.close() }
        }
    }

    fun openSession(
        modelBytes: ByteArray,
        intraOpThreads: Int = 2,
        xnnpackRegistrar: (OrtSession.SessionOptions) -> Unit = { it.addXnnpack(emptyMap()) },
    ): Session {
        val ortEnv = OrtEnvironment.getEnvironment()
        val (ortSession, xnnpackEnabled) =
            createSessionWithXnnpackFallback(
                ortEnv = ortEnv,
                modelBytes = modelBytes,
                intraOpThreads = intraOpThreads,
                xnnpackRegistrar = xnnpackRegistrar,
            )
        return Session(
            ortEnv = ortEnv,
            ortSession = ortSession,
            isXnnpackRequested = true,
            isXnnpackEnabled = xnnpackEnabled,
        )
    }

    private fun createSessionWithXnnpackFallback(
        ortEnv: OrtEnvironment,
        modelBytes: ByteArray,
        intraOpThreads: Int,
        xnnpackRegistrar: (OrtSession.SessionOptions) -> Unit,
    ): Pair<OrtSession, Boolean> {
        fun buildBaseOptions(): OrtSession.SessionOptions {
            return OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                runCatching { setMemoryPatternOptimization(true) }
                runCatching { setCPUArenaAllocator(true) }
                runCatching { addConfigEntry("session.intra_op.allow_spinning", "0") }
            }
        }

        val xnnpackAttempt = runCatching {
            buildBaseOptions().use { options ->
                xnnpackRegistrar(options)
                ortEnv.createSession(modelBytes, options) to true
            }
        }
        if (xnnpackAttempt.isSuccess) {
            return xnnpackAttempt.getOrThrow()
        }

        return buildBaseOptions().use { cpuOptions ->
            ortEnv.createSession(modelBytes, cpuOptions) to false
        }
    }
}

internal fun FloatBuffer.zeroFill() {
    clear()
    while (hasRemaining()) {
        put(0f)
    }
    flip()
}
