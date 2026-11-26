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
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.toID
import com.github.libretube.player.StreamTransportContext
import com.github.libretube.util.AccessControl
import com.github.libretube.util.AllowedSnapshot

class ChannelNotAllowedException(
    val channelId: String? = null,
) : Exception("Channel is not in Allowed Channels")

class AllowlistMediaServiceRepository(
    private val delegate: MediaServiceRepository = NewPipeMediaServiceRepository(),
    private val accessControl: AccessControl = AccessControl.instance,
    getAllowedSubscriptions: suspend () -> List<Subscription> = defaultAllowedSubscriptions,
    getLocalFeedItems: suspend () -> List<StreamItem> = defaultLocalFeedItems,
    getLocalSearchHistory: suspend () -> List<String> = defaultLocalSearchHistory,
    private val maxSearchPrefetchPages: Int = DEFAULT_MAX_SEARCH_PREFETCH_PAGES,
    private val minUpstreamResultsPerLoad: Int = DEFAULT_MIN_UPSTREAM_SEARCH_RESULTS,
) : MediaServiceRepository {

    private val localSearchIndex =
        LocalSearchIndex(
            getAllowedSubscriptions = getAllowedSubscriptions,
            getLocalFeedItems = getLocalFeedItems,
            getLocalSearchHistory = getLocalSearchHistory,
        )

    private fun filterChannelRelatedStreams(channel: Channel, snapshot: AllowedSnapshot): Channel {
        return channel.copy(
            relatedStreams =
                channel.relatedStreams.filter {
                    it.uploaderUrl == null || snapshot.isStreamItemAllowed(it)
                },
        )
    }

    override suspend fun getStreams(videoId: String): Streams {
        val streams = delegate.getStreams(videoId)
        val snapshot = accessControl.snapshot()
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
        if (!accessControl.policy.canViewComments) {
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
        if (!accessControl.policy.canViewComments) {
            return CommentsPage(comments = emptyList(), disabled = true)
        }
        return delegate.getCommentsNextPage(videoId, nextPage)
    }

    override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult {
        val initialResult = delegate.getSearchResults(searchQuery, filter)
        val snapshot = accessControl.snapshot()
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
        val snapshot = accessControl.snapshot()
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
        val snapshot = accessControl.snapshot()
        if (!snapshot.allowlistEnabled) return delegate.getSuggestions(query)
        return localSearchIndex.suggest(query, snapshot)
    }

    override suspend fun getChannel(channelId: String): Channel {
        val snapshot = accessControl.snapshot()
        if (!snapshot.allowlistEnabled) return delegate.getChannel(channelId)
        if (!snapshot.isChannelAllowed(channelId)) {
            throw ChannelNotAllowedException(channelId)
        }
        return filterChannelRelatedStreams(delegate.getChannel(channelId), snapshot)
    }

    override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse {
        val res = delegate.getChannelTab(data, nextPage)
        val snapshot = accessControl.snapshot()
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
        val snapshot = accessControl.snapshot()
        if (!snapshot.allowlistEnabled) return channel
        if (!snapshot.isChannelAllowed(channel.id)) {
            throw ChannelNotAllowedException(channel.id ?: channelName)
        }
        return filterChannelRelatedStreams(channel, snapshot)
    }

    override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel {
        val snapshot = accessControl.snapshot()
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
        return accessControl.snapshot().filterPlaylist(delegate.getPlaylist(playlistId))
    }

    override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist {
        return accessControl
            .snapshot()
            .filterPlaylist(delegate.getPlaylistNextPage(playlistId, nextPage))
    }

    private class LocalSearchIndex(
        private val getAllowedSubscriptions: suspend () -> List<Subscription>,
        private val getLocalFeedItems: suspend () -> List<StreamItem>,
        private val getLocalSearchHistory: suspend () -> List<String>,
    ) {
        suspend fun search(
            query: String,
            filter: String,
            snapshot: AllowedSnapshot,
        ): List<ContentItem> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return emptyList()
            val tokens = tokenizeQuery(trimmed)
            val results = mutableListOf<ContentItem>()

            if (filter == FILTER_ALL || filter == FILTER_CHANNELS) {
                getAllowedSubscriptions()
                    .asSequence()
                    .filter { snapshot.isChannelAllowed(it.url) }
                    .filter { matchesQuery(trimmed, tokens, it.name) }
                    .mapTo(results) { sub ->
                        ContentItem(
                            url = sub.url.toID(),
                            type = StreamItem.TYPE_CHANNEL,
                            thumbnail = sub.avatar.orEmpty(),
                            name = sub.name,
                            uploaderAvatar = sub.avatar,
                            uploaderVerified = sub.verified,
                            verified = sub.verified,
                        )
                    }
            }

            if (filter == FILTER_ALL || filter == FILTER_VIDEOS) {
                getLocalFeedItems()
                    .asSequence()
                    .filter { snapshot.isStreamItemAllowed(it) }
                    .filter {
                        matchesQuery(
                            trimmed,
                            tokens,
                            it.title,
                            it.uploaderName,
                            it.shortDescription,
                        )
                    }
                    .mapTo(results) { item ->
                        ContentItem(
                            url = item.url.orEmpty().toID(),
                            type = StreamItem.TYPE_STREAM,
                            thumbnail = item.thumbnail.orEmpty(),
                            title = item.title,
                            uploaderUrl = item.uploaderUrl,
                            uploaderAvatar = item.uploaderAvatar,
                            duration = item.duration ?: -1L,
                            views = item.views ?: -1L,
                            isShort = item.isShort,
                            uploaderVerified = item.uploaderVerified,
                            verified = item.uploaderVerified,
                            uploaderName = item.uploaderName,
                            uploaded = item.uploaded,
                            shortDescription = item.shortDescription,
                        )
                    }
            }

            return results
        }

        suspend fun suggest(query: String, snapshot: AllowedSnapshot): List<String> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return emptyList()
            val tokens = tokenizeQuery(trimmed)

            val channelNames =
                getAllowedSubscriptions()
                    .asSequence()
                    .filter { snapshot.isChannelAllowed(it.url) }
                    .map { it.name.trim() }
                    .filter { it.isNotEmpty() && matchesQuery(trimmed, tokens, it) }
                    .toList()

            val feedTitles =
                getLocalFeedItems()
                    .asSequence()
                    .filter { snapshot.isStreamItemAllowed(it) }
                    .mapNotNull { it.title?.trim() }
                    .filter { it.isNotEmpty() && matchesQuery(trimmed, tokens, it) }
                    .toList()

            val historyQueries =
                getLocalSearchHistory()
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && matchesQuery(trimmed, tokens, it) }
                    .toList()

            val allCandidates = channelNames + feedTitles + historyQueries
            val (prefixMatches, otherMatches) =
                allCandidates.partition { it.startsWith(trimmed, ignoreCase = true) }
            return (prefixMatches + otherMatches).distinctBy { it.lowercase() }
        }

        private fun tokenizeQuery(trimmedQuery: String): List<String> =
            trimmedQuery.lowercase().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }

        private fun matchesQuery(
            trimmedQuery: String,
            tokens: List<String>,
            vararg fields: String?,
        ): Boolean {
            val nonBlankFields = fields.mapNotNull { it?.takeIf(String::isNotBlank) }
            val hasFullMatch = nonBlankFields.any { it.contains(trimmedQuery, ignoreCase = true) }
            val hasTokenMatch =
                tokens.isNotEmpty() &&
                    tokens.all { token ->
                        nonBlankFields.any { field -> field.contains(token, ignoreCase = true) }
                    }
            return trimmedQuery.isNotEmpty() &&
                nonBlankFields.isNotEmpty() &&
                (hasFullMatch || hasTokenMatch)
        }
    }

    companion object {
        const val DEFAULT_MAX_SEARCH_PREFETCH_PAGES = 5
        const val DEFAULT_MIN_UPSTREAM_SEARCH_RESULTS = 1

        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val FILTER_ALL = "all"
        private const val FILTER_VIDEOS = "videos"
        private const val FILTER_CHANNELS = "channels"

        private val defaultAllowedSubscriptions: suspend () -> List<Subscription> = {
            runCatching { SubscriptionHelper.getSubscriptions() }.getOrDefault(emptyList())
        }
        private val defaultLocalFeedItems: suspend () -> List<StreamItem> = {
            runCatching { DatabaseHolder.Database.feedDao().getAll().map { it.toStreamItem() } }
                .getOrDefault(emptyList())
        }
        private val defaultLocalSearchHistory: suspend () -> List<String> = {
            runCatching {
                    DatabaseHolder.Database.searchHistoryDao().getAll().asReversed().map {
                        it.query
                    }
                }
                .getOrDefault(emptyList())
        }
    }
}
