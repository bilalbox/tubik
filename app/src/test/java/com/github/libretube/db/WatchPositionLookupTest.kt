package com.github.libretube.db

import android.app.Application
import androidx.room.Room
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.dao.WatchHistoryRow
import com.github.libretube.db.obj.LocalPlaylistWithVideos
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.WatchPosition
import com.github.libretube.repo.LocalUserDataRepository
import com.github.libretube.repo.LocalUserDataSource
import com.github.libretube.util.AccessControl
import com.github.libretube.util.InMemoryAccessControlStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchPositionLookupTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun openInMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun repoForDatabase(db: AppDatabase): LocalUserDataRepository {
        val dataSource =
            object : LocalUserDataSource {
                override suspend fun getLocalPlaylists(): List<LocalPlaylistWithVideos> =
                    emptyList()

                override suspend fun getWatchHistoryPage(
                    limit: Int,
                    cursor: Long,
                    watched: Boolean?,
                    absoluteWatchedThresholdSeconds: Float,
                    relativeWatchedThreshold: Float,
                    relativeRemainingWatchedThreshold: Float,
                ): List<WatchHistoryRow> = emptyList()

                override suspend fun getSeriesList(): List<Series> = emptyList()

                override suspend fun getWatchPosition(videoId: String): Long? =
                    db.watchPositionDao().findById(videoId)?.position

                override suspend fun getWatchPositions(
                    videoIds: List<String>,
                ): List<WatchPosition> = db.watchPositionDao().findByIds(videoIds)
            }
        val accessControl =
            AccessControl(
                storage = InMemoryAccessControlStorage(allowlistEnabled = false),
                getAllowedChannelIds = { emptySet() },
            )
        return LocalUserDataRepository(accessControl = accessControl, localDataSource = dataSource)
    }

    @Test
    fun `findById queries exact primary key without LIKE wildcard or watch history row`() =
        runBlocking {
            val db = openInMemoryDatabase()
            try {
                db.watchPositionDao().insert(WatchPosition(videoId = "vid_1", position = 45_000L))

                assertTrue(db.watchHistoryDao().getAll().isEmpty())
                assertNull(db.watchHistoryDao().findById("vid_1"))
                assertEquals(45_000L, db.watchPositionDao().findById("vid_1")?.position)
                assertNull(db.watchPositionDao().findById("vidA1"))

                val repo = repoForDatabase(db)
                assertEquals(45_000L, repo.getWatchPosition("vid_1"))
                assertNull(repo.getWatchPosition("vidA1"))
            } finally {
                db.close()
            }
        }

    @Test
    fun `chunked findByIds returns all matching positions above SQLite bind limit`() = runBlocking {
        val db = openInMemoryDatabase()
        try {
            val expected = (1..960).associate { "vid_$it" to it * 1_000L }
            db.watchPositionDao()
                .insertAll(expected.entries.map { (id, pos) -> WatchPosition(id, pos) })

            val repo = repoForDatabase(db)
            val queryIds = expected.keys.toList() + "missing_vid"
            val actual = repo.getWatchPositions(queryIds)

            assertEquals(expected, actual)
            assertEquals(
                mapOf("vid_1" to 1_000L, "vid_960" to 960_000L),
                db.watchPositionDao()
                    .findByIds(listOf("vid_1", "vid_960", "missing_vid"))
                    .associate { it.videoId to it.position },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `isVideoWatched and filterUnwatched work when watchHistoryItem is absent`() = runBlocking {
        val db = openInMemoryDatabase()
        try {
            db.watchPositionDao()
                .insertAll(
                    listOf(
                        WatchPosition(videoId = "watched_vid", position = 95_000L),
                        WatchPosition(videoId = "partial_vid", position = 20_000L),
                    ),
                )
            assertTrue(db.watchHistoryDao().getAll().isEmpty())

            val repo = repoForDatabase(db)
            val streams =
                listOf(
                    StreamItem(url = "/watch?v=watched_vid", duration = 100L),
                    StreamItem(url = "/watch?v=partial_vid", duration = 100L),
                    StreamItem(url = "/watch?v=unstarted_vid", duration = 100L),
                )
            val positions =
                repo.getWatchPositions(listOf("watched_vid", "partial_vid", "unstarted_vid"))

            assertTrue(DatabaseHelper.isVideoWatched(positions.getValue("watched_vid"), 100L))
            assertFalse(DatabaseHelper.isVideoWatched(positions.getValue("partial_vid"), 100L))

            val unwatched = DatabaseHelper.filterUnwatched(streams, positions)
            assertEquals(
                listOf("/watch?v=partial_vid", "/watch?v=unstarted_vid"),
                unwatched.map { it.url },
            )
        } finally {
            db.close()
        }
    }
}
