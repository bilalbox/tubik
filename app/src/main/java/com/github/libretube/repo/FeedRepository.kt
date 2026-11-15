package com.github.libretube.repo

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.obj.SubscriptionsFeedItem

data class FeedProgress(
    val currentProgress: Int,
    val total: Int,
)

sealed class FeedRefresh {
    object Automatically : FeedRefresh()

    object All : FeedRefresh()

    class OnlySome(val channelIds: List<String>) : FeedRefresh()
}

/**
 * Whether opening the subscriptions screen should load the feed. An empty list is not a loaded
 * feed: the screen was opened before any channel had videos, and staying on that list hides later
 * subscriptions.
 */
fun shouldFetchFeedOnOpen(
    currentFeed: List<*>?,
    subscriptionsChanged: Boolean = false,
): Boolean = currentFeed.isNullOrEmpty() || subscriptionsChanged

interface FeedRepository {
    /**
     * Loads the user's video feed based on the subscribed channels. Depending on [refresh], the
     * feed is refetched from YouTube.
     */
    suspend fun getFeed(
        refresh: FeedRefresh,
        onProgressUpdate: (FeedProgress) -> Unit = {},
    ): List<StreamItem>

    suspend fun removeChannel(channelId: String) {}

    suspend fun submitFeedItemChange(feedItem: SubscriptionsFeedItem) {}
}
