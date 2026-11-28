package com.github.libretube.repo

import android.util.Log
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.SeriesOrder
import com.github.libretube.db.obj.SeriesVideo
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.util.AccessControl
import com.github.libretube.util.ChannelSettingSet
import com.github.libretube.util.normalizeChannelId
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Persistence port for [Series] and the videos found in them. */
interface SeriesStore {
    suspend fun getAll(): List<Series>

    suspend fun get(seriesId: String): Series?

    suspend fun insert(series: Series)

    /** Replaces a followed Series' row, e.g. its order or metadata, keeping its videos. */
    suspend fun update(series: Series)

    suspend fun delete(seriesId: String)

    suspend fun getAllVideos(): List<SeriesVideo>

    suspend fun knownVideoIds(seriesId: String): Set<String>

    /** Adds videos; a video already known keeps its original [SeriesVideo.foundAt]. */
    suspend fun addVideos(videos: List<SeriesVideo>)

    suspend fun setCheckedVideoCount(seriesId: String, count: Int)

    suspend fun setLastOpened(seriesId: String, millis: Long)
}

/** Reads a playlist page by page, as the Allowlist-aware media repository returns it. */
interface SeriesPlaylistSource {
    suspend fun getPlaylist(playlistId: String): Playlist

    suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist
}

/** When the Series were last checked for new videos. */
interface SeriesRefreshPreferences {
    var lastSeriesRefreshMillis: Long
}

/**
 * Checks Series for new videos. Each Series costs one request when its video count hasn't changed
 * since its last check; otherwise its pages are read (up to [maxPages]) and compared against the
 * videos already known. The first check of a Series always reads its pages and records the videos
 * already there without marking any as new.
 */
class SeriesRefresher(
    private val store: SeriesStore,
    private val source: SeriesPlaylistSource,
    private val clock: Clock = Clock.systemUTC(),
    private val maxConcurrentChecks: Int = MAX_CONCURRENT_CHECKS,
    private val maxPages: Int = MAX_PAGES,
) {
    /** Checks every Series in [series]; a failure of one doesn't stop the rest. */
    suspend fun refresh(series: List<Series>): Throwable? {
        val lastFailure = AtomicReference<Throwable?>(null)
        val permits = Semaphore(maxConcurrentChecks)
        coroutineScope {
            series
                .map { item ->
                    async {
                        permits.withPermit {
                            runCatching { refreshOne(item) }
                                .onFailure { failure ->
                                    if (failure is CancellationException) throw failure
                                    lastFailure.compareAndSet(null, failure)
                                    runCatching {
                                        Log.w(TAG, "Series ${item.playlistId} failed", failure)
                                    }
                                }
                        }
                    }
                }
                .awaitAll()
        }
        return lastFailure.get()
    }

    suspend fun refreshOne(series: Series) {
        val seriesId = series.playlistId
        val firstPage = source.getPlaylist(seriesId)
        val countUnchanged =
            series.hasBaseline &&
                firstPage.videos > 0 &&
                firstPage.videos == series.checkedVideoCount
        if (countUnchanged) return

        val items = firstPage.relatedStreams.toMutableList()
        var nextPage = firstPage.nextpage
        var pagesRead = 1
        while (nextPage != null && pagesRead < maxPages) {
            val page = source.getPlaylistNextPage(seriesId, nextPage)
            items += page.relatedStreams
            nextPage = page.nextpage
            pagesRead++
        }

        val known = store.knownVideoIds(seriesId)
        val foundAt = if (series.hasBaseline) clock.millis() else 0L
        val found =
            items
                .mapIndexedNotNull { index, item ->
                    val videoId = item.url?.toID()?.takeIf { it.isNotEmpty() }
                    if (videoId == null || videoId in known) null
                    else item.toSeriesVideo(seriesId, videoId, index, foundAt)
                }
                .distinctBy { it.videoId }
        if (found.isNotEmpty()) store.addVideos(found)
        store.setCheckedVideoCount(seriesId, firstPage.videos.coerceAtLeast(0))
    }

    companion object {
        const val MAX_CONCURRENT_CHECKS = 3
        const val MAX_PAGES = 20
        private const val TAG = "SeriesRefresher"
    }
}

