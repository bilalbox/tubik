package com.github.libretube.repo

import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.SeriesVideo
import com.github.libretube.util.AccessControl
import com.github.libretube.util.ChannelSettingSet
import com.github.libretube.util.InMemoryAccessControlStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedScopeTest {
    private var stored = ""
    private val feedScope = ChannelSettingSet(read = { stored }, write = { stored = it })
    private val accessControl =
        AccessControl(
            storage = InMemoryAccessControlStorage(allowlistEnabled = true),
            getAllowedChannelIds = { setOf("UC_LECTURES", "UC_NEWS") },
        )

    private class Store(private val series: List<Series>, private val videos: List<SeriesVideo>) :
        SeriesStore {
        override suspend fun getAll() = series

        override suspend fun get(seriesId: String) = series.find { it.playlistId == seriesId }

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
        override suspend fun getPlaylist(playlistId: String): Playlist = error("unused")

        override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist =
            error("unused")
    }

    private fun upload(id: String, channel: String) =
        StreamItem(url = id, uploaderUrl = "/channel/$channel")

    private val channelFeed =
        listOf(
            upload("lecture_upload", "UC_LECTURES"),
            upload("tafsir_1", "UC_LECTURES"),
            upload("news", "UC_NEWS"),
        )

    private fun repository(followed: Boolean) =
        SeriesRepository(
            store =
                if (followed) {
                    Store(
                        listOf(
                            Series(
                                playlistId = "PL_TAFSIR",
                                playlistName = "Tafsir",
                                uploaderUrl = "UC_LECTURES",
                            ),
                        ),
                        listOf(
                            SeriesVideo(
                                seriesId = "PL_TAFSIR",
                                videoId = "tafsir_1",
                                uploaderUrl = "UC_LECTURES",
                            ),
                        ),
                    )
                } else {
                    Store(emptyList(), emptyList())
                },
            playlistSource = NoPlaylists,
            accessControl = accessControl,
            seriesOnlyFeedScope = feedScope,
        )

    @Test
    fun `a Series only channel contributes only its followed Series' videos`() = runBlocking {
        feedScope.set("UC_LECTURES", enabled = true)

        val feed = repository(followed = true).mergeIntoFeed(channelFeed)

        assertEquals(listOf("news", "tafsir_1"), feed.map { it.url })
        assertEquals("Tafsir", feed.single { it.url == "tafsir_1" }.seriesName)
    }

    @Test
    fun `a Series only channel without followed Series contributes nothing`() = runBlocking {
        feedScope.set("UC_LECTURES", enabled = true)

        val feed = repository(followed = false).mergeIntoFeed(channelFeed)

        assertEquals(listOf("news"), feed.map { it.url })
    }

    @Test
    fun `All uploads keeps the feed as it was`() = runBlocking {
        assertEquals(channelFeed, repository(followed = false).mergeIntoFeed(channelFeed))
    }

    @Test
    fun `Feed Scope never changes which channels are allowed`() = runBlocking {
        val before = accessControl.snapshot()
        feedScope.set("UC_LECTURES", enabled = true)
        val after = accessControl.snapshot()

        assertEquals(before, after)
        assertTrue(after.isChannelAllowed("UC_LECTURES"))
    }

    @Test
    fun `unsubscribing drops the channel's Feed Scope`() = runBlocking {
        feedScope.set("UC_LECTURES", enabled = true)
        var removed: String? = null
        val delegate =
            object : FeedRepository {
                override suspend fun getFeed(
                    refresh: FeedRefresh,
                    onProgressUpdate: (FeedProgress) -> Unit,
                ) = emptyList<StreamItem>()

                override suspend fun removeChannel(channelId: String) {
                    removed = channelId
                }
            }

        SeriesAwareFeedRepository(delegate, repository(followed = false), feedScope)
            .removeChannel("UC_LECTURES")

        assertEquals("UC_LECTURES", removed)
        assertFalse(feedScope.isEnabled("UC_LECTURES"))
    }
}
