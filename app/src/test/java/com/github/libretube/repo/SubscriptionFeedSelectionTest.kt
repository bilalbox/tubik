package com.github.libretube.repo

import android.view.View
import com.github.libretube.ui.fragments.SubscriptionsFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFeedSelectionTest {
    @Test
    fun `an empty subscriptions list still needs a fetch after the user subscribes`() {
        assertTrue(shouldFetchFeedOnOpen(emptyList<Any>()))
    }

    @Test
    fun `a feed that already has videos is not fetched again just because the screen opened`() {
        assertFalse(shouldFetchFeedOnOpen(listOf("video")))
    }

    @Test
    fun `a non-empty feed refreshes when subscriptions changed since last load`() {
        assertTrue(shouldFetchFeedOnOpen(listOf("video"), subscriptionsChanged = true))
    }

    @Test
    fun `chip selection maps NO_ID to ALL, ungrouped chip to UNGROUPED, and group chips by index`() {
        val allId = 100
        val ungroupedId = 101
        val groupIds = listOf(201, 202)
        assertEquals(
            SubscriptionsFragment.FILTER_GROUP_ALL,
            SubscriptionsFragment.resolveSelectedFilterGroup(
                View.NO_ID,
                allId,
                ungroupedId,
                groupIds,
            ),
        )
        assertEquals(
            SubscriptionsFragment.FILTER_GROUP_ALL,
            SubscriptionsFragment.resolveSelectedFilterGroup(allId, allId, ungroupedId, groupIds),
        )
        assertEquals(
            SubscriptionsFragment.FILTER_GROUP_UNGROUPED,
            SubscriptionsFragment.resolveSelectedFilterGroup(
                ungroupedId,
                allId,
                ungroupedId,
                groupIds,
            ),
        )
        assertEquals(
            1,
            SubscriptionsFragment.resolveSelectedFilterGroup(202, allId, ungroupedId, groupIds),
        )
    }
}
