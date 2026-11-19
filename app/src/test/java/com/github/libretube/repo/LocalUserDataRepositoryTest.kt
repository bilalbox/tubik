package com.github.libretube.repo

import com.github.libretube.db.dao.WatchHistoryRow
import com.github.libretube.db.obj.LocalPlaylist
import com.github.libretube.db.obj.LocalPlaylistItem
import com.github.libretube.db.obj.LocalPlaylistWithVideos
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.enums.WatchHistoryStatus
import com.github.libretube.util.AccessControl
import com.github.libretube.util.InMemoryAccessControlStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalUserDataRepositoryTest {

    private class FakeLocalUserDataSource(
        var playlists: List<LocalPlaylistWithVideos> = emptyList(),
        var watchHistoryRows: List<WatchHistoryRow> = emptyList(),
        var bookmarks: List<PlaylistBookmark> = emptyList(),
    ) : LocalUserDataSource {
        override suspend fun getLocalPlaylists(): List<LocalPlaylistWithVideos> = playlists

        override suspend fun getWatchHistoryPage(
            limit: Int,
            cursor: Long,
            watched: Boolean?,
            absoluteWatchedThresholdSeconds: Float,
            relativeWatchedThreshold: Float,
        ): List<WatchHistoryRow> =
            watchHistoryRows
                .filter { it.rowId <= cursor }
                .sortedByDescending { it.rowId }
                .take(limit)

        override suspend fun getPlaylistBookmarks(): List<PlaylistBookmark> = bookmarks
    }

    private fun samplePlaylist(
        id: Int,
        name: String,
        vararg uploaderUrls: String,
    ): LocalPlaylistWithVideos =
        LocalPlaylistWithVideos(
            playlist = LocalPlaylist(name = name, thumbnailUrl = "thumb").apply { this.id = id },
            videos =
                uploaderUrls.mapIndexed { idx, uploaderUrl ->
                    LocalPlaylistItem(
                        playlistId = id,
                        videoId = "v_${id}_$idx",
                        uploaderUrl = uploaderUrl,
                    )
                },
        )

    private fun accessControl(
        isAllowlistEnabled: () -> Boolean,
        onSnapshot: () -> Unit = {},
    ): AccessControl {
        val storage = InMemoryAccessControlStorage()
        return AccessControl(
            storage =
                object : com.github.libretube.util.AccessControlStorage by storage {
                    override var allowlistEnabled: Boolean
                        get() = isAllowlistEnabled()
                        set(value) {
                            storage.allowlistEnabled = value
                        }
                },
            getAllowedChannelIds = {
                onSnapshot()
                setOf("UC_ALLOWED_1")
            },
        )
    }

    @Test
    fun `getPlaylist and getPlaylists resolve fresh AccessControl snapshot and recount videos`() =
        runBlocking {
            var allowlistEnabled = true
            var snapshotCalls = 0
            val catalog =
                accessControl(isAllowlistEnabled = { allowlistEnabled }) { snapshotCalls++ }
            val dataSource =
                FakeLocalUserDataSource(
                    playlists =
                        listOf(
                            samplePlaylist(
                                1,
                                "Kids Mix",
                                "https://www.youtube.com/channel/UC_ALLOWED_1",
                                "https://www.youtube.com/channel/UC_BLOCKED",
                            ),
                            samplePlaylist(2, "Second Mix", "UC_BLOCKED"),
                        ),
                )
            val repo =
                LocalUserDataRepository(accessControl = catalog, localDataSource = dataSource)

            val playlist = repo.getPlaylist("1")
            assertEquals(1, playlist.videos)
            assertEquals(listOf("v_1_0"), playlist.relatedStreams.map { it.url })

            val beforeSummaryCalls = snapshotCalls
            val summaries = repo.getPlaylists()
            assertEquals(beforeSummaryCalls + 1, snapshotCalls)
            assertEquals(listOf(1L, 0L), summaries.map { it.videos })

            allowlistEnabled = false
            assertEquals(2, repo.getPlaylist("1").videos)
            assertEquals(listOf(2L, 1L), repo.getPlaylists().map { it.videos })
        }

    @Test
    fun `getWatchHistory and getPlaylistBookmarks filter by AccessControl while preserving history cursor`() =
        runBlocking {
            var allowlistEnabled = true
            val catalog = accessControl(isAllowlistEnabled = { allowlistEnabled })
            val dataSource =
                FakeLocalUserDataSource(
                    watchHistoryRows =
                        listOf(
                            WatchHistoryRow(
                                item =
                                    WatchHistoryItem(
                                        videoId = "h_allowed",
                                        uploaderUrl = "/channel/UC_ALLOWED_1",
                                    ),
                                rowId = 10L,
                                watchPosition = null,
                            ),
                            WatchHistoryRow(
                                item =
                                    WatchHistoryItem(
                                        videoId = "h_blocked",
                                        uploaderUrl = "/channel/UC_BLOCKED",
                                    ),
                                rowId = 9L,
                                watchPosition = null,
                            ),
                        ),
                    bookmarks =
                        listOf(
                            PlaylistBookmark(
                                playlistId = "pl_allowed",
                                uploaderUrl = "UC_ALLOWED_1",
                            ),
                            PlaylistBookmark(playlistId = "pl_blocked", uploaderUrl = "UC_BLOCKED"),
                        ),
                )
            val repo =
                LocalUserDataRepository(accessControl = catalog, localDataSource = dataSource)

            val (historyPage, nextCursor) = repo.getWatchHistory(2, null, WatchHistoryStatus.ALL)
            assertEquals(listOf("h_allowed"), historyPage.map { it.metadata.videoId })
            assertEquals(8L, nextCursor)

            assertEquals(listOf("pl_allowed"), repo.getPlaylistBookmarks().map { it.playlistId })

            allowlistEnabled = false
            val (unfilteredHistory, emptyNextCursor) =
                repo.getWatchHistory(5, null, WatchHistoryStatus.ALL)
            assertEquals(
                listOf("h_allowed", "h_blocked"),
                unfilteredHistory.map { it.metadata.videoId },
            )
            assertNull(emptyNextCursor)
            assertEquals(
                listOf("pl_allowed", "pl_blocked"),
                repo.getPlaylistBookmarks().map { it.playlistId },
            )
        }
}