private fun StreamItem.toSeriesVideo(
    seriesId: String,
    videoId: String,
    position: Int,
    foundAt: Long,
) =
    SeriesVideo(
        seriesId = seriesId,
        videoId = videoId,
        position = position,
        title = title,
        thumbnail = thumbnail?.let { ProxyHelper.unwrapUrl(it) },
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl,
        uploaderAvatar = uploaderAvatar?.let { ProxyHelper.unwrapUrl(it) },
        duration = duration,
        uploaded = uploaded.takeIf { it > 0L } ?: -1L,
        isShort = isShort,
        foundAt = foundAt,
    )

/**
 * Merges the channel feed with Series videos. A video that is both a channel upload and in a Series
 * appears once, labelled with its Series. Uploads from channels whose Feed Scope is Series only
 * ([seriesOnlyChannelIds]) reach the feed only through their Series.
 */
fun mergeSeriesIntoFeed(
    channelFeed: List<StreamItem>,
    seriesVideos: List<StreamItem>,
    seriesOnlyChannelIds: Set<String> = emptySet(),
): List<StreamItem> {
    val uniqueSeriesVideos = seriesVideos.distinctBy { it.url?.toID() }
    val seriesVideoIds = uniqueSeriesVideos.mapNotNull { it.url?.toID() }.toSet()
    val channelVideos = channelFeed.filter { item ->
        item.url?.toID() !in seriesVideoIds &&
            normalizeChannelId(item.uploaderUrl) !in seriesOnlyChannelIds
    }
    // stable: equal or unknown upload dates keep channel-feed and Series order
    return (channelVideos + uniqueSeriesVideos).sortedByDescending { it.uploaded }
}

/**
 * @param hasNewEpisode a video was found after the Series was last opened (the new-episode dot);
 *   independent of [unwatched]
 */
data class SeriesSummary(val unwatched: Int, val hasNewEpisode: Boolean)

/**
 * Orders a Series' videos for display and playback. Videos with an unknown upload date go last,
 * keeping their playlist order.
 */
fun <T> List<T>.sortedBySeriesOrder(order: SeriesOrder, uploaded: (T) -> Long): List<T> =
    when (order) {
        SeriesOrder.PLAYLIST -> this
        SeriesOrder.NEWEST_FIRST ->
            sortedWith(compareBy<T> { uploaded(it) <= 0L }.thenByDescending { uploaded(it) })
        SeriesOrder.OLDEST_FIRST ->
            sortedWith(compareBy<T> { uploaded(it) <= 0L }.thenBy { uploaded(it) })
    }

/**
 * The play queue for a Series: the chosen video (or, without one, the first unwatched video in
 * order) followed by the unwatched videos after it. When everything is watched, playing starts from
 * the beginning.
 */
fun seriesQueue(
    ordered: List<StreamItem>,
    start: StreamItem?,
    isWatched: (StreamItem) -> Boolean,
): List<StreamItem> {
    val chosen = start?.let { s -> ordered.indexOfFirst { it.url?.toID() == s.url?.toID() } }
    val startIndex =
        chosen?.takeIf { it >= 0 } ?: ordered.indexOfFirst { !isWatched(it) }.takeIf { it >= 0 }
    return if (startIndex == null) {
        ordered
    } else {
        listOf(ordered[startIndex]) + ordered.drop(startIndex + 1).filterNot(isWatched)
    }
}

/**
 * Series (followed playlists, see GLOSSARY.md and ADR-0003): following, checking for new videos on
 * the daily feed refresh, and the Allowlist rules for them.
 */
