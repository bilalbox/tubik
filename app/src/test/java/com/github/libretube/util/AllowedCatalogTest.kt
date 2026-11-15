package com.github.libretube.util

import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.LocalPlaylistItem
import com.github.libretube.db.obj.PlaylistBookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AllowedCatalogTest {

    private fun testCatalog(
        isAllowlistEnabled: () -> Boolean,
        isMusicRemovalEnabled: () -> Boolean = { false },
        allowedChannelIds: () -> Set<String> = { emptySet() },
    ): AllowedCatalog =
        AllowedCatalog(
            snapshotProvider =
                object : AllowedSnapshotProvider {
                    override fun isAllowlistEnabled(): Boolean = isAllowlistEnabled()

                    override fun isMusicRemovalEnabled(): Boolean = isMusicRemovalEnabled()

                    override suspend fun snapshot(): AllowedSnapshot =
                        AllowedSnapshot(
                            allowlistEnabled = isAllowlistEnabled(),
                            allowedChannelIds = allowedChannelIds(),
                        )
                },
        )

    @Test
    fun `snapshot resolves fresh allowlist state per call and normalizes channel IDs and URLs`() =
        runBlocking {
            var enabled = true
            var allowedIds = setOf("UC_ALLOWED_1", "https://www.youtube.com/channel/UC_ALLOWED_2")
            val catalog =
                testCatalog(
                    isAllowlistEnabled = { enabled },
                    allowedChannelIds = { allowedIds },
                )

            val first = catalog.snapshot()
            assertTrue(first.allowlistEnabled)
            assertTrue(first.isChannelAllowed("UC_ALLOWED_1"))
            assertTrue(first.isChannelAllowed("https://www.youtube.com/channel/UC_ALLOWED_2"))
            assertTrue(first.isChannelAllowed("/channel/UC_ALLOWED_1/videos"))
            assertFalse(first.isChannelAllowed("UC_BLOCKED"))
            assertFalse(first.isChannelAllowed(null))
            assertFalse(first.isChannelAllowed("   "))

            // Mutating underlying source affects subsequent operations without affecting prior
            // snapshot
            allowedIds = setOf("UC_ALLOWED_3")
            assertFalse(first.isChannelAllowed("UC_ALLOWED_3"))

            val second = catalog.snapshot()
            assertTrue(second.isChannelAllowed("UC_ALLOWED_3"))
            assertFalse(second.isChannelAllowed("UC_ALLOWED_1"))

            enabled = false
            val disabled = catalog.snapshot()
            assertFalse(disabled.allowlistEnabled)
            assertTrue(disabled.isChannelAllowed("UC_BLOCKED"))
            assertTrue(disabled.isChannelAllowed(null))
        }

    @Test
    fun `SubscriptionAllowedSnapshotProvider skips subscription lookup when Allowlist is off`() =
        runBlocking {
            var channelIdCalls = 0
            var subscriptionCalls = 0
            val offProvider =
                SubscriptionAllowedSnapshotProvider(
                    isAllowlistEnabled = { false },
                    isMusicRemovalEnabled = { false },
                    getAllowedChannelIds = {
                        channelIdCalls++
                        setOf("UC_1")
                    },
                    getAllowedSubscriptions = {
                        subscriptionCalls++
                        emptyList()
                    },
                )

            val offSnapshot = offProvider.snapshot()
            assertFalse(offSnapshot.allowlistEnabled)
            assertEquals(0, channelIdCalls)
            assertEquals(0, subscriptionCalls)
        }

    @Test
    fun `SubscriptionAllowedSnapshotProvider falls back on failure and rethrows CancellationException`() =
        runBlocking {
            var subscriptionCalls = 0
            val failingProvider =
                SubscriptionAllowedSnapshotProvider(
                    isAllowlistEnabled = { true },
                    isMusicRemovalEnabled = { false },
                    getAllowedChannelIds = { throw IllegalStateException("DB busy") },
                    getAllowedSubscriptions = {
                        subscriptionCalls++
                        listOf(
                            Subscription(
                                url = "https://www.youtube.com/channel/UC_FALLBACK",
                                name = "Fallback",
                                verified = true,
                            ),
                        )
                    },
                )
            val fallbackSnapshot = failingProvider.snapshot()
            assertTrue(fallbackSnapshot.allowlistEnabled)
            assertTrue(fallbackSnapshot.isChannelAllowed("UC_FALLBACK"))
            assertFalse(fallbackSnapshot.isChannelAllowed("UC_OTHER"))
            assertEquals(1, subscriptionCalls)

            var fallbackCalledOnCancel = false
            val cancelledProvider =
                SubscriptionAllowedSnapshotProvider(
                    isAllowlistEnabled = { true },
                    isMusicRemovalEnabled = { false },
                    getAllowedChannelIds = { throw CancellationException("Cancelled") },
                    getAllowedSubscriptions = {
                        fallbackCalledOnCancel = true
                        emptyList()
                    },
                )
            try {
                cancelledProvider.snapshot()
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // Expected
            }
            assertFalse(fallbackCalledOnCancel)
        }

    @Test
    fun `snapshot filters remote stream, content, and playlist items`() = runBlocking {
        val catalog =
            testCatalog(
                isAllowlistEnabled = { true },
                allowedChannelIds = { setOf("UC_ALLOWED_1", "UC_ALLOWED_2") },
            )
        val streamItems =
            listOf(
                StreamItem(url = "v1", type = StreamItem.TYPE_STREAM, uploaderUrl = "UC_ALLOWED_1"),
                StreamItem(url = "v2", type = StreamItem.TYPE_STREAM, uploaderUrl = "UC_BLOCKED"),
                StreamItem(url = "/channel/UC_ALLOWED_2", type = StreamItem.TYPE_CHANNEL),
                StreamItem(url = "/channel/UC_BLOCKED", type = StreamItem.TYPE_CHANNEL),
            )
        val snapshot = catalog.snapshot()
        assertEquals(
            listOf("v1", "/channel/UC_ALLOWED_2"),
            snapshot.filterStreamItems(streamItems).map { it.url },
        )

        val contentItems =
            listOf(
                ContentItem(
                    url = "c1",
                    type = StreamItem.TYPE_STREAM,
                    thumbnail = "",
                    uploaderUrl = "UC_ALLOWED_2",
                ),
                ContentItem(
                    url = "c2",
                    type = StreamItem.TYPE_STREAM,
                    thumbnail = "",
                    uploaderUrl = "UC_BLOCKED",
                ),
                ContentItem(url = "UC_ALLOWED_1", type = StreamItem.TYPE_CHANNEL, thumbnail = ""),
                ContentItem(url = "UC_BLOCKED", type = StreamItem.TYPE_CHANNEL, thumbnail = ""),
            )
        assertEquals(
            listOf("c1", "UC_ALLOWED_1"),
            contentItems.filter { snapshot.isContentItemAllowed(it) }.map { it.url },
        )

        val playlist = Playlist(name = "Mix", relatedStreams = streamItems)
        assertEquals(
            listOf("v1", "/channel/UC_ALLOWED_2"),
            catalog.filterPlaylist(playlist).relatedStreams.map { it.url },
        )
    }

    @Test
    fun `catalog and snapshot filter watch history, local playlists, bookmarks, and downloads`() =
        runBlocking {
            var enabled = true
            val catalog =
                testCatalog(
                    isAllowlistEnabled = { enabled },
                    allowedChannelIds = { setOf("UC_ALLOWED_1", "UC_ALLOWED_2") },
                )
            val snapshot = catalog.snapshot()

            val historyEntries =
                listOf(
                    WatchHistoryEntry(
                        WatchHistoryEntryMetadata("v1", 1L, false),
                        StreamItem(url = "v1", uploaderUrl = "UC_ALLOWED_1"),
                    ),
                    WatchHistoryEntry(
                        WatchHistoryEntryMetadata("v2", 2L, false),
                        StreamItem(url = "v2", uploaderUrl = "UC_BLOCKED"),
                    ),
                )
            assertEquals(
                listOf("v1"),
                snapshot.filterWatchHistoryEntries(historyEntries).map { it.metadata.videoId },
            )

            val localPlaylistItems =
                listOf(
                    LocalPlaylistItem(videoId = "p1", uploaderUrl = "UC_ALLOWED_1"),
                    LocalPlaylistItem(videoId = "p2", uploaderUrl = "UC_BLOCKED"),
                )
            assertEquals(
                listOf("p1"),
                snapshot.filterLocalPlaylistItems(localPlaylistItems).map { it.videoId },
            )

            val bookmarks =
                listOf(
                    PlaylistBookmark(playlistId = "pl1", uploaderUrl = "UC_ALLOWED_2"),
                    PlaylistBookmark(playlistId = "pl2", uploaderUrl = "UC_BLOCKED"),
                )
            assertEquals(
                listOf("pl1"),
                snapshot.filterPlaylistBookmarks(bookmarks).map { it.playlistId },
            )

            val downloads =
                listOf(
                    DownloadWithItems(
                        Download(videoId = "d1", uploaderUrl = "UC_ALLOWED_1"),
                        emptyList(),
                    ),
                    DownloadWithItems(
                        Download(videoId = "d2", uploaderUrl = "UC_BLOCKED"),
                        emptyList(),
                    ),
                )
            assertEquals(
                listOf("d1"),
                catalog.filterDownloads(downloads).map { it.download.videoId },
            )

            enabled = false
            assertEquals(2, catalog.filterDownloads(downloads).size)
            assertEquals(2, catalog.snapshot().filterWatchHistoryEntries(historyEntries).size)
        }

    @Test
    fun `UI capability predicates reflect Allowlist, Music Removal, and UI preference flags`() {
        var allowlistEnabled = false
        var musicRemovalEnabled = false
        val catalog =
            testCatalog(
                isAllowlistEnabled = { allowlistEnabled },
                isMusicRemovalEnabled = { musicRemovalEnabled },
            )

        // Allowlist off, Music Removal off
        assertTrue(catalog.canModifySubscriptions())
        assertTrue(catalog.canViewComments())
        assertTrue(catalog.canViewRelatedStreams(relatedStreamsEnabled = true))
        assertFalse(catalog.canViewRelatedStreams(relatedStreamsEnabled = false))
        assertTrue(catalog.areSearchSuggestionsEnabled(searchSuggestionsEnabled = true))
        assertFalse(catalog.areSearchSuggestionsEnabled(searchSuggestionsEnabled = false))
        assertFalse(catalog.shouldHideMusicSearchChips())

        // Allowlist off, Music Removal on
        musicRemovalEnabled = true
        assertTrue(catalog.shouldHideMusicSearchChips())

        // Allowlist on, Music Removal off
        allowlistEnabled = true
        musicRemovalEnabled = false
        assertFalse(catalog.canModifySubscriptions())
        assertFalse(catalog.canViewComments())
        assertFalse(catalog.canViewRelatedStreams(relatedStreamsEnabled = true))
        assertFalse(catalog.canViewRelatedStreams(relatedStreamsEnabled = false))
        assertTrue(catalog.areSearchSuggestionsEnabled(searchSuggestionsEnabled = true))
        assertTrue(catalog.areSearchSuggestionsEnabled(searchSuggestionsEnabled = false))
        assertTrue(catalog.shouldHideMusicSearchChips())

        // Allowlist on, Music Removal on
        musicRemovalEnabled = true
        assertTrue(catalog.shouldHideMusicSearchChips())
    }
}
