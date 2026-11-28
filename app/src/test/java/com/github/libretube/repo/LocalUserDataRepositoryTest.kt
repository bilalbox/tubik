package com.github.libretube.repo

import com.github.libretube.db.dao.WatchHistoryRow
import com.github.libretube.db.obj.LocalPlaylist
import com.github.libretube.db.obj.LocalPlaylistItem
import com.github.libretube.db.obj.LocalPlaylistWithVideos
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.db.obj.WatchPosition
import com.github.libretube.enums.WatchHistoryStatus
import com.github.libretube.util.AccessControl
import com.github.libretube.util.InMemoryAccessControlStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUserDataRepositoryTest {

    private class FakeLocalUserDataSource(
        var playlists: List<LocalPlaylistWithVideos> = emptyList(),
        var watchHistoryRows: List<WatchHistoryRow> = emptyList(),
        var bookmarks: List<Series> = emptyList(),
        var watchPositions: Map<String, Long> = emptyMap(),
    ) : LocalUserDataSource {
        val requestedWatchPositionBatches = mutableListOf<List<String>>()

        override suspend fun getLocalPlaylists(): List<LocalPlaylistWithVideos> = playlists

        override suspend fun getLocalPlaylist(playlistId: String): LocalPlaylistWithVideos? =
            playlists.firstOrNull {
                it.playlist.id.toString() == playlistId
            }

        override suspend fun removePlaylistVideo(playlistVideo: LocalPlaylistItem) {
            playlists = playlists.map { entry ->
                if (entry.playlist.id == playlistVideo.playlistId) {
                    entry.copy(
                        videos =
                            entry.videos.filter {
                                it !== playlistVideo &&
                                    (playlistVideo.id == 0 || it.id != playlistVideo.id)
                            },
                    )
                } else {
                    entry
                }
            }
        }

        override suspend fun updatePlaylist(playlist: LocalPlaylist) {
            playlists = playlists.map { entry ->
                if (entry.playlist.id == playlist.id) entry.copy(playlist = playlist) else entry
            }
        }

        override suspend fun getWatchHistoryPage(
            limit: Int,
            cursor: Long,
            watched: Boolean?,
            absoluteWatchedThresholdSeconds: Float,
            relativeWatchedThreshold: Float,
            relativeRemainingWatchedThreshold: Float,
        ): List<WatchHistoryRow> =
            watchHistoryRows
                .filter { it.rowId <= cursor }
                .sortedByDescending { it.rowId }
                .take(limit)

        override suspend fun getSeriesList(): List<Series> = bookmarks

        override suspend fun getWatchPosition(videoId: String): Long? = watchPositions[videoId]

        override suspend fun getWatchPositions(videoIds: List<String>): List<WatchPosition> {
            requestedWatchPositionBatches += videoIds
            return videoIds.mapNotNull { id -> watchPositions[id]?.let { WatchPosition(id, it) } }
        }
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
                            Series(
                                playlistId = "pl_allowed",
                                uploaderUrl = "UC_ALLOWED_1",
                            ),
                            Series(playlistId = "pl_blocked", uploaderUrl = "UC_BLOCKED"),
                        ),
                )
            val repo =
                LocalUserDataRepository(accessControl = catalog, localDataSource = dataSource)

            val (historyPage, nextCursor) = repo.getWatchHistory(2, null, WatchHistoryStatus.ALL)
            assertEquals(listOf("h_allowed"), historyPage.map { it.metadata.videoId })
            assertEquals(8L, nextCursor)

            assertEquals(listOf("pl_allowed"), repo.getSeriesList().map { it.playlistId })

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
                repo.getSeriesList().map { it.playlistId },
            )
        }

    @Test
    fun `getWatchPosition and getWatchPositions query watch positions directly and chunk batches`() =
        runBlocking {
            val positions = (1..950).associate { "v_$it" to it * 1_000L }
            val dataSource = FakeLocalUserDataSource(watchPositions = positions)
            val repo =
                LocalUserDataRepository(
                    accessControl = accessControl(isAllowlistEnabled = { false }),
                    localDataSource = dataSource,
                )

            assertEquals(42_000L, repo.getWatchPosition("v_42"))
            assertNull(repo.getWatchPosition("missing"))

            assertEquals(emptyMap<String, Long>(), repo.getWatchPositions(emptyList()))
            assertEquals(0, dataSource.requestedWatchPositionBatches.size)

            val queryIds = positions.keys.toList() + listOf("v_1", "v_42", "missing")
            val result = repo.getWatchPositions(queryIds)

            assertEquals(positions, result)
            assertEquals(listOf(900, 51), dataSource.requestedWatchPositionBatches.map { it.size })
        }

    @Test
    fun `removeFromPlaylist deletes by videoId when allowlist hides earlier items`() = runBlocking {
        val blockedItem =
            LocalPlaylistItem(
                playlistId = 1,
                videoId = "blocked_vid",
                uploaderUrl = "/channel/UC_BLOCKED",
                thumbnailUrl = "blocked_thumb",
            )
        val allowedItem =
            LocalPlaylistItem(
                playlistId = 1,
                videoId = "allowed_vid",
                uploaderUrl = "/channel/UC_ALLOWED_1",
                thumbnailUrl = "allowed_thumb",
            )
        val dataSource =
            FakeLocalUserDataSource(
                playlists =
                    listOf(
                        LocalPlaylistWithVideos(
                            playlist =
                                LocalPlaylist(
                                        name = "Kids Mix",
                                        thumbnailUrl = "allowed_thumb",
                                    )
                                    .apply { id = 1 },
                            videos = listOf(blockedItem, allowedItem),
                        ),
                    ),
            )
        val repo =
            LocalUserDataRepository(
                accessControl = accessControl(isAllowlistEnabled = { true }),
                localDataSource = dataSource,
            )

        val filtered = repo.getPlaylist("1")
        assertEquals(listOf("allowed_vid"), filtered.relatedStreams.map { it.url })

        assertTrue(repo.removeFromPlaylist("1", "allowed_vid", 0))
        assertEquals(listOf("blocked_vid"), dataSource.playlists.single().videos.map { it.videoId })
        assertEquals("blocked_thumb", dataSource.playlists.single().playlist.thumbnailUrl)
        assertFalse(repo.removeFromPlaylist("1", "missing_vid", 99))
        assertFalse(repo.removeFromPlaylist("999", "allowed_vid", 0))
    }
}
