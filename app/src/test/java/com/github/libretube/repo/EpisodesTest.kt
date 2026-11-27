package com.github.libretube.repo

import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.SeriesVideo
import com.github.libretube.ui.adapters.episodeSubtitle
import com.github.libretube.ui.adapters.formatEpisodeDuration
import com.github.libretube.util.AccessControl
import com.github.libretube.util.InMemoryAccessControlStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodesTest {
    private fun episode(
        id: String,
        uploaded: Long,
        seriesId: String? = null,
        duration: Long = 600,
    ) = StreamItem(url = id, uploaded = uploaded, duration = duration, seriesId = seriesId)

    @Test
    fun `Episodes are newest upload first, unknown dates last in Series order`() {
        val feed =
            listOf(
                episode("old_upload", uploaded = 100),
                episode("audiobook_1", uploaded = -1, seriesId = "PL"),
                episode("new_upload", uploaded = 300),
                episode("audiobook_2", uploaded = -1, seriesId = "PL"),
                episode("lecture", uploaded = 200, seriesId = "PL2"),
                episode("live", uploaded = 400, duration = 0),
                StreamItem(url = "short", uploaded = 500, duration = 30, isShort = true),
            )

        assertEquals(
            listOf("new_upload", "lecture", "old_upload", "audiobook_1", "audiobook_2"),
            selectEpisodes(feed, seriesOnly = false).map { it.url },
        )
        assertEquals(
            listOf("lecture", "audiobook_1", "audiobook_2"),
            selectEpisodes(feed, seriesOnly = true).map { it.url },
        )
    }

    @Test
    fun `episode rows show the Series name, or the channel, with the duration`() {
        assertEquals(
            "Tafsir · 48:12",
            episodeSubtitle(
                StreamItem(seriesName = "Tafsir", uploaderName = "Ch", duration = 2892),
            ),
        )
        assertEquals("Channel", episodeSubtitle(StreamItem(uploaderName = "Channel")))
        assertEquals("1:02:03", formatEpisodeDuration(3723))
        assertEquals("0:05", formatEpisodeDuration(5))
    }

    private class FakeWatchState : WatchStateStore {
        val positions = mutableMapOf<String, Long>()
        val history = mutableSetOf<String>()

        override suspend fun position(videoId: String) = positions[videoId]

        override suspend fun inHistory(videoId: String) = videoId in history

        override suspend fun markWatched(item: StreamItem) {
            history += item.url!!
            positions[item.url!!] = Long.MAX_VALUE
        }

        override suspend fun restorePosition(videoId: String, positionMillis: Long?) {
            if (positionMillis == null) positions.remove(videoId)
            else positions[videoId] = positionMillis
        }

        override suspend fun removeFromHistory(videoId: String) {
            history -= videoId
        }
    }

    @Test
    fun `swiping marks an episode watched and Undo restores its previous state`() = runBlocking {
        val store = FakeWatchState()
        store.positions["half"] = 30_000
        store.history += "half"

        val halfSnapshot = store.markWatchedWithUndo(StreamItem(url = "half"), "half")
        val newSnapshot = store.markWatchedWithUndo(StreamItem(url = "new"), "new")
        assertEquals(Long.MAX_VALUE, store.positions["half"])
        assertTrue("new" in store.history)

        store.undoMarkWatched(halfSnapshot)
        store.undoMarkWatched(newSnapshot)
        assertEquals(30_000L, store.positions["half"])
        assertTrue("half" in store.history)
        assertNull(store.positions["new"])
        assertFalse("new" in store.history)
    }

    private val summarySeries =
        listOf(
            Series(
                playlistId = "PL",
                uploaderUrl = "UC_OK",
                checkedVideoCount = 3,
                lastOpened = 50,
            ),
            Series(playlistId = "HIDDEN", uploaderUrl = "UC_BLOCKED", checkedVideoCount = 1),
        )
    private val summaryVideos =
        listOf(
            SeriesVideo(seriesId = "PL", videoId = "seen", uploaderUrl = "UC_OK"),
            SeriesVideo(seriesId = "PL", videoId = "baseline", uploaderUrl = "UC_OK"),
            SeriesVideo(
                seriesId = "PL",
                videoId = "found_later",
                uploaderUrl = "UC_OK",
                foundAt = 80,
            ),
            SeriesVideo(
                seriesId = "PL",
                videoId = "guest",
                uploaderUrl = "UC_BLOCKED",
                foundAt = 90,
            ),
        )

    private class ReadOnlyStore(val series: List<Series>, val videos: List<SeriesVideo>) :
        SeriesStore {
        override suspend fun getAll() = series

        override suspend fun get(seriesId: String) = null

        override suspend fun insert(series: Series) = Unit

        override suspend fun update(series: Series) = Unit

        override suspend fun delete(seriesId: String) = Unit

        override suspend fun getAllVideos() = videos

        override suspend fun knownVideoIds(seriesId: String) = emptySet<String>()

        override suspend fun addVideos(videos: List<SeriesVideo>) = Unit

        override suspend fun setCheckedVideoCount(seriesId: String, count: Int) = Unit

        override suspend fun setLastOpened(seriesId: String, millis: Long) = Unit
    }

    private object NoPlaylists : SeriesPlaylistSource {
        override suspend fun getPlaylist(playlistId: String) = Playlist()

        override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String) = Playlist()
    }

    private suspend fun summariesFor(series: List<Series>) =
        SeriesRepository(
                store = ReadOnlyStore(series, summaryVideos),
                playlistSource = NoPlaylists,
                accessControl =
                    AccessControl(
                        InMemoryAccessControlStorage(allowlistEnabled = true),
                        getAllowedChannelIds = { setOf("UC_OK") },
                    ),
            )
            .summaries { videoId, _ -> videoId == "seen" }

    @Test
    fun `Library summaries count unwatched videos and flag ones found since last opened`() =
        runBlocking {
            val summaries = summariesFor(summarySeries)

            assertEquals(setOf("PL"), summaries.keys)
            assertEquals(SeriesSummary(unwatched = 2, hasNewEpisode = true), summaries["PL"])

            val opened = summariesFor(listOf(summarySeries[0].copy(lastOpened = 100)))
            assertEquals(SeriesSummary(unwatched = 2, hasNewEpisode = false), opened["PL"])
        }
}
