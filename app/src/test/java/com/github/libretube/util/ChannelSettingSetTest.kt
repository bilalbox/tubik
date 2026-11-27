package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSettingSetTest {
    private var stored = ""
    private val setting = ChannelSettingSet(read = { stored }, write = { stored = it })

    @Test
    fun `channel IDs and URLs are normalized and persisted as one sorted list`() {
        setting.set("https://www.youtube.com/channel/UC_B", enabled = true)
        setting.set("/channel/UC_A", enabled = true)
        setting.set("UC_B", enabled = true)

        assertEquals("UC_A,UC_B", stored)
        assertTrue(setting.isEnabled("UC_A"))
        assertTrue(setting.isEnabled("https://www.youtube.com/channel/UC_B"))
        assertFalse(setting.isEnabled("UC_C"))
    }

    @Test
    fun `turning off removes the channel and blank IDs are ignored`() {
        stored = "UC_A, UC_B,,"
        setting.set("UC_A", enabled = false)
        setting.set("  ", enabled = true)
        setting.set(null, enabled = true)

        assertEquals(setOf("UC_B"), setting.channelIds())
        assertFalse(setting.isEnabled(null))
    }

    @Test
    fun `audio only starts for the owner channel default unless video is forced`() {
        assertTrue(shouldStartAudioOnly(false, false, false, ownerAudioOnlyDefault = true))
        assertFalse(shouldStartAudioOnly(false, forceVideo = true, false, true))
        assertFalse(shouldStartAudioOnly(false, false, false, false))
    }

    @Test
    fun `global audio only mode and explicit requests behave as before`() {
        assertTrue(shouldStartAudioOnly(false, false, globalAudioOnly = true, false))
        assertFalse(shouldStartAudioOnly(false, forceVideo = true, globalAudioOnly = true, false))
        assertTrue(shouldStartAudioOnly(audioOnlyRequested = true, false, false, false))
    }
}