class SeriesRepository(
    /** Direct access for changes the Allowlist doesn't care about (unfollow, order, metadata). */
    val store: SeriesStore = RoomSeriesStore,
    private val playlistSource: SeriesPlaylistSource = MediaServiceSeriesPlaylistSource,
    private val refresher: SeriesRefresher = SeriesRefresher(store, playlistSource),
    private val accessControl: AccessControl = AccessControl.instance,
    private val preferences: SeriesRefreshPreferences = PreferenceSeriesRefreshPreferences,
    private val seriesOnlyFeedScope: ChannelSettingSet? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Series the user can see: those whose owner is an Allowed Channel while the Allowlist is on.
     */
    suspend fun visibleSeries(): List<Series> =
        accessControl.snapshot().filterSeries(store.getAll())

    /** Whether the Follow control is offered for a playlist owned by [ownerUrl]. */
    suspend fun canFollow(ownerUrl: String?): Boolean =
        accessControl.snapshot().isChannelAllowed(ownerUrl)

    /**
     * Whether a public playlist can be followed. With the Allowlist on, its owner must be an
     * Allowed Channel, which takes one request to find out.
     */
    suspend fun canFollowPlaylist(playlistId: String): Boolean {
        val snapshot = accessControl.snapshot()
        if (!snapshot.allowlistEnabled) return true
        return snapshot.isChannelAllowed(playlistSource.getPlaylist(playlistId).uploaderUrl)
    }

    /** Videos of every visible Series, without videos from channels that aren't allowed. */
    suspend fun visibleSeriesVideos(): List<StreamItem> {
        val snapshot = accessControl.snapshot()
        val seriesById = snapshot.filterSeries(store.getAll()).associateBy { it.playlistId }
        val videos =
            store
                .getAllVideos()
                .filter { it.seriesId in seriesById }
                .sortedWith(compareBy({ it.seriesId }, { it.position }))
                .map { it.toStreamItem(seriesById[it.seriesId]?.playlistName) }
        return snapshot.filterStreamItems(videos)
    }

    /** The channel feed with Series videos merged in and Feed Scope applied. */
    suspend fun mergeIntoFeed(channelFeed: List<StreamItem>): List<StreamItem> =
        mergeSeriesIntoFeed(
            channelFeed = channelFeed,
            seriesVideos = visibleSeriesVideos(),
            seriesOnlyChannelIds = seriesOnlyFeedScope?.channelIds().orEmpty(),
        )

    /**
     * Checks visible Series for new videos when [force]d, once a day, or when a Series has no
     * baseline yet.
     */
    suspend fun refreshIfDue(force: Boolean): Throwable? {
        val series = visibleSeries()
        val now = clock.millis()
        val due =
            series.isNotEmpty() &&
                (force ||
                    now - preferences.lastSeriesRefreshMillis >= REFRESH_INTERVAL.toMillis() ||
                    series.any { !it.hasBaseline })
        val failure = if (due) refresher.refresh(series) else null
        if (due && failure == null) preferences.lastSeriesRefreshMillis = now
        return failure
    }

    /**
     * Follows [series] (again). It starts without a baseline, so its first check records the videos
     * already in it instead of marking them new.
     */
    suspend fun follow(series: Series) {
        if (!canFollow(series.uploaderUrl)) return
        val fresh = series.copy(checkedVideoCount = Series.NO_BASELINE, lastOpened = clock.millis())
        store.insert(fresh)
        runCatching { refresher.refreshOne(fresh) }
            .onFailure { if (it is CancellationException) throw it }
    }

    suspend fun markOpened(seriesId: String) = store.setLastOpened(seriesId, clock.millis())

    /**
     * Each visible Series' unwatched count and whether it has a video found since it was last
     * opened. Videos from channels that aren't allowed don't count.
     */
    suspend fun summaries(
        isWatched: suspend (videoId: String, durationSeconds: Long?) -> Boolean,
    ): Map<String, SeriesSummary> =
        computeSeriesSummaries(visibleSeries(), allowedVideosBySeries()) { video ->
            isWatched(video.videoId, video.duration)
        }

    /**
     * Batch-fetches watch positions once for all visible Series' videos via [getWatchPositions],
     * avoiding per-video queries.
     */
    suspend fun summaries(
        getWatchPositions: suspend (Collection<String>) -> Map<String, Long>,
        isWatched: (positionMillis: Long, durationSeconds: Long?) -> Boolean,
    ): Map<String, SeriesSummary> {
        val visible = visibleSeries()
        val videosBySeries = allowedVideosBySeries()
        val visibleIds = visible.mapTo(mutableSetOf()) { it.playlistId }
        val videoIds =
            videosBySeries.entries
                .asSequence()
                .filter { it.key in visibleIds }
                .flatMap { it.value }
                .map { it.videoId }
                .toList()
        val positions = getWatchPositions(videoIds)
        return computeSeriesSummaries(visible, videosBySeries) { video ->
            val position = positions[video.videoId] ?: return@computeSeriesSummaries false
            isWatched(position, video.duration)
        }
    }

    private suspend fun allowedVideosBySeries(): Map<String, List<SeriesVideo>> {
        val snapshot = accessControl.snapshot()
        return store
            .getAllVideos()
            .filter { snapshot.isChannelAllowed(it.uploaderUrl) }
            .groupBy {
                it.seriesId
            }
    }

    companion object {
        private val REFRESH_INTERVAL: Duration = Duration.ofDays(1)

        val instance: SeriesRepository by lazy {
            SeriesRepository(seriesOnlyFeedScope = ChannelSettingSet.seriesOnlyFeedScope)
        }
    }
}

