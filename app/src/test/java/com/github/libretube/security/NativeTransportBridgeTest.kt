package com.github.libretube.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTransportBridgeTest {
    @Test
    fun `debug fallback resolves expected transport constants and zero UMP mask`() {
        assertEquals("/youtubei/v1/player", NativeTransportBridge.playerPathToken())
        assertEquals(
            "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
            NativeTransportBridge.visitorIdEndpoint(),
        )
        assertEquals("streamingData", NativeTransportBridge.streamingDataKey())
        assertEquals("playabilityStatus", NativeTransportBridge.playabilityStatusKey())
        assertEquals(0, NativeTransportBridge.umpPartMask())
    }

    @Test
    fun `debug fallback reports integrity as verified`() {
        assertTrue(NativeTransportBridge.isIntegrityVerified)
    }
}
