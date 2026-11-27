package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.Index
import com.github.libretube.api.obj.StreamItem
import kotlinx.serialization.Serializable

/**
 * A video found in a [Series]. Kept for as long as the Series is followed: the feed cache's age and
 * unsubscribed-channel pruning never applies to it.
 *
 * @param position the video's index in the playlist when it was found
 * @param foundAt when the video was found, in epoch millis; 0 for the videos recorded by the
 *   Series' first check, which never count as new
 */
@Serializable
@Entity(
    tableName = "seriesVideo",
    primaryKeys = ["seriesId", "videoId"],
    indices = [Index("videoId")],
)
data class SeriesVideo(
    val seriesId: String,
    val videoId: String,
    val position: Int = 0,
    val title: String? = null,
    val thumbnail: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val duration: Long? = null,
    val uploaded: Long = -1,
    val isShort: Boolean = false,
    val foundAt: Long = 0,
) {
    fun toStreamItem(seriesName: String?) =
        StreamItem(
            url = videoId,
            type = StreamItem.TYPE_STREAM,
            title = title,
            thumbnail = thumbnail,
            uploaderName = uploaderName,
            uploaderUrl = uploaderUrl,
            uploaderAvatar = uploaderAvatar,
            duration = duration,
            uploaded = uploaded,
            isShort = isShort,
            seriesId = seriesId,
            seriesName = seriesName,
        )
}
