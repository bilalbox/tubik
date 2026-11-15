package com.github.libretube.api.obj

import android.os.Parcelable
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import kotlin.io.path.Path
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class PipedStream(
    var url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val videoOnly: Boolean? = null,
    val bitrate: Int? = null,
    val initStart: Int? = null,
    val initEnd: Int? = null,
    val indexStart: Int? = null,
    val indexEnd: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val durationMs: Long? = null,
    val audioTrackName: String? = null,
    val audioTrackId: String? = null,
    val contentLength: Long = -1,
    val audioTrackType: String? = null,
    val audioTrackLocale: String? = null,
    val itag: Int? = null,
    val lastModified: Long? = null,
    val isDrc: Boolean? = null,
    val xtags: String? = null,
) : Parcelable {
    private fun getQualityString(videoId: String): String {
        val extension =
            when {
                mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
                mimeType?.contains("mp4", ignoreCase = true) == true ||
                    format?.contains("mp4", ignoreCase = true) == true -> "mp4"
                mimeType?.contains("m4a", ignoreCase = true) == true ||
                    format?.contains("m4a", ignoreCase = true) == true -> "m4a"
                mimeType?.contains("opus", ignoreCase = true) == true -> "opus"
                !mimeType.isNullOrBlank() ->
                    mimeType.substringAfter("/").substringBefore(";").trim()
                !format.isNullOrBlank() -> format.lowercase()
                else -> "bin"
            }
        val safeQuality = quality?.replace(Regex("[^a-zA-Z0-9._-]"), "_") ?: "unknown"
        val safeFormat = format?.replace(Regex("[^a-zA-Z0-9._-]"), "_") ?: "unknown"
        return "${videoId}_${safeQuality}_${safeFormat}.${extension}"
    }

    fun toDownloadItem(fileType: FileType, videoId: String) =
        DownloadItem(
            type = fileType,
            videoId = videoId,
            fileName = getQualityString(videoId),
            path = Path(""),
            format = format,
            quality = quality,
            language = audioTrackLocale,
            downloadSize = contentLength,
        )
}
