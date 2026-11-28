package com.github.libretube.repo

import android.util.Log
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.enums.ContentFilter
import com.github.libretube.extensions.parallelMap
import com.github.libretube.extensions.toID
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

class LocalFeedRepository(
    private val feedStore: LocalFeedStore = RoomLocalFeedStore,
    private val channelFeedSource: ChannelFeedSource = NewPipeChannelFeedSource,
    private val preferences: FeedPreferences = PreferenceFeedPreferences,
    private val clock: Clock = Clock.systemUTC(),
    private val progressDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : FeedRepository {
    private val relevantTabs: Array<String>
        get() =
            listOf(
                    ContentFilter.LIVESTREAMS to ChannelTabs.LIVESTREAMS,
                    ContentFilter.VIDEOS to ChannelTabs.VIDEOS,
                    ContentFilter.SHORTS to ChannelTabs.SHORTS,
                )
                .mapNotNull { (filter, tab) ->
                    if (preferences.isContentFilterEnabled(filter)) tab else null
                }
                .toTypedArray()

    private val durationBetweenAutomaticRefreshes = Duration.ofDays(1)

    override suspend fun submitFeedItemChange(feedItem: SubscriptionsFeedItem) {
        feedStore.updateFeedItem(feedItem)
    }

    override suspend fun removeChannel(channelId: String) {
        feedStore.deleteChannel(channelId)
    }

    override suspend fun getFeed(
        refresh: FeedRefresh,
        onProgressUpdate: (FeedProgress) -> Unit,
    ): List<StreamItem> {
        val nowMillis = clock.instant().toEpochMilli()
        val minimumDateMillis = nowMillis - Duration.ofDays(MAX_FEED_AGE_DAYS).toMillis()
        val subscribedChannelIds =
            if (refresh is FeedRefresh.OnlySome) {
                emptyList()
            } else {
                feedStore.getSubscribedChannelIds().map { it.toID() }
            }
        if (refresh !is FeedRefresh.OnlySome) {
            // remove channels that are no longer subscribed to, e.g. after switching accounts or
            // unsubscribing
            feedStore.deleteAllExcept(subscribedChannelIds)
        }

        val cachedFeed =
            if (refresh is FeedRefresh.Automatically) feedStore.getFeedItems() else emptyList()
        val millisSinceLastRefresh =
            if (refresh is FeedRefresh.Automatically) {
                nowMillis - preferences.lastLocalFeedRefreshTimestampMillis
            } else {
                Long.MAX_VALUE
            }

        val plan =
            planFeedRefresh(
                refresh = refresh,
                subscribedChannelIds = subscribedChannelIds,
                cachedUploaderIds = cachedFeed.mapNotNull { it.uploaderUrl }.toSet(),
                millisSinceLastRefresh = millisSinceLastRefresh,
                refreshIntervalMillis = durationBetweenAutomaticRefreshes.toMillis(),
            )
        runCatching {
            Log.d(
                TAG_FEED,
                "getFeed(${refresh::class.simpleName}): subscribed=${subscribedChannelIds.size}, " +
                    "cached=${cachedFeed.size}, plan=${plan::class.simpleName}",
            )
        }
        return executeRefreshPlan(
            plan = plan,
            refresh = refresh,
            subscribedChannelIds = subscribedChannelIds,
            cachedFeed = cachedFeed,
            nowMillis = nowMillis,
            minimumDateMillis = minimumDateMillis,
            onProgressUpdate = onProgressUpdate,
        )
    }

    private suspend fun executeRefreshPlan(
        plan: FeedRefreshPlan,
        refresh: FeedRefresh,
        subscribedChannelIds: List<String>,
        cachedFeed: List<SubscriptionsFeedItem>,
        nowMillis: Long,
        minimumDateMillis: Long,
        onProgressUpdate: (FeedProgress) -> Unit,
    ): List<StreamItem> {
        return when (plan) {
            FeedRefreshPlan.UseCachedFeed -> cachedFeed.map(SubscriptionsFeedItem::toStreamItem)
            is FeedRefreshPlan.Refresh -> {
                val isFullRefresh =
                    refresh !is FeedRefresh.OnlySome &&
                        plan.channelIds.size == subscribedChannelIds.size
                if (isFullRefresh) {
                    feedStore.cleanUpOlderThan(minimumDateMillis)
                }
                val cachedUploaderIds =
                    resolveCachedUploaderIds(
                        plan = plan,
                        refresh = refresh,
                        cachedFeed = cachedFeed,
                        isFullRefresh = isFullRefresh,
                        minimumDateMillis = minimumDateMillis,
                    )
                val refreshFailure =
                    refreshFeed(
                        plan.channelIds,
                        minimumDateMillis,
                        cachedUploaderIds,
                        onProgressUpdate,
                    )
                if (isFullRefresh && refreshFailure == null) {
                    preferences.lastLocalFeedRefreshTimestampMillis = nowMillis
                }
                val updatedFeed = feedStore.getFeedItems()
                runCatching {
                    Log.d(
                        TAG_FEED,
                        "getFeed refreshed ${plan.channelIds.size} channels -> " +
                            "${updatedFeed.size} cached items " +
                            "(failure=${refreshFailure?.javaClass?.simpleName})",
                    )
                }
                if (
                    updatedFeed.isEmpty() && refreshFailure != null && plan.channelIds.isNotEmpty()
                ) {
                    throw refreshFailure
                }
                updatedFeed.map(SubscriptionsFeedItem::toStreamItem)
            }
        }
    }

    private suspend fun resolveCachedUploaderIds(
        plan: FeedRefreshPlan.Refresh,
        refresh: FeedRefresh,
        cachedFeed: List<SubscriptionsFeedItem>,
        isFullRefresh: Boolean,
        minimumDateMillis: Long,
    ): Set<String> {
        if (plan.channelIds.isEmpty()) return emptySet()
        val preRefreshItems =
            when {
                refresh !is FeedRefresh.Automatically -> feedStore.getFeedItems()
                isFullRefresh ->
                    cachedFeed.filter { it.uploaded >= minimumDateMillis || it.uploaded == -1L }
                else -> cachedFeed
            }
        return preRefreshItems.mapNotNullTo(HashSet()) {
            it.uploaderUrl?.toID()?.takeIf(String::isNotEmpty)
        }
    }

    private suspend fun refreshFeed(
        channelIds: List<String>,
        minimumDateMillis: Long,
        cachedUploaderIds: Set<String>,
        onProgressUpdate: (FeedProgress) -> Unit,
    ): Throwable? {
        if (channelIds.isEmpty()) return null

        val totalExtractionCount = AtomicInteger()
        val channelExtractionCount = AtomicInteger()
        val lastFailure = AtomicReference<Throwable?>(null)
        withContext(progressDispatcher) {
            onProgressUpdate(FeedProgress(0, channelIds.size))
        }

        for (channelIdChunk in channelIds.chunked(CHANNEL_CHUNK_SIZE)) {
            val count = channelExtractionCount.get()
            if (count >= CHANNEL_BATCH_SIZE) {
                // add a delay after each BATCH_SIZE amount of fully-fetched channels
                delay(CHANNEL_BATCH_DELAY.random().milliseconds)
                channelExtractionCount.set(0)
            }

            val (channels, collectedFeedItems) =
                channelIdChunk
                    .parallelMap { channelId ->
                        fetchChannelInBatch(
                            channelId = channelId,
                            minimumDateMillis = minimumDateMillis,
                            cachedUploaderIds = cachedUploaderIds,
                            channelExtractionCount = channelExtractionCount,
                            lastFailure = lastFailure,
                            totalExtractionCount = totalExtractionCount,
                            totalChannels = channelIds.size,
                            onProgressUpdate = onProgressUpdate,
                        )
                    }
                    .filterNotNull()
                    .unzip()

            // update subscriptions channels in case they've changed (e.g. different avatar or name)
            feedStore.submitSubscriptionChannelInfosChanged(channels.filterNotNull())
            feedStore.insertFeedItems(collectedFeedItems.flatten().map(StreamItem::toFeedItem))
        }
        return lastFailure.get()
    }

    private suspend fun fetchChannelInBatch(
        channelId: String,
        minimumDateMillis: Long,
        cachedUploaderIds: Set<String>,
        channelExtractionCount: AtomicInteger,
        lastFailure: AtomicReference<Throwable?>,
        totalExtractionCount: AtomicInteger,
        totalChannels: Int,
        onProgressUpdate: (FeedProgress) -> Unit,
    ): Pair<Subscription?, List<StreamItem>>? {
        val result = runCatching {
            getRelatedStreams(channelId, minimumDateMillis, cachedUploaderIds)
        }
        result.onFailure { failure ->
            if (failure is CancellationException) throw failure
            lastFailure.compareAndSet(null, failure)
            runCatching { Log.e(channelId, failure.stackTraceToString()) }
        }
        val fetched = result.getOrNull()
        if (fetched != null && fetched.second.isNotEmpty()) {
            // increase counter if we had to fully fetch the channel
            channelExtractionCount.incrementAndGet()
        }
        withContext(progressDispatcher) {
            onProgressUpdate(FeedProgress(totalExtractionCount.incrementAndGet(), totalChannels))
        }
        return fetched
    }

    private suspend fun getRelatedStreams(
        channelId: String,
        minimumDateMillis: Long,
        cachedUploaderIds: Set<String>,
    ): Pair<Subscription?, List<StreamItem>> {
        val canonicalChannelId = channelId.toID()
        // RSS can be empty or fail while the channel tab still has videos. A failed feed
        // must not drop the channel, and conversely a failing channel/tab must still fall
        // back to RSS items when available.
        val rssFeed = runCatching { channelFeedSource.fetchRssFeed(canonicalChannelId) }.getOrNull()
        val rssEntries = rssFeed?.items.orEmpty()
        val rssByVideoId = rssEntries.map { it.item }.associateBy { it.url.orEmpty().toID() }

        if (
            !shouldFetchChannel(
                canonicalChannelId,
                rssEntries,
                minimumDateMillis,
                cachedUploaderIds,
            )
        ) {
            return Pair(null, emptyList())
        }

        val channelDetailsResult = runCatching {
            channelFeedSource.fetchChannelDetails(canonicalChannelId)
        }
        val channelDetails = channelDetailsResult.getOrNull()
        return if (channelDetails == null) {
            val err = channelDetailsResult.exceptionOrNull()
            if (err is CancellationException) throw err
            if (rssEntries.isNotEmpty()) {
                Pair(
                    null,
                    mapChannelFeedItems(
                        entries = rssEntries,
                        canonicalChannelId = canonicalChannelId,
                        fallbackChannelName = rssFeed?.name,
                        channelAvatar = null,
                        rssByVideoId = rssByVideoId,
                        minimumDateMillis = minimumDateMillis,
                        filterByContentType = true,
                    ),
                )
            } else {
                throw err ?: IllegalStateException("Channel info failed for $canonicalChannelId")
            }
        } else {
            fetchChannelTabsOrFallback(
                channelId = channelId,
                canonicalChannelId = canonicalChannelId,
                channelDetails = channelDetails,
                rssEntries = rssEntries,
                rssByVideoId = rssByVideoId,
                minimumDateMillis = minimumDateMillis,
            )
        }
    }

    private suspend fun shouldFetchChannel(
        canonicalChannelId: String,
        rssEntries: List<ChannelFeedEntry>,
        minimumDateMillis: Long,
        cachedUploaderIds: Set<String>,
    ): Boolean {
        val newestVideoId =
            rssEntries
                .maxByOrNull { it.item.uploaded.takeIf { ts -> ts > 0L } ?: Long.MIN_VALUE }
                ?.item
                ?.url
                ?.toID()
        val newestStored =
            newestVideoId?.takeIf { it.isNotEmpty() }?.let { feedStore.containsFeedItem(it) } ==
                true
        val hasCachedVideos = canonicalChannelId in cachedUploaderIds
        val uploadTimes = rssEntries.map { it.item.uploaded.takeIf { ts -> ts > 0L } }
        return shouldFetchChannelVideos(
            uploadTimes,
            newestStored,
            minimumDateMillis,
            hasCachedVideos,
        )
    }

    private suspend fun fetchChannelTabsOrFallback(
        channelId: String,
        canonicalChannelId: String,
        channelDetails: ChannelFeedDetails,
        rssEntries: List<ChannelFeedEntry>,
        rssByVideoId: Map<String, StreamItem>,
        minimumDateMillis: Long,
    ): Pair<Subscription?, List<StreamItem>> {
        val subscription =
            Subscription(
                channelId,
                channelDetails.name,
                channelDetails.avatarUrl,
                channelDetails.isVerified,
            )
        val relevantInfoTabs =
            channelDetails.tabs.filter { tab ->
                relevantTabs.any { tab.contentFilters.contains(it) }
            }
        val tabResults = relevantInfoTabs.parallelMap { tab ->
            runCatching { tab.fetchItems() }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                    runCatching {
                        Log.w(
                            TAG_FEED,
                            "ChannelTabInfo failed for $canonicalChannelId (${tab.contentFilters})",
                            err,
                        )
                    }
                }
        }
        val tabEntries = tabResults.mapNotNull { it.getOrNull() }.flatten()
        val allTabsFailed = relevantInfoTabs.isNotEmpty() && tabResults.all { it.isFailure }
        if (tabEntries.isEmpty() && rssEntries.isEmpty() && allTabsFailed) {
            throw tabResults.firstNotNullOf { it.exceptionOrNull() }
        }
        val usedRssFallback = tabEntries.isEmpty() && rssEntries.isNotEmpty()
        val sourceEntries = if (tabEntries.isNotEmpty()) tabEntries else rssEntries
        val streamItems =
            mapChannelFeedItems(
                entries = sourceEntries,
                canonicalChannelId = canonicalChannelId,
                fallbackChannelName = channelDetails.name,
                channelAvatar = channelDetails.avatarUrl,
                rssByVideoId = rssByVideoId,
                minimumDateMillis = minimumDateMillis,
                filterByContentType = usedRssFallback,
            )
        return Pair(subscription, streamItems)
    }

    private fun mapChannelFeedItems(
        entries: List<ChannelFeedEntry>,
        canonicalChannelId: String,
        fallbackChannelName: String?,
        channelAvatar: String?,
        rssByVideoId: Map<String, StreamItem>,
        minimumDateMillis: Long,
        filterByContentType: Boolean,
    ): List<StreamItem> {
        return entries
            .filter { it.isAvailable }
            .map { entry ->
                val raw = entry.item
                val videoId = raw.url.orEmpty().toID()
                val rssItem = rssByVideoId[videoId]
                raw.copy(
                    url = videoId.ifEmpty { raw.url },
                    title = rssItem?.title ?: raw.title,
                    uploaded = if (raw.uploaded > 0L) raw.uploaded else (rssItem?.uploaded ?: -1L),
                    uploadedDate = raw.uploadedDate ?: rssItem?.uploadedDate,
                    uploaderAvatar = channelAvatar ?: raw.uploaderAvatar,
                    uploaderUrl = canonicalChannelId,
                    uploaderName = raw.uploaderName ?: fallbackChannelName,
                )
            }
            .filter { item ->
                !filterByContentType || matchesEnabledContentFilters(item, preferences)
            }
            .let { selectChannelFeedVideos(it, minimumDateMillis) }
    }

    companion object {
        /**
         * Amount of feeds that are fetched concurrently.
         *
         * Should ideally be a factor of `BATCH_SIZE` to be correctly applied.
         */
        const val CHANNEL_CHUNK_SIZE = 5

        /**
         * Maximum amount of feeds that should be fetched together, before a delay should be
         * applied.
         */
        const val CHANNEL_BATCH_SIZE = 50

        /**
         * Millisecond delay after fetching `BATCH_SIZE` channels to avoid throttling.
         *
         * A channel is only counted as fetched when it had a recent upload, requiring to fetch the
         * channelInfo via Innertube.
         */
        val CHANNEL_BATCH_DELAY = (500L..1500L)

        private const val TAG_FEED = "LocalFeedRepository"
        private const val MAX_FEED_AGE_DAYS = 365L // 1 year
    }
}

