package com.github.libretube.player.parser

import java.util.Base64
import misc.Common.XTags

/** Extra tags about a format. */
class Xtags {
    private var values: Map<String, String>

    constructor(xtags: String) {
        if (xtags.isEmpty()) {
            values = emptyMap()
            return
        }
        val parsed = XTags.parseFrom(Base64.getUrlDecoder().decode(xtags))
        values = parsed?.xtagsList?.associate { it.key to it.value }.orEmpty()
    }

    /** Returns whether a feature is enabled. */
    private fun isEnabled(feature: String) = values[feature] == "1"

    /**
     * Whether the format uses
     * [dynamic range compression](https://en.wikipedia.org/wiki/Dynamic_range_compression).
     */
    fun isDrcAudio() = isEnabled("drc")

    /**
     * The language of the audio track.
     *
     * For example `en-US`.
     */
    fun language() = values["lang"]

    /** Whether the audio/voices are artificially boosted. */
    fun isVoiceBoosted() = isEnabled("vb")
}
