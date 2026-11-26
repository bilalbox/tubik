package com.github.libretube.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavBarIndexMigrationTest {

    @Test
    fun `start tab positions shift past the removed Home and Trends tabs`() {
        // Old menu: Search, Home, Trends, Subscriptions, Library, Downloads
        // New menu: Search, Subscriptions, Library, Downloads
        val remapped = (0..5).map { PreferenceHelper.remapNavBarIndexWithoutHomeAndTrends(it) }

        assertEquals(listOf(0, null, null, 1, 2, 3), remapped)
        assertNull(PreferenceHelper.remapNavBarIndexWithoutHomeAndTrends(-1))
    }

    @Test
    fun `stored bar order keeps order and hidden markers, drops Home, Trends, and garbage`() {
        // Subscriptions, Search, hidden Library, hidden Downloads after a reorder
        assertEquals(
            "1,0,-2,-3",
            PreferenceHelper.remapNavBarItemsWithoutHomeAndTrends("3,0,-4,-5"),
        )
        assertEquals(
            "0,1,2,-3",
            PreferenceHelper.remapNavBarItemsWithoutHomeAndTrends("0,-1,-2,3,4,-5"),
        )
        assertEquals(
            "-0,1",
            PreferenceHelper.remapNavBarItemsWithoutHomeAndTrends("-0,2,x,3"),
        )
    }
}
