package com.github.libretube.repo

import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.SeriesOrder
import com.github.libretube.db.obj.SeriesVideo
import com.github.libretube.util.AccessControl
import com.github.libretube.util.InMemoryAccessControlStorage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesRepositoryTest {
    private val now = 1_800_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private class FakeSeriesStore(series: List<Series> = emptyList()) : SeriesStore {
        val series = series.associateBy { it.playlistId }.toMutableMap()
        val videos = mutableListOf<SeriesVideo>()

        override suspend fun getAll() = series.values.toList()

        override suspend fun get(seriesId: String) = series[seriesId]

        override suspend fun insert(series: Series) {
            this.series[series.playlistId] = series
        }

        override suspend fun update(series: Series) {
            this.series[series.playlistId] = series
        }

        override suspend fun delete(seriesId: String) {
            series.remove(seriesId)
            videos.removeAll { it.seriesId == seriesId }
        }

        override suspend fun getAllVideos() = videos.toList()

        override suspend fun knownVideoIds(seriesId: String) =
            videos.filter { it.seriesId == seriesId }.map { it.videoId }.toSet()

        override suspend fun addVideos(videos: List<SeriesVideo>) {
            this.videos += videos
        }

        override suspend fun setCheckedVideoCount(seriesId: String, count: Int) {
            series[seriesId]?.let { series[seriesId] = it.copy(checkedVideoCount = count) }
        }

        override suspend fun setLastOpened(seriesId: String, millis: Long) {
            series[seriesId]?.let { series[seriesId] = it.copy(lastOpened = millis) }
        }
    }

    /** Serves playlists in pages of [pageSize]; counts requests and concurrent requests. */
    private class FakePlaylistSource(
        private val pageSize: Int = 2,
        private val requestDelayMillis: Long = 0,
        val owners: MutableMap<String, String> = mutableMapOf(),
    ) : SeriesPlaylistSource {
        val playlists = mutableMapOf<String, List<StreamItem>>()
        val failing = mutableSetOf<String>()
        val requests = AtomicInteger()
        private val inFlight = AtomicInteger()
        var maxInFlight = 0
            private set

        private suspend fun page(playlistId: String, index: Int): Playlist {
            requests.incrementAndGet()
            val current = inFlight.incrementAndGet()
            synchronized(this) { maxInFlight = maxOf(maxInFlight, current) }
            try {
                if (requestDelayMillis > 0) delay(requestDelayMillis)
                if (playlistId in failing) error("playlist $playlistId failed")
                val all = playlists.getValue(playlistId)
                val chunk = all.drop(index * pageSize).take(pageSize)
                val hasMore = (index + 1) * pageSize < all.size
                return Playlist(
                    name = "Series $playlistId",
                    uploaderUrl = owners[playlistId],
                    videos = all.size,
                    relatedStreams = chunk,
                    nextpage = if (hasMore) (index + 1).toString() else null,
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun getPlaylist(playlistId: String) = page(playlistId, 0)

        override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String) =
            page(playlistId, nextPage.toInt())
    }

    private fun video(id: String, owner: String = "UC_ALLOWED", uploaded: Long = 0) =
        StreamItem(url = "/watch?v=$id", uploaderUrl = "/channel/$owner", uploaded = uploaded)

    private fun videos(vararg ids: String) = ids.map { video(it) }

    private fun accessControl(allowlist: Boolean = true) =
        AccessControl(
            storage = InMemoryAccessControlStorage(allowlistEnabled = allowlist),
            getAllowedChannelIds = { setOf("UC_ALLOWED") },
        )

    private fun series(id: String, count: Int = Series.NO_BASELINE, owner: String = "UC_ALLOWED") =
        Series(
            playlistId = id,
            playlistName = "Series $id",
            uploaderUrl = owner,
            checkedVideoCount = count,
        )

    @Test
    fun `first check stores every video without marking any new`() = runBlocking {
        val store = FakeSeriesStore(listOf(series("pl")))
        val source =
            FakePlaylistSource().apply { playlists["pl"] = videos("a", "b", "c", "d", "e") }

        SeriesRefresher(store, source, clock).refreshOne(store.series.getValue("pl"))

        assertEquals(listOf("a", "b", "c", "d", "e"), store.videos.map { it.videoId })
        assertEquals(listOf(0, 1, 2, 3, 4), store.videos.map { it.position })
        assertTrue(store.videos.all { it.foundAt == 0L })
        assertEquals(5, store.series.getValue("pl").checkedVideoCount)
    }

    @Test
    fun `an unchanged video count costs exactly one request and adds nothing`() = runBlocking {
        val store = FakeSeriesStore(listOf(series("pl", count = 5)))
        val source =
            FakePlaylistSource().apply { playlists["pl"] = videos("a", "b", "c", "d", "e") }

        SeriesRefresher(store, source, clock).refreshOne(store.series.getValue("pl"))

        assertEquals(1, source.requests.get())
        assertTrue(store.videos.isEmpty())
    }

    @Test
    fun `an upgraded bookmark with an unchanged count still records a baseline`() = runBlocking {
        // a bookmark from before Series: `videos` is already right, but no baseline exists
        val store = FakeSeriesStore(listOf(series("pl").copy(videos = 3)))
        val source = FakePlaylistSource().apply { playlists["pl"] = videos("a", "b", "c") }

        SeriesRefresher(store, source, clock).refreshOne(store.series.getValue("pl"))

        assertEquals(setOf("a", "b", "c"), store.videos.map { it.videoId }.toSet())
        assertTrue(store.series.getValue("pl").hasBaseline)
    }

    @Test
    fun `videos added at the end of a long playlist are found as new`() = runBlocking {
        val store = FakeSeriesStore(listOf(series("pl")))
        val source =
            FakePlaylistSource().apply { playlists["pl"] = videos("a", "b", "c", "d", "e") }
        val refresher = SeriesRefresher(store, source, clock)
        refresher.refreshOne(store.series.getValue("pl"))

        source.playlists["pl"] = videos("a", "b", "c", "d", "e", "f")
        refresher.refreshOne(store.series.getValue("pl"))

        val added = store.videos.single { it.videoId == "f" }
        assertEquals(now, added.foundAt)
        assertEquals(5, added.position)
        assertEquals(6, store.videos.size)
    }

    @Test
    fun `pages beyond the limit are not read`() = runBlocking {
        val store = FakeSeriesStore(listOf(series("pl")))
        val source =
            FakePlaylistSource(pageSize = 1).apply {
                playlists["pl"] = videos("a", "b", "c", "d", "e")
            }

        SeriesRefresher(store, source, clock, maxPages = 3).refreshOne(store.series.getValue("pl"))

        assertEquals(3, source.requests.get())
        assertEquals(listOf("a", "b", "c"), store.videos.map { it.videoId })
    }

    @Test
    fun `no more than three Series are checked at a time and one failure doesn't stop the rest`() =
        runBlocking {
            val ids = (1..8).map { "pl$it" }
            val store = FakeSeriesStore(ids.map { series(it) })
            val source =
                FakePlaylistSource(requestDelayMillis = 20).apply {
                    ids.forEach { playlists[it] = videos("${it}_a") }
                    failing += "pl3"
                }

            val failure = SeriesRefresher(store, source, clock).refresh(store.getAll())

            assertTrue(source.maxInFlight <= SeriesRefresher.MAX_CONCURRENT_CHECKS)
            assertTrue(source.maxInFlight > 1)
            assertEquals("playlist pl3 failed", failure?.message)
            assertEquals(7, store.videos.map { it.seriesId }.distinct().size)
        }

    @Test
    fun `with the Allowlist on, Follow is only offered for Allowed Channels`() = runBlocking {
        val source = FakePlaylistSource(owners = mutableMapOf("blocked" to "/channel/UC_BLOCKED"))
        source.playlists["blocked"] = videos("x")
        val store = FakeSeriesStore()
        val repo =
            SeriesRepository(
                store,
                source,
                SeriesRefresher(store, source, clock),
                accessControl(),
                clock = clock,
            )

        assertTrue(repo.canFollow("/channel/UC_ALLOWED"))
        assertFalse(repo.canFollow("/channel/UC_BLOCKED"))
        assertFalse(repo.canFollowPlaylist("blocked"))

        repo.follow(
            Playlist(name = "Blocked", uploaderUrl = "/channel/UC_BLOCKED").toSeries("blocked"),
        )
        assertNull(store.get("blocked"))

        val open = SeriesRepository(store, source, accessControl = accessControl(allowlist = false))
        assertTrue(open.canFollow("/channel/UC_BLOCKED"))
    }

    @Test
    fun `a Series whose owner isn't allowed is hidden but kept, and other channels' videos are dropped`() =
        runBlocking {
            val store =
                FakeSeriesStore(listOf(series("ok", count = 2), series("hidden", 1, "UC_BLOCKED")))
            store.videos +=
                listOf(
                    SeriesVideo(
                        seriesId = "ok",
                        videoId = "a",
                        uploaderUrl = "/channel/UC_ALLOWED",
                    ),
                    SeriesVideo(
                        seriesId = "ok",
                        videoId = "guest",
                        uploaderUrl = "/channel/UC_OTHER",
                    ),
                    SeriesVideo(
                        seriesId = "hidden",
                        videoId = "h",
                        uploaderUrl = "/channel/UC_BLOCKED",
                    ),
                )
            val repo =
                SeriesRepository(
                    store,
                    FakePlaylistSource(),
                    accessControl = accessControl(),
                    clock = clock,
                )

            assertEquals(listOf("ok"), repo.visibleSeries().map { it.playlistId })
            assertEquals(2, store.getAll().size)
            val feedVideos = repo.visibleSeriesVideos()
            assertEquals(listOf("a"), feedVideos.map { it.url })
            assertEquals("Series ok", feedVideos.single().seriesName)

            val open =
                SeriesRepository(store, FakePlaylistSource(), accessControl = accessControl(false))
            assertEquals(
                setOf("a", "guest", "h"),
                open.visibleSeriesVideos().map { it.url }.toSet(),
            )
        }

    @Test
    fun `following again starts from a fresh baseline`() = runBlocking {
        val store = FakeSeriesStore()
        val source = FakePlaylistSource().apply { playlists["pl"] = videos("a", "b") }
        val repo = SeriesRepository(store, source, accessControl = accessControl(), clock = clock)
        repo.follow(series("pl", count = 2))

        assertEquals(setOf("a", "b"), store.videos.map { it.videoId }.toSet())
        assertTrue(store.videos.all { it.foundAt == 0L })
        repo.store.delete("pl")
        assertTrue(store.videos.isEmpty())
        assertNull(store.get("pl"))
    }

    @Test
    fun `refresh runs when forced, once a day, or when a Series has no baseline`() = runBlocking {
        val store = FakeSeriesStore(listOf(series("pl", count = 1)))
        val source = FakePlaylistSource().apply { playlists["pl"] = videos("a") }
        val prefs =
            object : SeriesRefreshPreferences {
                override var lastSeriesRefreshMillis = now - 1000
            }
        val repo =
            SeriesRepository(
                store,
                source,
                accessControl = accessControl(),
                preferences = prefs,
                clock = clock,
            )

        repo.refreshIfDue(force = false)
        assertEquals(0, source.requests.get())
        repo.refreshIfDue(force = true)
        assertEquals(1, source.requests.get())

        store.insert(series("new"))
        source.playlists["new"] = videos("n")
        repo.refreshIfDue(force = false)
        assertTrue(store.knownVideoIds("new").contains("n"))
    }

    @Test
    fun `a video both uploaded by a channel and in a Series appears once with its Series`() {
        val channelFeed = listOf(video("a"), video("b"), video("c", owner = "UC_SERIES_ONLY"))
        val seriesVideos =
            listOf(video("b").copy(seriesId = "pl", seriesName = "Lectures"), video("d"))

        val merged = mergeSeriesIntoFeed(channelFeed, seriesVideos)
        assertEquals(listOf("a", "c", "b", "d"), merged.map { it.url!!.substringAfter("=") })
        assertEquals("Lectures", merged.single { it.url!!.endsWith("b") }.seriesName)

        val scoped = mergeSeriesIntoFeed(channelFeed, seriesVideos, setOf("UC_SERIES_ONLY"))
        assertEquals(listOf("a", "b", "d"), scoped.map { it.url!!.substringAfter("=") })
    }

    @Test
    fun `Series order sorts by upload date with unknown dates last`() {
        val items =
            listOf(
                video("x", uploaded = 0),
                video("old", uploaded = 10),
                video("new", uploaded = 20),
            )
        fun ids(order: SeriesOrder) =
            items.sortedBySeriesOrder(order) { it.uploaded }.map { it.url!!.substringAfter("=") }

        assertEquals(listOf("x", "old", "new"), ids(SeriesOrder.PLAYLIST))
        assertEquals(listOf("new", "old", "x"), ids(SeriesOrder.NEWEST_FIRST))
        assertEquals(listOf("old", "new", "x"), ids(SeriesOrder.OLDEST_FIRST))
    }

    @Test
    fun `playing a Series continues with the next unwatched video`() {
        val ordered = videos("1", "2", "3", "4")
        val watched = setOf("/watch?v=1", "/watch?v=3")
        fun ids(start: StreamItem?) =
            seriesQueue(ordered, start) { it.url in watched }.map { it.url!!.substringAfter("=") }

        assertEquals(listOf("2", "4"), ids(start = null))
        assertEquals(listOf("1", "2", "4"), ids(start = ordered[0]))
        assertEquals(listOf("3", "4"), ids(start = ordered[2]))
        assertEquals(
            listOf("1", "2"),
            seriesQueue(videos("1", "2"), null) { true }.map { it.url!!.substringAfter("=") },
        )
    }

    @Test
    fun `Series feed keeps Series videos when the channel feed fails and passes other refreshes`() =
        runBlocking {
            val store = FakeSeriesStore(listOf(series("pl", count = 1)))
            store.videos +=
                SeriesVideo(seriesId = "pl", videoId = "s", uploaderUrl = "/channel/UC_ALLOWED")
            val repo =
                SeriesRepository(
                    store,
                    FakePlaylistSource(),
                    accessControl = accessControl(),
                    clock = clock,
                )
            val failing =
                object : FeedRepository {
                    override suspend fun getFeed(
                        refresh: FeedRefresh,
                        onProgressUpdate: (FeedProgress) -> Unit,
                    ): List<StreamItem> = error("offline")
                }

            val feed =
                SeriesAwareFeedRepository(failing, repo).getFeed(FeedRefresh.OnlySome(emptyList()))
            assertEquals(listOf("s"), feed.map { it.url })
        }
}
