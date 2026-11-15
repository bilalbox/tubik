package com.github.libretube.player

import ai.onnxruntime.OnnxTensor
import android.util.Log
import java.nio.FloatBuffer

/**
 * Streaming Silero VAD v5 gate.
 *
 * Evaluates Silero VAD every 512 samples at 16 kHz (32 ms) with a 64-sample context window: one
 * evaluation per hop for the bundled 16 kHz models, or after 3:1 decimation of 48 kHz hops. Gate
 * timing is defined in milliseconds and converted to hops for the geometry given to [prepare]. Uses
 * direct native FloatBuffers and a persistent sample-rate tensor via [OrtSpeechRuntime] for
 * zero-copy JNI calls.
 *
 * The bundled model is upstream's 16 kHz-only export (`silero_vad_16k_op15.onnx`): same weights and
 * identical output at 16 kHz as the dual-rate `silero_vad.onnx`, at about half the size and memory.
 */
class SileroVadGate(
    vadModelBytes: ByteArray,
) : SpeechGate {

    /** Gate timing converted from milliseconds to hops of one geometry. */
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

    private class DecimatorBiquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun processSample(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }
    }

    private class AntiAliasCascade(private val stages: Array<DecimatorBiquad>) {
        fun process(sample: Float): Float {
            var y = sample
            for (stage in stages) {
                y = stage.processSample(y)
            }
            return y
        }

        fun reset() {
            for (stage in stages) {
                stage.reset()
            }
        }
    }

    private val context16k = FloatArray(CONTEXT_16K)
    private val frame16kAccumulator = FloatArray(FRAME_16K)

    // 6th-order Butterworth low-pass anti-aliasing filter (3 cascaded biquads, fc = 6800 Hz @ 48
    // kHz)
    private val antiAlias =
        AntiAliasCascade(
            arrayOf(
                createButterworthLowPass(SAMPLE_RATE_48K, ANTI_ALIAS_CUTOFF_HZ, BUTTERWORTH_6_Q0),
                createButterworthLowPass(SAMPLE_RATE_48K, ANTI_ALIAS_CUTOFF_HZ, BUTTERWORTH_6_Q1),
                createButterworthLowPass(SAMPLE_RATE_48K, ANTI_ALIAS_CUTOFF_HZ, BUTTERWORTH_6_Q2),
            ),
        )

    private var frame16kFill = 0
    private var decimatePhase = 0

    var lastSpeechProb: Float = 0f
        private set

    private var hangoverHops: Int = 0
    private var currentGain: Float = 0f

    var isLoaded: Boolean = false
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

    internal fun decimateHop48kForTest(hop48k: FloatArray, out16k: FloatArray): Int {
        var outCount = 0
        for (sample in hop48k) {
            val filtered = antiAlias.process(sample)
            decimatePhase++
            if (decimatePhase == 3) {
                decimatePhase = 0
                if (outCount < out16k.size) {
                    out16k[outCount++] = filtered.coerceIn(-1f, 1f)
                }
            }
        }
        return outCount
    }

    /**
     * Feeds one 48 kHz 512-sample hop of enhanced audio and returns the target VAD gate gain in
     * [0f, 1f] for the start and end of the hop so the caller can apply a click-free linear ramp
     * across the hop.
     */
    fun processHop48k(hop48k: FloatArray, frameRatio: Float): Pair<Float, Float> {
        if (!isLoaded) return 1f to 1f

        var sumSq = 0.0
        for (sample in hop48k) {
            sumSq += sample * sample
            val filtered = antiAlias.process(sample)
            decimatePhase++
            if (decimatePhase == 3) {
                decimatePhase = 0
                pushVadSample16k(filtered)
            }
        }

        val rms = kotlin.math.sqrt(sumSq / hop48k.size).toFloat()
        return updateGainState(rms, frameRatio)
    }

    /**
     * Feeds one native 16 kHz hop of enhanced audio directly into the Silero VAD accumulator
     * without 3:1 decimation or anti-aliasing, returning the target VAD gate gain ramp.
     */
    fun processHop16k(hop16k: FloatArray, frameRatio: Float): Pair<Float, Float> {
        if (!isLoaded || hop16k.isEmpty()) return 1f to 1f

        var sumSq = 0.0
        for (sample in hop16k) {
            sumSq += sample * sample
            pushVadSample16k(sample)
        }

        val rms = kotlin.math.sqrt(sumSq / hop16k.size).toFloat()
        return updateGainState(rms, frameRatio)
    }

    private fun pushVadSample16k(sample: Float) {
        frame16kAccumulator[frame16kFill++] = sample.coerceIn(-1f, 1f)
        if (frame16kFill == FRAME_16K) {
            lastSpeechProb = runVadFrame16k(frame16kAccumulator)
            frame16kFill = 0
        }
    }

    /**
     * Dispatches to native 16 kHz processing, 48 kHz 3:1 decimation, or linear-resampled 16 kHz
     * processing depending on [sampleRate].
     */
    override fun processHop(
        hop: FloatArray,
        sampleRate: Int,
        frameRatio: Float,
    ): Pair<Float, Float> {
        if (!isLoaded || hop.isEmpty()) return 1f to 1f
        return when (sampleRate) {
            SAMPLE_RATE_16K.toInt() -> processHop16k(hop, frameRatio)
            SAMPLE_RATE_48K -> processHop48k(hop, frameRatio)
            else -> processHopResampledTo16k(hop, sampleRate, frameRatio)
        }
    }

    private fun processHopResampledTo16k(
        hop: FloatArray,
        sampleRate: Int,
        frameRatio: Float,
    ): Pair<Float, Float> {
        if (sampleRate <= 0) return 1f to 1f
        var sumSq = 0.0
        for (sample in hop) {
            sumSq += sample * sample
        }
        val step = sampleRate.toDouble() / SAMPLE_RATE_16K.toDouble()
        var pos = 0.0
        while (pos < hop.size) {
            val idx = pos.toInt().coerceIn(0, hop.size - 1)
            val nextIdx = (idx + 1).coerceAtMost(hop.size - 1)
            val frac = (pos - idx).toFloat()
            val sample16k = hop[idx] + (hop[nextIdx] - hop[idx]) * frac
            pushVadSample16k(sample16k)
            pos += step
        }
        val rms = kotlin.math.sqrt(sumSq / hop.size).toFloat()
        return updateGainState(rms, frameRatio)
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
                speechActive -> 1.0f
                hangoverHops > 0 -> 1.0f
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
        decimatePhase = 0
        antiAlias.reset()
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

        /**
         * Moves [current] one hop towards [target] and stops there, so an open gate stays at
         * exactly 1.0 instead of dipping by a release step on every other hop.
         */
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

        // Geometry assumed until prepare() is called
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_HOP_SIZE = 512

        private const val SAMPLE_RATE_48K = 48000
        private const val SAMPLE_RATE_16K = 16000L
        private const val FRAME_16K = 512
        private const val CONTEXT_16K = 64
        private const val ANTI_ALIAS_CUTOFF_HZ = 6800.0
        private const val BUTTERWORTH_6_Q0 = 0.5176380902050415
        private const val BUTTERWORTH_6_Q1 = 0.7071067811865475
        private const val BUTTERWORTH_6_Q2 = 1.9318516525781366

        private fun createButterworthLowPass(
            sampleRate: Int,
            cutoffHz: Double,
            q: Double,
        ): DecimatorBiquad {
            val w0 = 2.0 * kotlin.math.PI * cutoffHz / sampleRate
            val cosW0 = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 - cosW0) / 2.0 / a0).toFloat()
            val b1 = ((1.0 - cosW0) / a0).toFloat()
            val b2 = b0
            val a1 = (-2.0 * cosW0 / a0).toFloat()
            val a2 = ((1.0 - alpha) / a0).toFloat()
            return DecimatorBiquad(b0, b1, b2, a1, a2)
        }

        // Thresholds tuned to reject music/singing/SFX while keeping spoken voice
        private const val SPEECH_ONSET_PROB = 0.42f
        private const val SPEECH_CONTINUE_PROB = 0.25f
        private const val MIN_SPEECH_RMS = 0.003f
        private const val MIN_ENHANCEMENT_RATIO_FOR_ONSET = 0.18f

        // Audio is held back this long behind the gate decision (rounded to whole hops)
        private const val LOOKAHEAD_MS = 43.0
        // Hold after speech to bridge stop consonants (p/t/k) within words
        private const val HANGOVER_MS = 128.0
        // Time for the gain to ramp fully open / fully closed
        private const val ATTACK_MS = 19.4
        private const val RELEASE_MS = 59.3
    }
}
