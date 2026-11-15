package com.github.libretube.util

import androidx.collection.LruCache

/**
 * Bounded, instance-scoped registry mapping extracted video IDs to the YouTube client `User-Agent`
 * that produced their playable streams.
 */
class ClientUserAgentRegistry(maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val clientUserAgentsByVideoId = LruCache<String, String>(maxEntries)

    fun record(videoId: String?, userAgent: String) {
        if (!videoId.isNullOrBlank() && userAgent.isNotBlank()) {
            synchronized(clientUserAgentsByVideoId) {
                clientUserAgentsByVideoId.put(videoId, userAgent)
            }
        }
    }

    fun get(videoId: String?): String? {
        val key = videoId?.takeIf { it.isNotBlank() } ?: return null
        return synchronized(clientUserAgentsByVideoId) { clientUserAgentsByVideoId[key] }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 128
    }
}
