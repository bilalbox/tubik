package com.github.libretube.extensions

import com.github.libretube.ui.dialogs.ShareDialog.Companion.YOUTUBE_FRONTEND_URL
import com.github.libretube.ui.dialogs.ShareDialog.Companion.YOUTUBE_MUSIC_URL
import com.github.libretube.ui.dialogs.ShareDialog.Companion.YOUTUBE_SHORT_URL

private val YOUTUBE_URL_PREFIXES =
    listOf(
        YOUTUBE_FRONTEND_URL,
        YOUTUBE_MUSIC_URL,
        YOUTUBE_SHORT_URL,
        "https://m.youtube.com",
        "https://youtube.com",
        "http://www.youtube.com",
        "http://m.youtube.com",
        "http://youtube.com",
    )

/** format a full YouTube url or a path to a video/channel/playlist ID */
fun String.toID(): String {
    val withoutOrigin =
        YOUTUBE_URL_PREFIXES.fold(this) { current, prefix ->
            current.removePrefix(prefix)
        }
    return withoutOrigin
        .replace("/watch?v=", "") // videos
        .replace("/shorts/", "") // shorts
        .replace("/channel/", "") // channels
        .replace("/playlist?list=", "") // playlists
        .substringBefore('&')
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
        // channel urls for different categories than the main one
        .removeSuffix("/shorts")
        .removeSuffix("/streams")
        .removeSuffix("/videos")
        .trim('/')
}

/**
 * Builds the canonical YouTube channel URL for either a channel ID (`UC...`) or a handle/custom
 * path (`@handle`, `c/...`, `user/...`).
 */
fun String.toChannelUrl(): String {
    val canonical = toID()
    return when {
        canonical.startsWith("@") || canonical.startsWith("c/") || canonical.startsWith("user/") ->
            "$YOUTUBE_FRONTEND_URL/$canonical"
        else -> "$YOUTUBE_FRONTEND_URL/channel/$canonical"
    }
}
