package com.github.libretube.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.github.libretube.api.JsonHelper
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.SeriesOrder
import com.github.libretube.db.obj.SeriesVideo
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.obj.BackupFile
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SeriesDatabaseTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    /** Creates a database file exactly as schema [version] describes it. */
    private fun createDatabaseAtVersion(
        name: String,
        version: Int,
        seed: SQLiteDatabase.() -> Unit,
    ) {
        val schema =
            Json.parseToJsonElement(
                    File("schemas/com.github.libretube.db.AppDatabase/$version.json").readText(),
                )
                .jsonObject
                .getValue("database")
                .jsonObject
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val obj = entity.jsonObject
                val table = obj.getValue("tableName").jsonPrimitive.content
                db.execSQL(
                    obj.getValue("createSql")
                        .jsonPrimitive
                        .content
                        .replace("\${TABLE_NAME}", table),
                )
                obj["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(
                        index.jsonObject
                            .getValue("createSql")
                            .jsonPrimitive
                            .content
                            .replace("\${TABLE_NAME}", table),
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach {
                db.execSQL(it.jsonPrimitive.content)
            }
            db.seed()
            db.version = version
        }
    }

    @Test
    fun `bookmarks from version 25 come through as Series with every field kept`() = runBlocking {
        val name = "migration-test.db"
        createDatabaseAtVersion(name, 25) {
            execSQL(
                "INSERT INTO playlistBookmark (playlistId, playlistName, thumbnailUrl, uploader, " +
                    "uploaderUrl, uploaderAvatar, videos) VALUES ('PL1', 'Tafsir', 'thumb', " +
                    "'Teacher', '/channel/UC1', 'avatar', 42)",
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val series = db.seriesDao().getAll().single()
            assertEquals(
                Series(
                    playlistId = "PL1",
                    playlistName = "Tafsir",
                    thumbnailUrl = "thumb",
                    uploader = "Teacher",
                    uploaderUrl = "/channel/UC1",
                    uploaderAvatar = "avatar",
                    videos = 42,
                ),
                series,
            )
            assertEquals(SeriesOrder.PLAYLIST, series.order)
            assertFalse(series.hasBaseline)
            assertTrue(db.seriesVideoDao().getAll().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `Series videos survive feed pruning and are removed when the Series is unfollowed`() =
        runBlocking {
            val db =
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            try {
                val dao = db.seriesDao()
                dao.insert(Series(playlistId = "PL1", uploaderUrl = "UC_UNSUBSCRIBED"))
                db.seriesVideoDao()
                    .insertAll(
                        listOf(
                            SeriesVideo(
                                seriesId = "PL1",
                                videoId = "old",
                                uploaderUrl = "UC_UNSUBSCRIBED",
                                uploaded = 1_000L,
                            ),
                        ),
                    )
                db.feedDao()
                    .insertAll(
                        listOf(
                            SubscriptionsFeedItem(
                                videoId = "old",
                                uploaderUrl = "UC_UNSUBSCRIBED",
                                uploaderVerified = false,
                                uploaded = 1_000L,
                            ),
                        ),
                    )

                db.feedDao().cleanUpOlderThan(olderThan = 2_000_000L)
                db.feedDao().deleteAllExcept(listOf("UC_SUBSCRIBED"))

                assertTrue(db.feedDao().getAll().isEmpty())
                assertEquals(listOf("old"), db.seriesVideoDao().getAll().map { it.videoId })

                // a video found again keeps when it was first found
                db.seriesVideoDao()
                    .insertAll(
                        listOf(SeriesVideo(seriesId = "PL1", videoId = "old", foundAt = 9)),
                    )
                assertEquals(0L, db.seriesVideoDao().getAll().single().foundAt)

                dao.deleteById("PL1")
                assertTrue(db.seriesVideoDao().getAll().isEmpty())
                assertTrue(dao.getAll().isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun `backups keep Series state and old bookmark-only backups restore as Series`() {
        val backup =
            BackupFile(
                playlistBookmarks =
                    listOf(
                        Series(
                            playlistId = "PL1",
                            seriesOrder = SeriesOrder.OLDEST_FIRST.name,
                            checkedVideoCount = 7,
                            lastOpened = 99,
                        ),
                    ),
                seriesVideos = listOf(SeriesVideo(seriesId = "PL1", videoId = "v1", foundAt = 5)),
            )
        val decoded =
            JsonHelper.json.decodeFromString<BackupFile>(JsonHelper.json.encodeToString(backup))
        assertEquals(backup.playlistBookmarks, decoded.playlistBookmarks)
        assertEquals(backup.seriesVideos, decoded.seriesVideos)

        val old =
            JsonHelper.json.decodeFromString<BackupFile>(
                """{"playlistBookmarks":[{"playlistId":"PL2","playlistName":"Old","videos":3}]}""",
            )
        val restored = old.playlistBookmarks.orEmpty().single()
        assertEquals("PL2", restored.playlistId)
        assertFalse(restored.hasBaseline)
        assertEquals(SeriesOrder.PLAYLIST, restored.order)
        assertTrue(old.seriesVideos.orEmpty().isEmpty())
    }
}
