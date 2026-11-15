package com.github.libretube.repo

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.enums.ContentFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

class LocalFeedRepositoryTest {

    private class FakeLocalFeedStore(
        var subscribedChannelIds: List<String> = emptyList(),
        initialFeedItems: List<SubscriptionsFeedItem> = emptyList(),
    ) : LocalFeedStore {
        private val itemsByVideoId = ConcurrentHashMap(initialFeedItems.associateBy { it.videoId })
        val updatedSubscriptions: MutableList<Subscription> =
            Collections.synchronizedList(mutableListOf())

        override suspend fun getSubscribedChannelIds(): List<String> = subscribedChannelIds

        override suspend fun submitSubscriptionChannelInfosChanged(
            subscriptions: List<Subscription>,
        ) {
            updatedSubscriptions.addAll(subscriptions)
        }

        override suspend fun getFeedItems(): List<SubscriptionsFeedItem> =
            itemsByVideoId.values.sortedByDescending { it.uploaded }

        override suspend fun insertFeedItems(feedItems: List<SubscriptionsFeedItem>) {
            for (item in feedItems) {
                itemsByVideoId[item.videoId] = item
            }
        }

        override suspend fun updateFeedItem(feedItem: SubscriptionsFeedItem) {
            if (itemsByVideoId.containsKey(feedItem.videoId)) {
                itemsByVideoId[feedItem.videoId] = feedItem
            }
        }

        override suspend fun containsFeedItem(videoId: String): Boolean =
            itemsByVideoId.containsKey(videoId)

        override suspend fun cleanUpOlderThan(olderThanMillis: Long) {
            itemsByVideoId.entries.removeIf { (_, item) ->
                item.uploaded < olderThanMillis && item.uploaded != -1L
            }
        }

        override suspend fun deleteChannel(channelId: String) {
            itemsByVideoId.entries.removeIf { (_, item) -> item.uploaderUrl == channelId }
        }

        override suspend fun deleteAllExcept(channelIds: List<String>) {
            val allowed = channelIds.toSet()
            itemsByVideoId.entries.removeIf { (_, item) -> item.uploaderUrl !in allowed }
        }
    }

    private class FakeChannelFeedSource : ChannelFeedSource {
        val rssByChannel = ConcurrentHashMap<String, () -> ChannelFeedRss?>()
        val detailsByChannel = ConcurrentHashMap<String, () -> ChannelFeedDetails>()
        val fetchedDetailsChannels: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        override suspend fun fetchRssFeed(channelId: String): ChannelFeedRss? =
            rssByChannel[channelId]?.invoke()

        override suspend fun fetchChannelDetails(channelId: String): ChannelFeedDetails {
            fetchedDetailsChannels.add(channelId)
            return detailsByChannel.getValue(channelId).invoke()
        }
    }

    private class FakeFeedPreferences(
        override var lastLocalFeedRefreshTimestampMillis: Long = 0L,
        var enabledFilters: Set<ContentFilter> = ContentFilter.entries.toSet(),
    ) : FeedPreferences {
        override fun isContentFilterEnabled(filter: ContentFilter): Boolean =
            filter in enabledFilters
    }

    private class MutableClock(var nowMillis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
    }

    private val nowMillis = 1_700_000_000_000L
    private val oneDayMillis = Duration.ofDays(1).toMillis()
    private val minimumDateMillis = nowMillis - Duration.ofDays(365).toMillis()

    private fun createRepository(
        store: FakeLocalFeedStore = FakeLocalFeedStore(),
        source: FakeChannelFeedSource = FakeChannelFeedSource(),
        preferences: FakeFeedPreferences = FakeFeedPreferences(),
        clock: MutableClock = MutableClock(nowMillis),
    ): FeedRepository =
        LocalFeedRepository(
            feedStore = store,
            channelFeedSource = source,
            preferences = preferences,
            clock = clock,
            progressDispatcher = Dispatchers.Unconfined,
        )

