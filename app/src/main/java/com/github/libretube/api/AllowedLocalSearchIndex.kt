package com.github.libretube.api

import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.toID
import com.github.libretube.util.AllowedSnapshot

/**
 * In-process search and suggestion index over Allowed Channels, local feed items, and local search
 * history.
 */
class AllowedLocalSearchIndex(
    private val getAllowedSubscriptions: suspend () -> List<Subscription> =
        defaultAllowedSubscriptions,
    private val getLocalFeedItems: suspend () -> List<StreamItem> = defaultLocalFeedItems,
    private val getLocalSearchHistory: suspend () -> List<String> = defaultLocalSearchHistory,
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
                    matchesQuery(trimmed, tokens, it.title, it.uploaderName, it.shortDescription)
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

    private fun tokenizeQuery(trimmedQuery: String): List<String> {
        return trimmedQuery.lowercase().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
    }

    private fun matchesQuery(
        trimmedQuery: String,
        tokens: List<String>,
        vararg fields: String?,
    ): Boolean {
        val nonBlankFields = fields.mapNotNull { it?.takeIf(String::isNotBlank) }
        return trimmedQuery.isNotEmpty() &&
            nonBlankFields.isNotEmpty() &&
            (nonBlankFields.any { it.contains(trimmedQuery, ignoreCase = true) } ||
                (tokens.isNotEmpty() &&
                    tokens.all { token ->
                        nonBlankFields.any { field -> field.contains(token, ignoreCase = true) }
                    }))
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val FILTER_ALL = "all"
        private const val FILTER_VIDEOS = "videos"
        private const val FILTER_CHANNELS = "channels"

        val defaultAllowedSubscriptions: suspend () -> List<Subscription> = {
            runCatching { SubscriptionHelper.getSubscriptions() }.getOrDefault(emptyList())
        }
        val defaultLocalFeedItems: suspend () -> List<StreamItem> = {
            runCatching { DatabaseHolder.Database.feedDao().getAll().map { it.toStreamItem() } }
                .getOrDefault(emptyList())
        }
        val defaultLocalSearchHistory: suspend () -> List<String> = {
            runCatching {
                    DatabaseHolder.Database.searchHistoryDao().getAll().asReversed().map {
                        it.query
                    }
                }
                .getOrDefault(emptyList())
        }
    }
}
