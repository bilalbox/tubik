package com.github.libretube.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SileroVadGateTest {

    @Test
    fun `gain holds at its target instead of dipping once the gate is fully open or closed`() {
        val attack = 0.55f
        val release = 0.18f

        var gain = 0f
        val opening =
            List(6) { SileroVadGate.stepGain(gain, 1f, attack, release).also { gain = it } }
        assertEquals(listOf(0.55f, 1f, 1f, 1f, 1f, 1f), opening)

        val closing =
            List(8) { SileroVadGate.stepGain(gain, 0f, attack, release).also { gain = it } }
        assertEquals(0.82f, closing[0], 1e-6f)
        assertEquals(0.64f, closing[1], 1e-6f)
        assertEquals(listOf(0f, 0f), closing.takeLast(2))
    }

    @Test
    fun `gate timing is defined in milliseconds so lookahead scales with the hop duration`() {
        // The gate's timing does not depend on the model loading, so unreadable bytes are fine
        val gate = SileroVadGate(ByteArray(16) { 0x42 })
        try {
            // 43 ms of lookahead: 4 hops of 10.7 ms at 48 kHz, 1 hop of 32 ms at 16 kHz
            assertEquals(4, gate.prepare(sampleRate = 48000, hopSize = 512))
            assertEquals(1, gate.prepare(sampleRate = 16000, hopSize = 512))
            // Never less than one hop, however long the hop is
            assertEquals(1, gate.prepare(sampleRate = 8000, hopSize = 2048))
        } finally {
            gate.close()
        }
    }
}
