package com.github.libretube.repo

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.WatchPosition
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PlayerHelper

/**
 * The Episodes view: from the (already unwatched-only) feed, videos that can be listened to like
 * podcast episodes, newest upload first. Unknown upload dates go last and keep their Series order.
 * With [seriesOnly], only videos found in a followed Series.
 */
fun selectEpisodes(unwatchedFeed: List<StreamItem>, seriesOnly: Boolean): List<StreamItem> =
    unwatchedFeed
        .filter { !it.isLive && !it.isUpcoming && !it.isShort }
        .filter { !seriesOnly || it.seriesId != null }
        .sortedWith(compareBy<StreamItem> { it.uploaded <= 0L }.thenByDescending { it.uploaded })

/** What marking a video watched changed, so it can be undone. */
data class WatchStateSnapshot(
    val videoId: String,
    val positionMillis: Long?,
    val inHistory: Boolean,
)

/** Watch position and history access for marking a video watched from the Episodes view. */
interface WatchStateStore {
    suspend fun position(videoId: String): Long?

    suspend fun inHistory(videoId: String): Boolean

    /** Marks [item] watched the same way the video options sheet does. */
    suspend fun markWatched(item: StreamItem)

    suspend fun restorePosition(videoId: String, positionMillis: Long?)

    suspend fun removeFromHistory(videoId: String)
}

/** Marks [item] watched and returns what it replaced, for [undoMarkWatched]. */
suspend fun WatchStateStore.markWatchedWithUndo(
    item: StreamItem,
    videoId: String,
): WatchStateSnapshot {
    val snapshot = WatchStateSnapshot(videoId, position(videoId), inHistory(videoId))
    markWatched(item)
    return snapshot
}

/** Puts back the watch state [snapshot] recorded before marking the video watched. */
suspend fun WatchStateStore.undoMarkWatched(snapshot: WatchStateSnapshot) {
    if (!snapshot.inHistory) removeFromHistory(snapshot.videoId)
    restorePosition(snapshot.videoId, snapshot.positionMillis)
}

internal object RoomWatchStateStore : WatchStateStore {
    private val repository: UserDataRepository
        @Suppress("DEPRECATION") get() = UserDataRepositoryHelper.userDataRepository

    override suspend fun position(videoId: String): Long? =
        DatabaseHolder.Database.watchPositionDao().findById(videoId)?.position

    override suspend fun inHistory(videoId: String): Boolean =
        DatabaseHolder.Database.watchHistoryDao().findById(videoId) != null

    override suspend fun markWatched(item: StreamItem) {
        val videoId = item.url.orEmpty().toID()
        if (PlayerHelper.watchHistoryEnabled) {
            repository.addToWatchHistory(item.toWatchHistoryEntry(Long.MAX_VALUE))
        }
        repository.updateWatchHistoryEntry(
            WatchHistoryEntryMetadata(
                videoId = videoId,
                addedDate = -1,
                finished = true,
                positionMillis = Long.MAX_VALUE,
            ),
        )
    }

    override suspend fun restorePosition(videoId: String, positionMillis: Long?) {
        val dao = DatabaseHolder.Database.watchPositionDao()
        if (positionMillis == null) {
            dao.deleteByVideoId(videoId)
        } else {
            dao.insert(WatchPosition(videoId = videoId, position = positionMillis))
        }
    }

    override suspend fun removeFromHistory(videoId: String) =
        DatabaseHolder.Database.watchHistoryDao().deleteByVideoId(videoId)
}