private const val LowCadenceFallbackLimit = 15

private sealed class FeedRefreshPlan {
    data object UseCachedFeed : FeedRefreshPlan()

    data class Refresh(val channelIds: List<String>) : FeedRefreshPlan()
}

private fun planFeedRefresh(
    refresh: FeedRefresh,
    subscribedChannelIds: List<String>,
    cachedUploaderIds: Set<String>,
    millisSinceLastRefresh: Long,
    refreshIntervalMillis: Long,
): FeedRefreshPlan {
    return when (refresh) {
        is FeedRefresh.All -> FeedRefreshPlan.Refresh(subscribedChannelIds)
        is FeedRefresh.OnlySome -> FeedRefreshPlan.Refresh(refresh.channelIds)
        FeedRefresh.Automatically -> {
            val represented = cachedUploaderIds.map { it.toID() }.toSet()
            val missing = subscribedChannelIds.filter { it.toID() !in represented }
            val cacheCoversSubscriptions = represented.isNotEmpty() && missing.isEmpty()
            val fresh = millisSinceLastRefresh < refreshIntervalMillis
            when {
                cacheCoversSubscriptions && fresh -> FeedRefreshPlan.UseCachedFeed
                represented.isEmpty() || !fresh -> FeedRefreshPlan.Refresh(subscribedChannelIds)
                else -> FeedRefreshPlan.Refresh(missing)
            }
        }
    }
}