    private fun streamItem(
        videoId: String,
        channelId: String = "channel-a",
        title: String? = "Title $videoId",
        uploaded: Long = nowMillis - 1_000L,
        uploadedDate: String? = "2023-11-14",
        duration: Long? = 120L,
        isShort: Boolean = false,
        uploaderName: String? = "Name $channelId",
    ): StreamItem =
        StreamItem(
            url = videoId,
            type = StreamItem.TYPE_STREAM,
            title = title,
            uploaderName = uploaderName,
            uploaderUrl = channelId,
            duration = duration,
            uploaded = uploaded,
            uploadedDate = uploadedDate,
            isShort = isShort,
        )

    private fun feedDbItem(
        videoId: String,
        uploaderUrl: String? = "channel-a",
        uploaded: Long = nowMillis - 1_000L,
        title: String = "Cached $videoId",
    ): SubscriptionsFeedItem =
        SubscriptionsFeedItem(
            videoId = videoId,
            title = title,
            uploaderName = "Name $uploaderUrl",
            uploaderUrl = uploaderUrl,
            uploaderVerified = false,
            uploaded = uploaded,
        )

    private fun singleVideosTabDetails(
        channelName: String,
        vararg entries: ChannelFeedEntry,
        avatarUrl: String? = "https://img/avatar.png",
        isVerified: Boolean = true,
    ): ChannelFeedDetails =
        ChannelFeedDetails(
            name = channelName,
            avatarUrl = avatarUrl,
            isVerified = isVerified,
            tabs =
                listOf(
                    ChannelFeedTab(listOf(ChannelTabs.VIDEOS)) { entries.toList() },
                ),
        )

    @Test
    fun `automatic open with subscriptions and an empty cache refreshes those channels`() =
        runBlocking {
            val store = FakeLocalFeedStore(subscribedChannelIds = listOf("channel-a", "channel-b"))
            val source = FakeChannelFeedSource()
            source.detailsByChannel["channel-a"] = {
                singleVideosTabDetails(
                    "Channel A",
                    ChannelFeedEntry(streamItem("vid-a", "channel-a")),
                )
            }
            source.detailsByChannel["channel-b"] = {
                singleVideosTabDetails(
                    "Channel B",
                    ChannelFeedEntry(
                        streamItem("vid-b", "channel-b", uploaded = nowMillis - 2_000L),
                    ),
                )
            }
            val preferences =
                FakeFeedPreferences(lastLocalFeedRefreshTimestampMillis = nowMillis - 100L)
            val repository =
                createRepository(store = store, source = source, preferences = preferences)

            val feed = repository.getFeed(FeedRefresh.Automatically)

            assertEquals(listOf("vid-a", "vid-b"), feed.map { it.url })
            assertEquals(listOf("channel-a", "channel-b"), source.fetchedDetailsChannels.sorted())
            assertEquals(nowMillis, preferences.lastLocalFeedRefreshTimestampMillis)
        }

    @Test
    fun `automatic open keeps a fresh feed that already covers every subscription`() = runBlocking {
        val store =
            FakeLocalFeedStore(
                subscribedChannelIds = listOf("channel-a", "channel-b"),
                initialFeedItems =
                    listOf(
                        feedDbItem(
                            "cached-a",
                            uploaderUrl = "channel-a",
                            uploaded = nowMillis - 1_000L,
                        ),
                        feedDbItem(
                            "cached-b",
                            uploaderUrl = "channel-b",
                            uploaded = nowMillis - 2_000L,
                        ),
                    ),
            )
        val source = FakeChannelFeedSource()
        val preferences =
            FakeFeedPreferences(
                lastLocalFeedRefreshTimestampMillis = nowMillis - (oneDayMillis - 1),
            )
        val repository = createRepository(store = store, source = source, preferences = preferences)

        val feed = repository.getFeed(FeedRefresh.Automatically)

        assertEquals(listOf("cached-a", "cached-b"), feed.map { it.url })
        assertTrue(source.fetchedDetailsChannels.isEmpty())
    }

