package com.github.libretube.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplingLowPassTest {

    private fun rmsAfterFiltering(filter: ResamplingLowPass, freqHz: Double): Double {
        filter.reset()
        val buffer = FloatArray(4800)
        var sampleIdx = 0
        var sumSq = 0.0
        var counted = 0
        repeat(4) { block ->
            for (i in buffer.indices) {
                val t = sampleIdx++.toDouble() / filter.sampleRate
                buffer[i] = (0.4 * sin(2.0 * PI * freqHz * t)).toFloat()
            }
            filter.processInPlace(buffer, buffer.size)
            // Skip the first block while the filter settles
            if (block > 0) {
                for (v in buffer) sumSq += v * v
                counted += buffer.size
            }
        }
        return sqrt(sumSq / counted)
    }

    @Test
    fun `48 kHz to 16 kHz filter passes the speech band and removes what would alias into it`() {
        val filter = ResamplingLowPass.between(48000, 16000)!!
        assertEquals(48000, filter.sampleRate)
        assertEquals(7000.0, filter.cutoffHz, 1e-9)

        val inputRms = 0.4 / sqrt(2.0)
        assertEquals(inputRms, rmsAfterFiltering(filter, 1000.0), 0.005)
        assertEquals(inputRms, rmsAfterFiltering(filter, 4000.0), 0.005)

        // 12 kHz would fold onto 4 kHz and 14 kHz onto 2 kHz after 3:1 decimation
        val alias12k = rmsAfterFiltering(filter, 12000.0)
        val alias14k = rmsAfterFiltering(filter, 14000.0)
        assertTrue(
            "Expected >= 34 dB attenuation at 12 kHz, got rms $alias12k",
            alias12k < inputRms * 0.02,
        )
        assertTrue(
            "Expected >= 46 dB attenuation at 14 kHz, got rms $alias14k",
            alias14k < inputRms * 0.005,
        )
    }

    @Test
    fun `filter runs at the higher of the two rates and is not needed when rates match`() {
        assertNull(ResamplingLowPass.between(16000, 16000))
        val upsampling = ResamplingLowPass.between(16000, 44100)!!
        assertEquals(44100, upsampling.sampleRate)
        assertEquals(7000.0, upsampling.cutoffHz, 1e-9)
    }
}
