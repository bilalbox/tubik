package com.github.libretube.util

import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.LocalPlaylistItem
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.extensions.toID
import kotlinx.coroutines.CancellationException

/** Immutable point-in-time snapshot of Allowlist enforcement state and Allowed Channel IDs. */
data class AllowedSnapshot(
    val allowlistEnabled: Boolean,
    val allowedChannelIds: Set<String> = emptySet(),
) {
    private val normalizedAllowedIds: Set<String> =
        allowedChannelIds.mapNotNullTo(mutableSetOf()) { it.toID().takeIf(String::isNotBlank) }

    fun isChannelAllowed(channelIdOrUrl: String?): Boolean {
        if (!allowlistEnabled) return true
        val normalizedId =
            channelIdOrUrl?.takeIf { it.isNotBlank() }?.toID()?.takeIf { it.isNotBlank() }
        return normalizedId != null && normalizedId in normalizedAllowedIds
    }

    fun isStreamItemAllowed(item: StreamItem): Boolean =
        isTypedItemAllowed(item.type, item.url, item.uploaderUrl)

    fun isContentItemAllowed(item: ContentItem): Boolean =
        isTypedItemAllowed(item.type, item.url, item.uploaderUrl)

    private fun isTypedItemAllowed(type: String?, url: String?, uploaderUrl: String?): Boolean {
        val channelIdOrUrl = if (type == StreamItem.TYPE_CHANNEL) url else uploaderUrl
        return isChannelAllowed(channelIdOrUrl)
    }

    fun filterStreamItems(items: List<StreamItem>): List<StreamItem> {
        if (!allowlistEnabled) return items
        return items.filter(::isStreamItemAllowed)
    }

    fun filterPlaylist(playlist: Playlist): Playlist {
        if (!allowlistEnabled) return playlist
        return playlist.copy(relatedStreams = filterStreamItems(playlist.relatedStreams))
    }

    fun filterWatchHistoryEntries(entries: List<WatchHistoryEntry>): List<WatchHistoryEntry> =
        filterWhenEnabled(entries) { it.video.uploaderUrl }

    fun filterLocalPlaylistItems(items: List<LocalPlaylistItem>): List<LocalPlaylistItem> =
        filterWhenEnabled(items) { it.uploaderUrl }

    fun filterPlaylistBookmarks(bookmarks: List<PlaylistBookmark>): List<PlaylistBookmark> =
        filterWhenEnabled(bookmarks) { it.uploaderUrl }

    fun filterDownloads(downloads: List<DownloadWithItems>): List<DownloadWithItems> =
        filterWhenEnabled(downloads) { it.download.uploaderUrl }

    private inline fun <T> filterWhenEnabled(
        items: List<T>,
        channelSelector: (T) -> String?,
    ): List<T> =
        if (!allowlistEnabled) items else items.filter { isChannelAllowed(channelSelector(it)) }
}

/** Provides current Allowlist/Music Removal flags and fresh per-operation snapshots. */
interface AllowedSnapshotProvider {
    fun isAllowlistEnabled(): Boolean

    fun isMusicRemovalEnabled(): Boolean = false

    suspend fun snapshot(): AllowedSnapshot
}

/**
 * Default [AllowedSnapshotProvider] backed by [ParentPinStore] and [SubscriptionHelper], falling
 * back to full subscription objects when channel-ID queries fail with non-cancellation errors.
 */
class SubscriptionAllowedSnapshotProvider(
    private val isAllowlistEnabled: () -> Boolean = {
        ParentPinStore.instance.isAllowlistEnabled()
    },
    private val isMusicRemovalEnabled: () -> Boolean = {
        ParentPinStore.instance.isMusicRemovalEnabled()
    },
    private val getAllowedChannelIds: suspend () -> Set<String> = {
        SubscriptionHelper.getSubscriptionChannelIds().toSet()
    },
    private val getAllowedSubscriptions: suspend () -> List<Subscription> = {
        runCatching { SubscriptionHelper.getSubscriptions() }.getOrDefault(emptyList())
    },
) : AllowedSnapshotProvider {

    override fun isAllowlistEnabled(): Boolean = isAllowlistEnabled.invoke()

    override fun isMusicRemovalEnabled(): Boolean = isMusicRemovalEnabled.invoke()

    override suspend fun snapshot(): AllowedSnapshot {
        if (!isAllowlistEnabled.invoke()) {
            return AllowedSnapshot(allowlistEnabled = false)
        }
        val channelIds =
            try {
                getAllowedChannelIds()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                getAllowedSubscriptions().mapTo(mutableSetOf()) { sub -> sub.url.toID() }
            }
        return AllowedSnapshot(allowlistEnabled = true, allowedChannelIds = channelIds)
    }
}

/**
 * Centralized Allowed Channel enforcement and UI capability policy backed by
 * [AllowedSnapshotProvider].
 */
class AllowedCatalog(
    private val snapshotProvider: AllowedSnapshotProvider = SubscriptionAllowedSnapshotProvider(),
) {
    suspend fun snapshot(): AllowedSnapshot = snapshotProvider.snapshot()

    suspend fun isChannelAllowed(channelIdOrUrl: String?): Boolean =
        snapshot().isChannelAllowed(channelIdOrUrl)

    suspend fun filterPlaylist(playlist: Playlist): Playlist = snapshot().filterPlaylist(playlist)

    suspend fun filterDownloads(downloads: List<DownloadWithItems>): List<DownloadWithItems> =
        snapshot().filterDownloads(downloads)

    fun canModifySubscriptions(): Boolean = !snapshotProvider.isAllowlistEnabled()

    fun canViewComments(): Boolean = !snapshotProvider.isAllowlistEnabled()

    fun canViewRelatedStreams(relatedStreamsEnabled: Boolean): Boolean =
        relatedStreamsEnabled && !snapshotProvider.isAllowlistEnabled()

    fun areSearchSuggestionsEnabled(searchSuggestionsEnabled: Boolean): Boolean =
        searchSuggestionsEnabled || snapshotProvider.isAllowlistEnabled()

    fun shouldHideMusicSearchChips(): Boolean =
        snapshotProvider.isAllowlistEnabled() || snapshotProvider.isMusicRemovalEnabled()

    companion object {
        val instance: AllowedCatalog by lazy { AllowedCatalog() }
    }
}