    @Test
    fun `automatic open refetches every subscription once cache is stale and cleans up expired items`() =
        runBlocking {
            val store =
                FakeLocalFeedStore(
                    subscribedChannelIds = listOf("channel-a"),
                    initialFeedItems =
                        listOf(
                            feedDbItem(
                                "expired-vid",
                                uploaderUrl = "channel-a",
                                uploaded = minimumDateMillis - 5_000L,
                            ),
                            feedDbItem(
                                "unknown-date-vid",
                                uploaderUrl = "channel-a",
                                uploaded = -1L,
                            ),
                        ),
                )
            val source = FakeChannelFeedSource()
            source.detailsByChannel["channel-a"] = {
                singleVideosTabDetails(
                    "Channel A",
                    ChannelFeedEntry(streamItem("fresh-vid", "channel-a")),
                )
            }
            val preferences =
                FakeFeedPreferences(lastLocalFeedRefreshTimestampMillis = nowMillis - oneDayMillis)
            val repository =
                createRepository(store = store, source = source, preferences = preferences)

            val feed = repository.getFeed(FeedRefresh.Automatically)

            assertEquals(listOf("fresh-vid", "unknown-date-vid"), feed.map { it.url })
            assertEquals(listOf("channel-a"), source.fetchedDetailsChannels)
            assertEquals(nowMillis, preferences.lastLocalFeedRefreshTimestampMillis)
        }

    @Test
    fun `automatic open fetches a newly subscribed channel even when the rest of the feed is fresh`() =
        runBlocking {
            val store =
                FakeLocalFeedStore(
                    subscribedChannelIds = listOf("channel-a", "channel-b"),
                    initialFeedItems =
                        listOf(
                            feedDbItem(
                                "cached-a",
                                uploaderUrl = "channel-a",
                                uploaded = nowMillis - 1_000L,
                            ),
                        ),
                )
            val source = FakeChannelFeedSource()
            source.detailsByChannel["channel-b"] = {
                singleVideosTabDetails(
                    "Channel B",
                    ChannelFeedEntry(
                        streamItem("new-b", "channel-b", uploaded = nowMillis - 2_000L),
                    ),
                )
            }
            val preferences = FakeFeedPreferences(lastLocalFeedRefreshTimestampMillis = nowMillis)
            val repository =
                createRepository(store = store, source = source, preferences = preferences)

            val feed = repository.getFeed(FeedRefresh.Automatically)

            assertEquals(listOf("cached-a", "new-b"), feed.map { it.url })
            assertEquals(listOf("channel-b"), source.fetchedDetailsChannels)
        }

    @Test
    fun `full refresh reloads every subscription and prunes unsubscribed channels`() = runBlocking {
        val store =
            FakeLocalFeedStore(
                subscribedChannelIds = listOf("channel-a"),
                initialFeedItems =
                    listOf(
                        feedDbItem(
                            "cached-a",
                            uploaderUrl = "channel-a",
                            uploaded = nowMillis - 5_000L,
                        ),
                        feedDbItem(
                            "unsub-z",
                            uploaderUrl = "channel-z",
                            uploaded = nowMillis - 1_000L,
                        ),
                    ),
            )
        val source = FakeChannelFeedSource()
        source.detailsByChannel["channel-a"] = {
            singleVideosTabDetails(
                "Channel A",
                ChannelFeedEntry(streamItem("reloaded-a", "channel-a")),
            )
        }
        val preferences = FakeFeedPreferences(lastLocalFeedRefreshTimestampMillis = nowMillis)
        val repository = createRepository(store = store, source = source, preferences = preferences)

        val feed = repository.getFeed(FeedRefresh.All)

        assertEquals(listOf("reloaded-a", "cached-a"), feed.map { it.url })
        assertEquals(listOf("channel-a"), source.fetchedDetailsChannels)
    }

    @Test
    fun `only-some refresh fetches requested channels without pruning other cached channels`() =
        runBlocking {
            val store =
                FakeLocalFeedStore(
                    subscribedChannelIds = listOf("channel-a", "channel-b"),
                    initialFeedItems =
                        listOf(
                            feedDbItem(
                                "cached-a",
                                uploaderUrl = "channel-a",
                                uploaded = nowMillis - 5_000L,
                            ),
                        ),
                )
            val source = FakeChannelFeedSource()
            source.detailsByChannel["channel-b"] = {
                singleVideosTabDetails(
                    "Channel B",
                    ChannelFeedEntry(
                        streamItem("vid-b", "channel-b", uploaded = nowMillis - 2_000L),
                    ),
                )
            }
            val repository = createRepository(store = store, source = source)

            val feed = repository.getFeed(FeedRefresh.OnlySome(listOf("channel-b")))

            assertEquals(listOf("vid-b", "cached-a"), feed.map { it.url })
            assertEquals(listOf("channel-b"), source.fetchedDetailsChannels)
        }

