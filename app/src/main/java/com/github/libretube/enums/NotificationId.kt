package com.github.libretube.enums

private const val AppUpdateNotificationId = 4

enum class NotificationId(val id: Int) {
    PLAYER_PLAYBACK(1),
    DOWNLOAD_IN_PROGRESS(2),
    ENQUEUE_PLAYLIST_DOWNLOAD(3),
    APP_UPDATE(AppUpdateNotificationId),
}
