package com.github.libretube.util

import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper

/**
 * A per-channel on/off setting stored as a set of normalized channel IDs. Used for settings that
 * never weaken protection (the audio-only default and Feed Scope), so changing one never needs the
 * Parent PIN. Stored in preferences, so it is included in backups.
 */
class ChannelSettingSet(private val read: () -> String, private val write: (String) -> Unit) {
    fun channelIds(): Set<String> =
        read().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isEnabled(channelIdOrUrl: String?): Boolean {
        val id = normalizeChannelId(channelIdOrUrl) ?: return false
        return id in channelIds()
    }

    fun set(channelIdOrUrl: String?, enabled: Boolean) {
        val id = normalizeChannelId(channelIdOrUrl) ?: return
        val updated = channelIds().toMutableSet()
        if (enabled) updated.add(id) else updated.remove(id)
        write(updated.sorted().joinToString(","))
    }

    companion object {
        private fun preference(key: String) =
            ChannelSettingSet(
                read = { PreferenceHelper.getString(key, "") },
                write = { PreferenceHelper.putString(key, it) },
            )

        /** Channels whose videos start in the audio player by default. */
        val audioOnlyDefault: ChannelSettingSet by lazy {
            preference(PreferenceKeys.AUDIO_ONLY_CHANNELS)
        }

        /** Subscriptions whose Feed Scope is Series only. */
        val seriesOnlyFeedScope: ChannelSettingSet by lazy {
            preference(PreferenceKeys.SERIES_ONLY_FEED_CHANNELS)
        }
    }
}

/**
 * Whether a video should start in the audio player: when audio was asked for, or when either the
 * global audio-only mode or the owner channel's audio-only default is on and video wasn't forced.
 * An unknown owner falls back to the global preference.
 */
fun shouldStartAudioOnly(
    audioOnlyRequested: Boolean,
    forceVideo: Boolean,
    globalAudioOnly: Boolean,
    ownerAudioOnlyDefault: Boolean,
): Boolean = audioOnlyRequested || (!forceVideo && (globalAudioOnly || ownerAudioOnlyDefault))