    @Test
    fun `rss short-circuit skips channel details when newest rss video is already stored and recent`() =
        runBlocking {
            val store =
                FakeLocalFeedStore(
                    subscribedChannelIds = listOf("channel-a"),
                    initialFeedItems =
                        listOf(
                            feedDbItem(
                                "newest-a",
                                uploaderUrl = "channel-a",
                                uploaded = nowMillis - 1_000L,
                            ),
                        ),
                )
            val source = FakeChannelFeedSource()
            source.rssByChannel["channel-a"] = {
                ChannelFeedRss(
                    name = "Channel A",
                    items =
                        listOf(
                            ChannelFeedEntry(
                                streamItem("newest-a", "channel-a", uploaded = nowMillis - 1_000L),
                            ),
                        ),
                )
            }
            val repository = createRepository(store = store, source = source)

            val feed = repository.getFeed(FeedRefresh.All)

            assertEquals(listOf("newest-a"), feed.map { it.url })
            assertTrue(source.fetchedDetailsChannels.isEmpty())
        }

    @Test
    fun `channel with no rss dates or uncached older rss uploads still fetches channel tabs`() =
        runBlocking {
            val store =
                FakeLocalFeedStore(
                    subscribedChannelIds = listOf("channel-no-rss-dates", "channel-uncached"),
                    initialFeedItems =
                        listOf(
                            feedDbItem(
                                "existing-a",
                                uploaderUrl = "channel-no-rss-dates",
                                uploaded = nowMillis - 5_000L,
                            ),
                        ),
                )
            val source = FakeChannelFeedSource()
            source.rssByChannel["channel-no-rss-dates"] = {
                ChannelFeedRss(
                    items =
                        listOf(
                            ChannelFeedEntry(
                                streamItem("undated-rss", uploaded = -1L, uploadedDate = null),
                            ),
                        ),
                )
            }
            source.rssByChannel["channel-uncached"] = {
                ChannelFeedRss(
                    items =
                        listOf(
                            ChannelFeedEntry(
                                streamItem("old-rss", uploaded = minimumDateMillis - 10_000L),
                            ),
                        ),
                )
            }
            source.detailsByChannel["channel-no-rss-dates"] = {
                singleVideosTabDetails(
                    "Channel No Dates",
                    ChannelFeedEntry(
                        streamItem("tab-1", "channel-no-rss-dates", uploaded = nowMillis - 1_000L),
                    ),
                )
            }
            source.detailsByChannel["channel-uncached"] = {
                singleVideosTabDetails(
                    "Channel Uncached",
                    ChannelFeedEntry(
                        streamItem("tab-2", "channel-uncached", uploaded = nowMillis - 2_000L),
                    ),
                )
            }
            val repository = createRepository(store = store, source = source)

            val feed = repository.getFeed(FeedRefresh.Automatically)

            assertEquals(listOf("tab-1", "tab-2", "existing-a"), feed.map { it.url })
            assertEquals(
                listOf("channel-no-rss-dates", "channel-uncached"),
                source.fetchedDetailsChannels.sorted(),
            )
        }