private inline fun computeSeriesSummaries(
    visible: List<Series>,
    videosBySeries: Map<String, List<SeriesVideo>>,
    isWatched: (SeriesVideo) -> Boolean,
): Map<String, SeriesSummary> = visible.associate { series ->
    val videos = videosBySeries[series.playlistId].orEmpty()
    series.playlistId to
        SeriesSummary(
            unwatched = videos.count { !isWatched(it) },
            hasNewEpisode = videos.any { it.foundAt > series.lastOpened },
        )
}

internal object RoomSeriesStore : SeriesStore {
    private val dao
        get() = DatabaseHolder.Database.seriesDao()

    private val videoDao
        get() = DatabaseHolder.Database.seriesVideoDao()

    override suspend fun getAll(): List<Series> = dao.getAll()

    override suspend fun get(seriesId: String): Series? = dao.findById(seriesId)

    override suspend fun insert(series: Series) = dao.insert(series)

    override suspend fun update(series: Series) = dao.update(series)

    override suspend fun delete(seriesId: String) = dao.deleteById(seriesId)

    override suspend fun getAllVideos(): List<SeriesVideo> = videoDao.getAll()

    override suspend fun knownVideoIds(seriesId: String): Set<String> =
        videoDao.getVideoIds(seriesId).toSet()

    override suspend fun addVideos(videos: List<SeriesVideo>) = videoDao.insertAll(videos)

    override suspend fun setCheckedVideoCount(seriesId: String, count: Int) =
        dao.setCheckedVideoCount(seriesId, count)

    override suspend fun setLastOpened(seriesId: String, millis: Long) =
        dao.setLastOpened(seriesId, millis)
}

internal object MediaServiceSeriesPlaylistSource : SeriesPlaylistSource {
    override suspend fun getPlaylist(playlistId: String): Playlist =
        MediaServiceRepository.instance.getPlaylist(playlistId)

    override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist =
        MediaServiceRepository.instance.getPlaylistNextPage(playlistId, nextPage)
}

internal object PreferenceSeriesRefreshPreferences : SeriesRefreshPreferences {
    override var lastSeriesRefreshMillis: Long
        get() = PreferenceHelper.getLong(PreferenceKeys.LAST_SERIES_REFRESH_MILLIS, 0)
        set(value) = PreferenceHelper.putLong(PreferenceKeys.LAST_SERIES_REFRESH_MILLIS, value)
}

/** The feed: subscribed channels' uploads plus Series videos, with Feed Scope applied. */
class SeriesAwareFeedRepository(
    private val delegate: FeedRepository = LocalFeedRepository(),
    private val series: SeriesRepository = SeriesRepository.instance,
    private val seriesOnlyFeedScope: ChannelSettingSet? = ChannelSettingSet.seriesOnlyFeedScope,
) : FeedRepository {
    override suspend fun getFeed(
        refresh: FeedRefresh,
        onProgressUpdate: (FeedProgress) -> Unit,
    ): List<StreamItem> {
        val channelFeed = runCatching { delegate.getFeed(refresh, onProgressUpdate) }
        channelFeed.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (refresh !is FeedRefresh.OnlySome)
            series.refreshIfDue(force = refresh is FeedRefresh.All)
        val merged = series.mergeIntoFeed(channelFeed.getOrDefault(emptyList()))
        val failure = channelFeed.exceptionOrNull()
        if (failure != null && merged.isEmpty()) throw failure
        return merged
    }

    /** Unsubscribing also drops the channel's Feed Scope. */
    override suspend fun removeChannel(channelId: String) {
        delegate.removeChannel(channelId)
        seriesOnlyFeedScope?.set(channelId, enabled = false)
    }

    override suspend fun submitFeedItemChange(feedItem: SubscriptionsFeedItem) =
        delegate.submitFeedItemChange(feedItem)
}
