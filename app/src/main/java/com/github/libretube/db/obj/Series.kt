package com.github.libretube.db.obj

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A playlist the user follows (see GLOSSARY.md). Stored in the table that used to hold playlist
 * bookmarks (ADR-0003), so existing bookmarks are Series.
 */
@Serializable
@Entity(tableName = "playlistBookmark")
data class Series(
    @PrimaryKey val playlistId: String = "",
    var playlistName: String? = null,
    var thumbnailUrl: String? = null,
    var uploader: String? = null,
    var uploaderUrl: String? = null,
    var uploaderAvatar: String? = null,
    var videos: Int = 0,
    /** A [SeriesOrder] name. */
    @ColumnInfo(defaultValue = "PLAYLIST") var seriesOrder: String = SeriesOrder.PLAYLIST.name,
    /**
     * The playlist's video count at its last check, or [NO_BASELINE] before its first check has
     * recorded the videos already in it.
     */
    @ColumnInfo(defaultValue = "-1") var checkedVideoCount: Int = NO_BASELINE,
    /** When the user last opened this Series, in epoch millis; drives the new-episode dot. */
    @ColumnInfo(defaultValue = "0") var lastOpened: Long = 0,
) {
    val order: SeriesOrder
        get() = SeriesOrder.entries.firstOrNull { it.name == seriesOrder } ?: SeriesOrder.PLAYLIST

    val hasBaseline: Boolean
        get() = checkedVideoCount != NO_BASELINE

    companion object {
        const val NO_BASELINE = -1
    }
}

enum class SeriesOrder {
    PLAYLIST,
    NEWEST_FIRST,
    OLDEST_FIRST,
}