    @Test
    fun `channel-tab-to-rss fallback uses rss items and applies content filters when tabs are empty`() =
        runBlocking {
            val store = FakeLocalFeedStore(subscribedChannelIds = listOf("channel-empty-tabs"))
            val source = FakeChannelFeedSource()
            source.rssByChannel["channel-empty-tabs"] = {
                ChannelFeedRss(
                    name = "Empty Tabs RSS",
                    items =
                        listOf(
                            ChannelFeedEntry(
                                streamItem(
                                    "rss-video",
                                    "channel-empty-tabs",
                                    uploaded = nowMillis - 1_000L,
                                    isShort = false,
                                ),
                            ),
                            ChannelFeedEntry(
                                streamItem(
                                    "rss-short",
                                    "channel-empty-tabs",
                                    uploaded = nowMillis - 1_500L,
                                    isShort = true,
                                ),
                            ),
                        ),
                )
            }
            source.detailsByChannel["channel-empty-tabs"] = {
                singleVideosTabDetails("Empty Tabs Channel")
            }
            val preferences =
                FakeFeedPreferences(
                    enabledFilters = setOf(ContentFilter.VIDEOS, ContentFilter.LIVESTREAMS),
                )
            val repository =
                createRepository(store = store, source = source, preferences = preferences)

            val feed = repository.getFeed(FeedRefresh.Automatically)

            assertEquals(listOf("rss-video"), feed.map { it.url })
        }

    @Test
    fun `channel-tab-to-rss fallback uses rss items when channel details fail`() = runBlocking {
        val store = FakeLocalFeedStore(subscribedChannelIds = listOf("channel-failed-details"))
        val source = FakeChannelFeedSource()
        source.rssByChannel["channel-failed-details"] = {
            ChannelFeedRss(
                name = "Failed Details RSS",
                items =
                    listOf(
                        ChannelFeedEntry(
                            streamItem(
                                "rss-fallback",
                                "channel-failed-details",
                                uploaded = nowMillis - 2_000L,
                                uploaderName = null,
                            ),
                        ),
                    ),
            )
        }
        source.detailsByChannel["channel-failed-details"] = {
            error("channel details unavailable")
        }
        val repository = createRepository(store = store, source = source)

        val feed = repository.getFeed(FeedRefresh.Automatically)

        assertEquals(listOf("rss-fallback"), feed.map { it.url })
        assertEquals("Failed Details RSS", feed.single().uploaderName)
    }

    @Test
    fun `tab items prefer untranslated rss title, inherit missing rss upload date, and drop unavailable items`() =
        runBlocking {
            val store = FakeLocalFeedStore(subscribedChannelIds = listOf("channel-a"))
            val source = FakeChannelFeedSource()
            source.rssByChannel["channel-a"] = {
                ChannelFeedRss(
                    name = "Channel A",
                    items =
                        listOf(
                            ChannelFeedEntry(
                                streamItem(
                                    videoId = "short-1",
                                    title = "Original Untranslated Title",
                                    uploaded = nowMillis - 3_000L,
                                    uploadedDate = "2023-11-14",
                                ),
                            ),
                        ),
                )
            }
            source.detailsByChannel["channel-a"] = {
                ChannelFeedDetails(
                    name = "Channel A",
                    avatarUrl = "https://img/channel-a.png",
                    isVerified = true,
                    tabs =
                        listOf(
                            ChannelFeedTab(listOf(ChannelTabs.SHORTS)) {
                                listOf(
                                    ChannelFeedEntry(
                                        streamItem(
                                            videoId = "short-1",
                                            title = "Translated Title",
                                            uploaded = -1L,
                                            uploadedDate = null,
                                            isShort = true,
                                        ),
                                        isAvailable = true,
                                    ),
                                    ChannelFeedEntry(
                                        streamItem(
                                            videoId = "members-only",
                                            uploaded = nowMillis - 1_000L,
                                        ),
                                        isAvailable = false,
                                    ),
                                )
                            },
                        ),
                )
            }
            val repository = createRepository(store = store, source = source)

            val feed = repository.getFeed(FeedRefresh.Automatically)

            assertEquals(1, feed.size)
            val item = feed.single()
            assertEquals("short-1", item.url)
            assertEquals("Original Untranslated Title", item.title)
            assertEquals(nowMillis - 3_000L, item.uploaded)
            assertEquals("https://img/channel-a.png", item.uploaderAvatar)
        }

