package com.github.libretube.db

import android.app.Application
import androidx.room.Room
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.db.obj.WatchPosition
import com.github.libretube.repo.LocalUserDataRepository.Companion.ABSOLUTE_WATCHED_THRESHOLD
import com.github.libretube.repo.LocalUserDataRepository.Companion.RELATIVE_REMAINING_WATCHED_THRESHOLD
import com.github.libretube.repo.LocalUserDataRepository.Companion.RELATIVE_WATCHED_THRESHOLD
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The watch history page query repeats the watched rule in SQL; it must agree with Kotlin. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchHistoryDaoWatchedRuleTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `watched and unwatched history filters match isVideoWatched`() = runBlocking {
        val twoHours = 2 * 60 * 60L
        val tenMinutes = 10 * 60L
        // videoId to (position seconds, duration seconds)
        val cases =
            mapOf(
                "long_6min_left" to (twoHours - 6 * 60 to twoHours),
                "long_7min_left" to (twoHours - 7 * 60 to twoHours),
                "short_45s_left" to (tenMinutes - 45 to tenMinutes),
                "short_2min_left" to (tenMinutes - 2 * 60 to tenMinutes),
                "early_short" to (20L to 70L),
                "zero_duration" to (100L to 0L),
            )
        cases.forEach { (id, value) ->
            val (position, duration) = value
            db.watchHistoryDao().insert(WatchHistoryItem(videoId = id, duration = duration))
            db.watchPositionDao().insert(WatchPosition(videoId = id, position = position * 1000))
        }
        db.watchHistoryDao().insert(WatchHistoryItem(videoId = "no_position", duration = 300))

        fun page(watched: Boolean?) = runBlocking {
            db.watchHistoryDao()
                .getPage(
                    limit = 100,
                    cursor = Long.MAX_VALUE,
                    watched = watched,
                    absoluteWatchedThresholdSeconds = ABSOLUTE_WATCHED_THRESHOLD,
                    relativeWatchedThreshold = RELATIVE_WATCHED_THRESHOLD,
                    relativeRemainingWatchedThreshold = RELATIVE_REMAINING_WATCHED_THRESHOLD,
                )
                .map { it.item.videoId }
                .toSet()
        }

        val expectedWatched =
            cases
                .filter { (_, value) ->
                    DatabaseHelper.isVideoWatched(value.first * 1000, value.second)
                }
                .keys
        assertEquals(setOf("long_6min_left", "short_45s_left"), expectedWatched)
        assertEquals(expectedWatched, page(watched = true))
        assertEquals(cases.keys - expectedWatched + "no_position", page(watched = false))
        assertEquals(cases.keys + "no_position", page(watched = null))
    }
}
