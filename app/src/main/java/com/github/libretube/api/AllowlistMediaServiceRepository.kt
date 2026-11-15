package com.github.libretube.api

import com.github.libretube.api.obj.Channel
import com.github.libretube.api.obj.ChannelTabResponse
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.SearchResult
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subscription
import com.github.libretube.extensions.toID
import com.github.libretube.player.StreamTransportContext
import com.github.libretube.util.AllowedCatalog
import com.github.libretube.util.AllowedSnapshot

class ChannelNotAllowedException(
    val channelId: String? = null,
) : Exception("Channel is not in Allowed Channels")

class AllowlistMediaServiceRepository(
    private val delegate: MediaServiceRepository = NewPipeMediaServiceRepository(),
    private val allowedCatalog: AllowedCatalog = AllowedCatalog.instance,
    getAllowedSubscriptions: suspend () -> List<Subscription> =
        AllowedLocalSearchIndex.defaultAllowedSubscriptions,
    getLocalFeedItems: suspend () -> List<StreamItem> =
        AllowedLocalSearchIndex.defaultLocalFeedItems,
    getLocalSearchHistory: suspend () -> List<String> =
        AllowedLocalSearchIndex.defaultLocalSearchHistory,
    private val maxSearchPrefetchPages: Int = DEFAULT_MAX_SEARCH_PREFETCH_PAGES,
    private val minUpstreamResultsPerLoad: Int = DEFAULT_MIN_UPSTREAM_SEARCH_RESULTS,
    private val localSearchIndex: AllowedLocalSearchIndex =
        AllowedLocalSearchIndex(
            getAllowedSubscriptions = getAllowedSubscriptions,
            getLocalFeedItems = getLocalFeedItems,
            getLocalSearchHistory = getLocalSearchHistory,
        ),
) : MediaServiceRepository {

    private fun filterChannelRelatedStreams(channel: Channel, snapshot: AllowedSnapshot): Channel {
        return channel.copy(
            relatedStreams =
                channel.relatedStreams.filter {
                    it.uploaderUrl == null || snapshot.isStreamItemAllowed(it)
                },
        )
    }

    override fun getTrendingCategories(): List<TrendingCategory> = delegate.getTrendingCategories()

    override suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem> {
        val items = delegate.getTrending(region, category)
        return allowedCatalog.snapshot().filterStreamItems(items)
    }

    override suspend fun getStreams(videoId: String): Streams {
        val streams = delegate.getStreams(videoId)
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return streams
        if (!snapshot.isChannelAllowed(streams.uploaderUrl)) {
            throw ChannelNotAllowedException(streams.uploaderUrl)
        }
        return streams.copy(relatedStreams = emptyList())
    }

    override fun getStreamTransportContext(videoId: String): StreamTransportContext {
        return delegate.getStreamTransportContext(videoId)
    }

    override fun invalidateStreamsCache(videoId: String) {
        delegate.invalidateStreamsCache(videoId)
    }

    override suspend fun getComments(videoId: String): CommentsPage {
        if (!allowedCatalog.canViewComments()) {
            return CommentsPage(comments = emptyList(), disabled = true)
        }
        return delegate.getComments(videoId)
    }

    override suspend fun getSegments(
        videoId: String,
        category: List<String>,
        actionType: List<String>?,
    ): SegmentData {
        return delegate.getSegments(videoId, category, actionType)
    }

    override suspend fun getDeArrowContent(videoId: String): DeArrowContent? {
        return delegate.getDeArrowContent(videoId)
    }

    override suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage {
        if (!allowedCatalog.canViewComments()) {
            return CommentsPage(comments = emptyList(), disabled = true)
        }
        return delegate.getCommentsNextPage(videoId, nextPage)
    }

    override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult {
        val initialResult = delegate.getSearchResults(searchQuery, filter)
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return initialResult
        val localItems = localSearchIndex.search(searchQuery, filter, snapshot)
        return fetchAllowedSearchPage(
            searchQuery = searchQuery,
            filter = filter,
            initialResult = initialResult,
            snapshot = snapshot,
            localItems = localItems,
        )
    }

    override suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String,
    ): SearchResult {
        val initialResult = delegate.getSearchResultsNextPage(searchQuery, filter, nextPage)
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return initialResult
        return fetchAllowedSearchPage(
            searchQuery = searchQuery,
            filter = filter,
            initialResult = initialResult,
            snapshot = snapshot,
            localItems = emptyList(),
        )
    }

    private suspend fun fetchAllowedSearchPage(
        searchQuery: String,
        filter: String,
        initialResult: SearchResult,
        snapshot: AllowedSnapshot,
        localItems: List<ContentItem>,
    ): SearchResult {
        val seenUpstreamIds = mutableSetOf<String>()
        val upstreamItems = mutableListOf<ContentItem>()

        fun appendAllowedItems(items: List<ContentItem>) {
            for (item in items) {
                if (snapshot.isContentItemAllowed(item)) {
                    val key = "${item.type}:${item.url.toID()}"
                    if (seenUpstreamIds.add(key)) {
                        upstreamItems.add(item)
                    }
                }
            }
        }

        appendAllowedItems(initialResult.items)

        var currentNextPage = initialResult.nextpage
        var pagesFetched = 0
        while (
            upstreamItems.size < minUpstreamResultsPerLoad &&
                currentNextPage != null &&
                pagesFetched < maxSearchPrefetchPages
        ) {
            pagesFetched++
            val nextRes =
                runCatching {
                    delegate.getSearchResultsNextPage(searchQuery, filter, currentNextPage)
                }
                    .getOrNull() ?: break
            appendAllowedItems(nextRes.items)
            currentNextPage = nextRes.nextpage
        }

        val combined = (localItems + upstreamItems).distinctBy { "${it.type}:${it.url.toID()}" }
        return SearchResult(
            items = combined,
            nextpage = currentNextPage,
            suggestion = null,
            corrected = false,
        )
    }

    override suspend fun getSuggestions(query: String): List<String> {
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return delegate.getSuggestions(query)
        return localSearchIndex.suggest(query, snapshot)
    }

    override suspend fun getChannel(channelId: String): Channel {
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return delegate.getChannel(channelId)
        if (!snapshot.isChannelAllowed(channelId)) {
            throw ChannelNotAllowedException(channelId)
        }
        return filterChannelRelatedStreams(delegate.getChannel(channelId), snapshot)
    }

    override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse {
        val res = delegate.getChannelTab(data, nextPage)
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return res
        return res.copy(
            content =
                res.content.filter {
                    (it.type != StreamItem.TYPE_CHANNEL && it.uploaderUrl == null) ||
                        snapshot.isContentItemAllowed(it)
                },
        )
    }

    override suspend fun getChannelByName(channelName: String): Channel {
        val channel = delegate.getChannelByName(channelName)
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return channel
        if (!snapshot.isChannelAllowed(channel.id)) {
            throw ChannelNotAllowedException(channel.id ?: channelName)
        }
        return filterChannelRelatedStreams(channel, snapshot)
    }

    override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel {
        val snapshot = allowedCatalog.snapshot()
        if (!snapshot.allowlistEnabled) return delegate.getChannelNextPage(channelId, nextPage)
        if (!snapshot.isChannelAllowed(channelId)) {
            throw ChannelNotAllowedException(channelId)
        }
        return filterChannelRelatedStreams(
            delegate.getChannelNextPage(channelId, nextPage),
            snapshot,
        )
    }

    override suspend fun getPlaylist(playlistId: String): Playlist {
        return allowedCatalog.filterPlaylist(delegate.getPlaylist(playlistId))
    }

    override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist {
        return allowedCatalog.filterPlaylist(delegate.getPlaylistNextPage(playlistId, nextPage))
    }

    companion object {
        const val DEFAULT_MAX_SEARCH_PREFETCH_PAGES = 5
        const val DEFAULT_MIN_UPSTREAM_SEARCH_RESULTS = 1
    }
}