    @Test
    fun `failing channel tabs with empty rss rethrows failure when feed remains empty`() =
        runBlocking {
            val store = FakeLocalFeedStore(subscribedChannelIds = listOf("channel-a"))
            val source = FakeChannelFeedSource()
            source.detailsByChannel["channel-a"] = {
                ChannelFeedDetails(
                    name = "Channel A",
                    avatarUrl = null,
                    isVerified = false,
                    tabs =
                        listOf(
                            ChannelFeedTab(listOf(ChannelTabs.VIDEOS)) {
                                throw IllegalStateException("tab boom")
                            },
                        ),
                )
            }
            val preferences = FakeFeedPreferences(lastLocalFeedRefreshTimestampMillis = 123L)
            val repository =
                createRepository(store = store, source = source, preferences = preferences)

            val failure = runCatching {
                repository.getFeed(FeedRefresh.Automatically)
            }
                .exceptionOrNull()

            assertEquals("tab boom", failure?.message)
            assertEquals(123L, preferences.lastLocalFeedRefreshTimestampMillis)
        }

    @Test
    fun `time-window filtering drops stale videos when recent exist and falls back for low-cadence`() =
        runBlocking {
            val store =
                FakeLocalFeedStore(
                    subscribedChannelIds = listOf("active-channel", "low-cadence-channel"),
                )
            val source = FakeChannelFeedSource()
            source.detailsByChannel["active-channel"] = {
                singleVideosTabDetails(
                    "Active Channel",
                    ChannelFeedEntry(
                        streamItem(
                            "active-recent",
                            "active-channel",
                            uploaded = minimumDateMillis + 1_000L,
                        ),
                    ),
                    ChannelFeedEntry(
                        streamItem(
                            "active-unknown",
                            "active-channel",
                            uploaded = -1L,
                            uploadedDate = null,
                        ),
                    ),
                    ChannelFeedEntry(
                        streamItem(
                            "active-old",
                            "active-channel",
                            uploaded = minimumDateMillis - 1_000L,
                        ),
                    ),
                )
            }
            val lowCadenceEntries =
                (1..17).map { index ->
                    ChannelFeedEntry(
                        streamItem(
                            videoId = "low-$index",
                            channelId = "low-cadence-channel",
                            uploaded = minimumDateMillis - index * 1_000L,
                        ),
                    )
                }
            source.detailsByChannel["low-cadence-channel"] = {
                singleVideosTabDetails("Low Cadence", *lowCadenceEntries.toTypedArray())
            }
            val repository = createRepository(store = store, source = source)

            val feed = repository.getFeed(FeedRefresh.Automatically)
            val byChannel = feed.groupBy { it.uploaderUrl }

            assertEquals(
                listOf("active-recent", "active-unknown"),
                byChannel.getValue("active-channel").map { it.url },
            )
            assertEquals(
                (1..15).map { "low-$it" },
                byChannel.getValue("low-cadence-channel").map { it.url },
            )
        }

    @Test
    fun `progress, subscription metadata, item updates, and channel removal work through FeedRepository`() =
        runBlocking {
            val store = FakeLocalFeedStore(subscribedChannelIds = listOf("channel-a"))
            val source = FakeChannelFeedSource()
            source.detailsByChannel["channel-a"] = {
                singleVideosTabDetails(
                    "Updated Name",
                    ChannelFeedEntry(streamItem("vid-a", "channel-a")),
                    avatarUrl = "https://img/updated.png",
                    isVerified = true,
                )
            }
            val repository = createRepository(store = store, source = source)
            val progressEvents = mutableListOf<FeedProgress>()

            repository.getFeed(FeedRefresh.All) { progressEvents.add(it) }

            assertEquals(listOf(FeedProgress(0, 1), FeedProgress(1, 1)), progressEvents)
            assertEquals(
                listOf(Subscription("channel-a", "Updated Name", "https://img/updated.png", true)),
                store.updatedSubscriptions,
            )

            repository.submitFeedItemChange(
                feedDbItem("vid-a", uploaderUrl = "channel-a", title = "Edited Title"),
            )
            assertEquals(
                "Edited Title",
                repository.getFeed(FeedRefresh.Automatically).single().title,
            )

            repository.removeChannel("channel-a")
            assertNull(store.getFeedItems().firstOrNull())
        }
}
