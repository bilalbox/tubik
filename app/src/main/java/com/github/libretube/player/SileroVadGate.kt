package com.github.libretube.player

import ai.onnxruntime.OnnxTensor
import android.util.Log
import java.nio.FloatBuffer

/**
 * Streaming Silero VAD v5 gate.
 *
 * Evaluates Silero VAD every 512 samples at 16 kHz (32 ms) with a 64-sample context window: one
 * evaluation per hop for the bundled 16 kHz models, or via anti-aliased [AudioResampler] decimation
 * for other sample rates. Gate timing is defined in milliseconds and converted to hops for the
 * geometry given to [prepare].
 */
class SileroVadGate(
    vadModelBytes: ByteArray,
) : SpeechGate {

    private class Timing(sampleRate: Int, hopSize: Int) {
        private val hopMs = 1000.0 * hopSize / sampleRate

        // Silero reports speech up to 32 ms after it starts and the gain then needs to ramp up, so
        // without this lead the first consonant of every word is faded in.
        val lookaheadHops = Math.round(LOOKAHEAD_MS / hopMs).toInt().coerceAtLeast(1)

        // Decisions lead the audio by lookaheadHops, so the hold has to outlast that lead as well.
        val hangoverHops = Math.round(HANGOVER_MS / hopMs).toInt().coerceAtLeast(1) + lookaheadHops
        val attackStep = (hopMs / ATTACK_MS).toFloat().coerceAtMost(1f)
        val releaseStep = (hopMs / RELEASE_MS).toFloat().coerceAtMost(1f)
    }

    private var timing = Timing(DEFAULT_SAMPLE_RATE, DEFAULT_HOP_SIZE)
    private var ortSession: OrtSpeechRuntime.Session? = null
    private var inputBuffer: FloatBuffer? = null
    private var stateBuffer: FloatBuffer? = null
    private var resampler: AudioResampler? = null

    private val context16k = FloatArray(CONTEXT_16K)
    private val frame16kAccumulator = FloatArray(FRAME_16K)
    private var frame16kFill = 0

    var lastSpeechProb: Float = 0f
        private set

    private var hangoverHops: Int = 0
    private var currentGain: Float = 0f

    override var isLoaded: Boolean = false
        private set

    init {
        runCatching {
            val session = OrtSpeechRuntime.openSession(vadModelBytes, intraOpThreads = 1)
            val inBuf =
                session.bindDirectFloatInput(
                    "input",
                    longArrayOf(1L, (CONTEXT_16K + FRAME_16K).toLong()),
                )
            val stBuf = session.bindDirectFloatInput("state", longArrayOf(2L, 1L, 128L))
            session.bindScalarLongInput("sr", SAMPLE_RATE_16K)
            this.ortSession = session
            this.inputBuffer = inBuf
            this.stateBuffer = stBuf
            this.isLoaded = true
        }
            .onFailure { t ->
                runCatching { Log.e(TAG, "Failed to load Silero VAD ONNX model", t) }
                this.isLoaded = false
                close()
            }
    }

    override fun prepare(sampleRate: Int, hopSize: Int): Int {
        if (sampleRate > 0 && hopSize > 0) {
            timing = Timing(sampleRate, hopSize)
        }
        return timing.lookaheadHops
    }

    override fun processHop(
        hop: FloatArray,
        sampleRate: Int,
        frameRatio: Float,
    ): Pair<Float, Float> {
        if (!isLoaded || hop.isEmpty() || sampleRate <= 0) return 1f to 1f

        var sumSq = 0.0
        for (sample in hop) {
            sumSq += sample * sample
        }
        if (sampleRate == SAMPLE_RATE_16K.toInt()) {
            for (sample in hop) {
                pushVadSample16k(sample)
            }
        } else {
            val activeResampler =
                AudioResampler.reuseOrCreate(resampler, sampleRate, SAMPLE_RATE_16K.toInt())
            resampler = activeResampler
            val count = activeResampler.resample(hop, hop.size, mutateInput = false)
            val samples16k = activeResampler.outputBuffer
            for (i in 0 until count) {
                pushVadSample16k(samples16k[i])
            }
        }

        val rms = kotlin.math.sqrt(sumSq / hop.size).toFloat()
        return updateGainState(rms, frameRatio)
    }

    private fun pushVadSample16k(sample: Float) {
        frame16kAccumulator[frame16kFill++] = sample.coerceIn(-1f, 1f)
        if (frame16kFill == FRAME_16K) {
            lastSpeechProb = runVadFrame16k(frame16kAccumulator)
            frame16kFill = 0
        }
    }

    private fun updateGainState(rms: Float, frameRatio: Float): Pair<Float, Float> {
        val speechActive =
            lastSpeechProb >= SPEECH_ONSET_PROB &&
                rms >= MIN_SPEECH_RMS &&
                frameRatio >= MIN_ENHANCEMENT_RATIO_FOR_ONSET

        val speechContinuing =
            lastSpeechProb >= SPEECH_CONTINUE_PROB && rms >= MIN_SPEECH_RMS * 0.5f

        val timing = timing
        if (speechActive) {
            hangoverHops = timing.hangoverHops
        } else if (speechContinuing && hangoverHops > 0) {
            hangoverHops = maxOf(hangoverHops, timing.hangoverHops / 2)
        } else if (hangoverHops > 0) {
            hangoverHops--
        }

        val startGain = currentGain
        val targetGain =
            when {
                speechActive || hangoverHops > 0 -> 1.0f
                else -> 0.0f
            }

        currentGain = stepGain(currentGain, targetGain, timing.attackStep, timing.releaseStep)
        return startGain to currentGain
    }

    private fun runVadFrame16k(frame16k: FloatArray): Float {
        val session = ortSession
        val inBuf = inputBuffer
        val stBuf = stateBuffer
        if (session == null || inBuf == null || stBuf == null) return 1f

        inBuf.clear()
        inBuf.put(context16k, 0, CONTEXT_16K)
        inBuf.put(frame16k, 0, FRAME_16K)
        inBuf.flip()

        System.arraycopy(
            frame16k,
            FRAME_16K - CONTEXT_16K,
            context16k,
            0,
            CONTEXT_16K,
        )

        return runCatching {
            session.run().use { result ->
                val outTensor = result.get(0) as OnnxTensor
                val prob = outTensor.floatBuffer.get(0)

                val stateOutTensor = result.get(1) as OnnxTensor
                stBuf.clear()
                stBuf.put(stateOutTensor.floatBuffer)
                stBuf.flip()

                prob
            }
        }
            .getOrElse { t ->
                runCatching { Log.w(TAG, "Silero VAD inference error", t) }
                0f
            }
    }

    override fun reset() {
        context16k.fill(0f)
        frame16kFill = 0
        resampler?.reset()
        lastSpeechProb = 0f
        hangoverHops = 0
        currentGain = 0f
        stateBuffer?.zeroFill()
    }

    override fun close() {
        runCatching { ortSession?.close() }
        ortSession = null
        inputBuffer = null
        stateBuffer = null
        isLoaded = false
    }

    companion object {
        private const val TAG = "SileroVadGate"

        internal fun stepGain(
            current: Float,
            target: Float,
            attackStep: Float,
            releaseStep: Float,
        ): Float =
            when {
                target > current -> (current + attackStep).coerceAtMost(target)
                target < current -> (current - releaseStep).coerceAtLeast(target)
                else -> current
            }

        const val ASSET_FILENAME = "silero_vad.onnx"

        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_HOP_SIZE = 512
        private const val SAMPLE_RATE_16K = 16000L
        private const val FRAME_16K = 512
        private const val CONTEXT_16K = 64

        private const val SPEECH_ONSET_PROB = 0.42f
        private const val SPEECH_CONTINUE_PROB = 0.25f
        private const val MIN_SPEECH_RMS = 0.003f
        private const val MIN_ENHANCEMENT_RATIO_FOR_ONSET = 0.18f

        private const val LOOKAHEAD_MS = 43.0
        private const val HANGOVER_MS = 128.0
        private const val ATTACK_MS = 19.4
        private const val RELEASE_MS = 59.3
    }
}
