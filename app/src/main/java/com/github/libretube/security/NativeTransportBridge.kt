package com.github.libretube.security

import android.content.Context
import androidx.annotation.Keep
import com.github.libretube.BuildConfig

/**
 * Resolves transport parameters through `libtubik_native.so`, binding network and stream parsing
 * behavior to the active APK signing certificate and package identity.
 */
@Keep
object NativeTransportBridge {
    private const val LIB_NAME = "tubik_native"
    private const val SLOT_PLAYER_PATH = 0
    private const val SLOT_VISITOR_ENDPOINT = 1
    private const val SLOT_STREAMING_DATA = 2
    private const val SLOT_PLAYABILITY_STATUS = 3

    @Volatile private var appContext: Context? = null

    @Volatile private var nativeLoaded: Boolean = false

    @Volatile private var useDebugFallback: Boolean = false

    @Volatile private var cachedPlayerPath: String? = null

    @Volatile private var cachedVisitorEndpoint: String? = null

    @Volatile private var cachedStreamingDataKey: String? = null

    @Volatile private var cachedPlayabilityStatusKey: String? = null

    @Volatile private var cachedUmpMask: Int? = null

    /** Initializes native transport resolution with an application [Context]. */
    fun initialize(context: Context) {
        val applicationContext = context.applicationContext ?: context
        appContext = applicationContext
        if (ensureLoaded() && nativeLoaded) {
            nativeInit(applicationContext)
        }
    }

    /** Returns the YouTube player endpoint path token required for stream fallback matching. */
    fun playerPathToken(): String =
        cachedPlayerPath
            ?: resolveSlot(SLOT_PLAYER_PATH, DebugFallback.PLAYER_PATH).also {
                cachedPlayerPath = it
            }

    /** Returns the YouTube visitor-ID endpoint URL used by fallback player clients. */
    fun visitorIdEndpoint(): String =
        cachedVisitorEndpoint
            ?: resolveSlot(SLOT_VISITOR_ENDPOINT, DebugFallback.VISITOR_ENDPOINT).also {
                cachedVisitorEndpoint = it
            }

    /** Returns the JSON key name for playable stream metadata. */
    fun streamingDataKey(): String =
        cachedStreamingDataKey
            ?: resolveSlot(SLOT_STREAMING_DATA, DebugFallback.STREAMING_DATA).also {
                cachedStreamingDataKey = it
            }

    /** Returns the JSON key name for player playability status metadata. */
    fun playabilityStatusKey(): String =
        cachedPlayabilityStatusKey
            ?: resolveSlot(SLOT_PLAYABILITY_STATUS, DebugFallback.PLAYABILITY_STATUS).also {
                cachedPlayabilityStatusKey = it
            }

    /**
     * Returns the UMP part-type XOR mask derived from the active signing certificate (0 when the
     * certificate and package identity match).
     */
    fun umpPartMask(): Int =
        cachedUmpMask
            ?: run {
                val mask =
                    if (ensureLoaded() && nativeLoaded) {
                        nativeResolveUmpMask(appContext)
                    } else if (BuildConfig.DEBUG && useDebugFallback) {
                        0
                    } else {
                        -1
                    }
                cachedUmpMask = mask
                mask
            }

    private fun resolveSlot(slot: Int, debugDefault: String): String {
        return if (ensureLoaded() && nativeLoaded) {
            nativeResolveSlot(appContext, slot)
        } else if (BuildConfig.DEBUG && useDebugFallback) {
            debugDefault
        } else {
            ""
        }
    }

    private fun ensureLoaded(): Boolean {
        if (!nativeLoaded && !useDebugFallback) {
            synchronized(this) {
                if (!nativeLoaded && !useDebugFallback) {
                    if (BuildConfig.DEBUG) {
                        try {
                            System.loadLibrary(LIB_NAME)
                            nativeLoaded = true
                        } catch (_: UnsatisfiedLinkError) {
                            // Host JVM unit tests run without Android native libraries.
                            useDebugFallback = true
                        }
                    } else {
                        System.loadLibrary(LIB_NAME)
                        nativeLoaded = true
                    }
                }
            }
        }
        return true
    }

    @JvmStatic private external fun nativeInit(context: Context?)

    @JvmStatic private external fun nativeResolveSlot(context: Context?, slot: Int): String

    @JvmStatic private external fun nativeResolveUmpMask(context: Context?): Int

    /**
     * Debug/JVM-test-only defaults stripped by R8 in release builds where `BuildConfig.DEBUG` is
     * false.
     */
    private object DebugFallback {
        const val PLAYER_PATH = "/youtubei/v1/player"
        const val VISITOR_ENDPOINT =
            "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        const val STREAMING_DATA = "streamingData"
        const val PLAYABILITY_STATUS = "playabilityStatus"
    }
}
