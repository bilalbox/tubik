package com.github.libretube.updater

import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper

/** Persistence abstraction for OTA update preferences and cached manifest metadata. */
interface UpdateStorage {
    var autoUpdateCheckEnabled: Boolean
    var lastCheckTimestampMillis: Long
    var cachedManifestJson: String?
    var lastNotifiedVersionCode: Int
}

/** [UpdateStorage] backed by [PreferenceHelper]. */
object PreferenceUpdateStorage : UpdateStorage {
    override var autoUpdateCheckEnabled: Boolean
        get() = PreferenceHelper.getBoolean(PreferenceKeys.AUTO_UPDATE_CHECK_ENABLED, true)
        set(value) = PreferenceHelper.putBoolean(PreferenceKeys.AUTO_UPDATE_CHECK_ENABLED, value)

    override var lastCheckTimestampMillis: Long
        get() = PreferenceHelper.getLong(PreferenceKeys.LAST_UPDATE_CHECK_TIMESTAMP, 0L)
        set(value) = PreferenceHelper.putLong(PreferenceKeys.LAST_UPDATE_CHECK_TIMESTAMP, value)

    override var cachedManifestJson: String?
        get() =
            PreferenceHelper.getString(PreferenceKeys.CACHED_UPDATE_MANIFEST, "").takeIf {
                it.isNotBlank()
            }
        set(value) {
            if (value.isNullOrBlank()) {
                PreferenceHelper.remove(PreferenceKeys.CACHED_UPDATE_MANIFEST)
            } else {
                PreferenceHelper.putString(PreferenceKeys.CACHED_UPDATE_MANIFEST, value)
            }
        }

    override var lastNotifiedVersionCode: Int
        get() = PreferenceHelper.getInt(PreferenceKeys.LAST_NOTIFIED_UPDATE_VERSION_CODE, 0)
        set(value) =
            PreferenceHelper.putInt(PreferenceKeys.LAST_NOTIFIED_UPDATE_VERSION_CODE, value)
}