private fun shouldFetchChannelVideos(
    feedUploadTimesMillis: List<Long?>,
    newestVideoAlreadyStored: Boolean,
    minimumDateMillis: Long,
    hasCachedVideosForChannel: Boolean,
): Boolean {
    val knownTimes = feedUploadTimesMillis.mapNotNull { it?.takeIf { time -> time > 0L } }
    val newest = knownTimes.maxOrNull()
    return !hasCachedVideosForChannel ||
        newest == null ||
        (newest > minimumDateMillis && !newestVideoAlreadyStored)
}

private fun selectChannelFeedVideos(
    items: List<StreamItem>,
    minimumDateMillis: Long,
): List<StreamItem> {
    val inWindow = items.filter { it.uploaded <= 0L || it.uploaded > minimumDateMillis }
    return inWindow.ifEmpty { items.take(LowCadenceFallbackLimit) }
}

private fun matchesEnabledContentFilters(
    item: StreamItem,
    preferences: FeedPreferences,
): Boolean {
    val isVideo = !item.isShort && !item.isLive
    return when {
        !preferences.isContentFilterEnabled(ContentFilter.SHORTS) && item.isShort -> false
        !preferences.isContentFilterEnabled(ContentFilter.VIDEOS) && isVideo -> false
        !preferences.isContentFilterEnabled(ContentFilter.LIVESTREAMS) && item.isLive -> false
        else -> true
    }
}
