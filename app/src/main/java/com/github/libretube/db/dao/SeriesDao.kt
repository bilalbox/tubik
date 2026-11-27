package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.github.libretube.db.obj.Series
import com.github.libretube.db.obj.SeriesVideo

/** Series (followed playlists), stored in the table that used to hold playlist bookmarks. */
@Dao
interface SeriesDao {
    @Query("SELECT * FROM playlistBookmark") suspend fun getAll(): List<Series>

    @Query("SELECT * FROM playlistBookmark WHERE playlistId = :playlistId LIMIT 1")
    suspend fun findById(playlistId: String): Series?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(series: Series)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(series: List<Series>)

    @Update suspend fun update(series: Series)

    @Query("DELETE FROM playlistBookmark WHERE playlistId = :playlistId")
    suspend fun deleteRow(playlistId: String)

    @Query("DELETE FROM seriesVideo WHERE seriesId = :seriesId")
    suspend fun deleteVideosOf(seriesId: String)

    /** Unfollows a Series, removing its videos with it. */
    @Transaction
    suspend fun deleteById(playlistId: String) {
        deleteVideosOf(playlistId)
        deleteRow(playlistId)
    }

    @Query("UPDATE playlistBookmark SET checkedVideoCount = :count WHERE playlistId = :seriesId")
    suspend fun setCheckedVideoCount(seriesId: String, count: Int)

    @Query("UPDATE playlistBookmark SET lastOpened = :millis WHERE playlistId = :seriesId")
    suspend fun setLastOpened(seriesId: String, millis: Long)
}

/** Videos found in Series; never pruned by the feed cache. */
@Dao
interface SeriesVideoDao {
    @Query("SELECT * FROM seriesVideo") suspend fun getAll(): List<SeriesVideo>

    @Query("SELECT videoId FROM seriesVideo WHERE seriesId = :seriesId")
    suspend fun getVideoIds(seriesId: String): List<String>

    /** Keeps an existing video's [SeriesVideo.foundAt] when it is found again. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(videos: List<SeriesVideo>)
}
