package com.github.libretube.repo

import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.toStreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.enums.ContentFilter
import com.github.libretube.extensions.toChannelUrl
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.NewPipeExtractorInstance
import com.github.libretube.helpers.PreferenceHelper
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem

interface LocalFeedStore {
    suspend fun getSubscribedChannelIds(): List<String>

    suspend fun submitSubscriptionChannelInfosChanged(subscriptions: List<Subscription>)

    suspend fun getFeedItems(): List<SubscriptionsFeedItem>

    suspend fun insertFeedItems(feedItems: List<SubscriptionsFeedItem>)

    suspend fun updateFeedItem(feedItem: SubscriptionsFeedItem)

    suspend fun containsFeedItem(videoId: String): Boolean

    suspend fun cleanUpOlderThan(olderThanMillis: Long)

    suspend fun deleteChannel(channelId: String)

    suspend fun deleteAllExcept(channelIds: List<String>)
}

data class ChannelFeedEntry(
    val item: StreamItem,
    val isAvailable: Boolean = true,
)

data class ChannelFeedRss(
    val name: String? = null,
    val items: List<ChannelFeedEntry> = emptyList(),
)

data class ChannelFeedTab(
    val contentFilters: List<String>,
    val fetchItems: suspend () -> List<ChannelFeedEntry>,
)

data class ChannelFeedDetails(
    val name: String,
    val avatarUrl: String?,
    val isVerified: Boolean,
    val tabs: List<ChannelFeedTab>,
)

interface ChannelFeedSource {
    suspend fun fetchRssFeed(channelId: String): ChannelFeedRss?

    suspend fun fetchChannelDetails(channelId: String): ChannelFeedDetails
}

interface FeedPreferences {
    var lastLocalFeedRefreshTimestampMillis: Long

    fun isContentFilterEnabled(filter: ContentFilter): Boolean
}

internal object RoomLocalFeedStore : LocalFeedStore {
    private val feedDao
        get() = DatabaseHolder.Database.feedDao()

    override suspend fun getSubscribedChannelIds(): List<String> =
        SubscriptionHelper.getSubscriptionChannelIds()

    override suspend fun submitSubscriptionChannelInfosChanged(subscriptions: List<Subscription>) {
        SubscriptionHelper.submitSubscriptionChannelInfosChanged(subscriptions)
    }

    override suspend fun getFeedItems(): List<SubscriptionsFeedItem> = feedDao.getAll()

    override suspend fun insertFeedItems(feedItems: List<SubscriptionsFeedItem>) {
        feedDao.insertAll(feedItems)
    }

    override suspend fun updateFeedItem(feedItem: SubscriptionsFeedItem) {
        feedDao.update(feedItem)
    }

    override suspend fun containsFeedItem(videoId: String): Boolean = feedDao.contains(videoId)

    override suspend fun cleanUpOlderThan(olderThanMillis: Long) {
        feedDao.cleanUpOlderThan(olderThanMillis)
    }

    override suspend fun deleteChannel(channelId: String) {
        feedDao.delete(channelId)
    }

    override suspend fun deleteAllExcept(channelIds: List<String>) {
        feedDao.deleteAllExcept(channelIds)
    }
}

internal object NewPipeChannelFeedSource : ChannelFeedSource {
    private val allowedAvailability =
        setOf(
            ContentAvailability.AVAILABLE,
            ContentAvailability.UPCOMING,
            ContentAvailability.UNKNOWN,
        )

    override suspend fun fetchRssFeed(channelId: String): ChannelFeedRss? {
        val channelUrl = channelId.toID().toChannelUrl()
        val feedInfo = FeedInfo.getInfo(channelUrl) ?: return null
        return ChannelFeedRss(
            name = feedInfo.name,
            items = feedInfo.relatedItems.orEmpty().map { it.toChannelFeedEntry() },
        )
    }

    override suspend fun fetchChannelDetails(channelId: String): ChannelFeedDetails {
        val channelUrl = channelId.toID().toChannelUrl()
        val channelInfo = ChannelInfo.getInfo(channelUrl)
        val avatarUrl = channelInfo.avatars.maxByOrNull { it.height }?.url
        val tabs =
            channelInfo.tabs.map { tab ->
                ChannelFeedTab(
                    contentFilters = tab.contentFilters,
                    fetchItems = {
                        ChannelTabInfo.getInfo(NewPipeExtractorInstance.extractor, tab)
                            .relatedItems
                            .filterIsInstance<StreamInfoItem>()
                            .map { it.toChannelFeedEntry(avatarUrl) }
                    },
                )
            }
        return ChannelFeedDetails(
            name = channelInfo.name.orEmpty(),
            avatarUrl = avatarUrl,
            isVerified = channelInfo.isVerified,
            tabs = tabs,
        )
    }

    private fun StreamInfoItem.toChannelFeedEntry(channelAvatar: String? = null): ChannelFeedEntry =
        ChannelFeedEntry(
            item = toStreamItem(uploaderAvatarUrl = channelAvatar),
            isAvailable = contentAvailability in allowedAvailability,
        )
}

internal object PreferenceFeedPreferences : FeedPreferences {
    override var lastLocalFeedRefreshTimestampMillis: Long
        get() =
            PreferenceHelper.getLong(
                PreferenceKeys.LAST_LOCAL_FEED_REFRESH_TIMESTAMP_MILLIS,
                0,
            )
        set(value) {
            PreferenceHelper.putLong(
                PreferenceKeys.LAST_LOCAL_FEED_REFRESH_TIMESTAMP_MILLIS,
                value,
            )
        }

    override fun isContentFilterEnabled(filter: ContentFilter): Boolean = filter.isEnabled
}
