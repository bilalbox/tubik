package com.github.libretube.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedRuleTest {
    private fun watched(positionSeconds: Long, durationSeconds: Long?) =
        DatabaseHelper.isVideoWatched(positionSeconds * 1000, durationSeconds)

    @Test
    fun `long talk counts as watched with at most 5 percent left`() {
        val twoHours = 2 * 60 * 60L
        assertTrue(watched(twoHours - 6 * 60, twoHours))
        assertFalse(watched(twoHours - 7 * 60, twoHours))
    }

    @Test
    fun `short video keeps the 60 second rule`() {
        val tenMinutes = 10 * 60L
        assertTrue(watched(tenMinutes - 45, tenMinutes))
        assertFalse(watched(tenMinutes - 2 * 60, tenMinutes))
    }

    @Test
    fun `60 second rule still needs 75 percent watched`() {
        assertFalse(watched(20, 70))
        assertTrue(watched(60, 70))
    }

    @Test
    fun `unknown or zero duration is never watched`() {
        assertFalse(watched(100, null))
        assertFalse(watched(100, 0))
        assertFalse(watched(0, 0))
    }
}
