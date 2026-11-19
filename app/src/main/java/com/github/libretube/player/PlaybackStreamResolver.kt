package com.github.libretube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.poToken.PoTokenGenerator
import com.github.libretube.player.parser.SabrStreamException
import com.github.libretube.util.DeArrowUtil
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider

/**
 * Video-scoped HTTP/SABR transport context propagated from extraction through stream refresh
 * recovery into playback and download data sources.
 */
data class StreamTransportContext(
    val videoId: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val httpClient: OkHttpClient = defaultHttpClient,
    val poTokenProvider: PoTokenProvider = PoTokenGenerator.instance,
) {
    fun resolveUserAgent(url: String? = null): String {
        val headerUserAgent =
            headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }

        return resolveUrlClientUserAgent(url)
            ?: userAgent?.takeIf { it.isNotBlank() }
            ?: headerUserAgent
            ?: DEFAULT_USER_AGENT
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"
        const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
        const val ANDROID_KIDS_USER_AGENT =
            "com.google.android.apps.youtube.kids/9.44.2 (Linux; U; Android 15; US) gzip"
        const val VISIONOS_USER_AGENT =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; GB)"

        private const val POOL_MAX_IDLE_CONNECTIONS = 10
        private const val POOL_KEEP_ALIVE_MINUTES = 5L
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 20L

        val sharedConnectionPool =
            ConnectionPool(POOL_MAX_IDLE_CONNECTIONS, POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)

        val defaultHttpClient: OkHttpClient =
            OkHttpClient.Builder()
                .connectionPool(sharedConnectionPool)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        fun userAgentForClientName(clientName: String?): String? =
            when (clientName?.uppercase()) {
                "IOS" -> IOS_USER_AGENT
                "ANDROID_KIDS" -> ANDROID_KIDS_USER_AGENT
                "WEB",
                "MWEB",
                "TVHTML5",
                "WEB_REMIX",
                "WEB_EMBEDDED_PLAYER",
                "WEB_CREATOR" -> DEFAULT_USER_AGENT
                "VISIONOS" -> VISIONOS_USER_AGENT
                else -> null
            }

        internal fun resolveUrlClientUserAgent(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val clientName =
                url.toHttpUrlOrNull()?.queryParameter("c") ?: extractRawQueryParam(url, "c")
            return userAgentForClientName(clientName)
        }

        private fun extractRawQueryParam(url: String, paramName: String): String? {
            val queryStart = url.indexOf('?')
            return if (queryStart < 0 || queryStart + 1 >= url.length) {
                null
            } else {
                val query = url.substring(queryStart + 1).substringBefore('#')
                query.split('&').firstNotNullOfOrNull { pair ->
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx > 0 && pair.substring(0, eqIdx) == paramName) {
                        pair.substring(eqIdx + 1)
                    } else {
                        null
                    }
                }
            }
        }
    }
}

sealed class PlaybackSource {
    data object GeneratedDash : PlaybackSource()

    data class RemoteDash(val url: String) : PlaybackSource()

    data object Sabr : PlaybackSource()

    data class Hls(val url: String) : PlaybackSource()

    data object None : PlaybackSource()
}

fun PipedStream.isPlayableDashVideo(): Boolean = videoOnly == true && hasDashIndex()

fun PipedStream.isPlayableDashAudio(): Boolean = videoOnly != true && hasDashIndex()

private fun PipedStream.hasDashIndex(): Boolean {
    val hasUrl = !url.isNullOrBlank() && url?.startsWith("sabr://") != true
    val hasFormat = !mimeType.isNullOrBlank() && !codec.isNullOrBlank()
    val hasRanges = initStart != null && initEnd != null && indexStart != null
    return hasUrl && hasFormat && hasRanges && (indexEnd ?: 0) > 0
}

/**
 * Paired extraction result bundling streams, client-matched transport context, and chosen source.
 */
data class ResolvedPlaybackStream(
    val videoId: String,
    val streams: Streams,
    val transportContext: StreamTransportContext,
    val source: PlaybackSource,
)

sealed interface StreamRecoveryOutcome {
    data class Recovered(
        val resolved: ResolvedPlaybackStream,
        val resumePositionMs: Long,
    ) : StreamRecoveryOutcome

    data class Failed(val error: Throwable) : StreamRecoveryOutcome

    data object Stale : StreamRecoveryOutcome
}

/**
 * Resolves playable YouTube streams with their client-matched [StreamTransportContext] and
 * [PlaybackSource], and coordinates bounded single-retry recovery on HTTP 403/410 or SABR reload
 * directives while keeping [MediaServiceRepository] as the ADR-0001 Allowlist seam.
 */
@OptIn(UnstableApi::class)
class PlaybackStreamResolver(
    private val repository: MediaServiceRepository = MediaServiceRepository.instance,
    private val enrichStreams: suspend (Streams, String) -> Streams = { streams, id ->
        runCatching { DeArrowUtil.deArrowStreams(streams, id) }.getOrDefault(streams)
    },
    private val maxRetries: Int = MAX_STREAM_REFRESH_RETRIES,
) {
    @Volatile private var activeVideoId: String? = null

    @Volatile private var retryCount: Int = 0

    @Volatile
    var isRefreshing: Boolean = false
        private set

    @Synchronized
    fun onVideoStarted(videoId: String) {
        activeVideoId = videoId
        retryCount = 0
        isRefreshing = false
    }

    /**
     * Fetches streams through [repository], pairs them immediately with the matching
     * [StreamTransportContext], and chooses an openable [PlaybackSource].
     */
    suspend fun resolve(videoId: String): ResolvedPlaybackStream {
        val streams = enrichStreams(repository.getStreams(videoId), videoId)
        val transportContext = repository.getStreamTransportContext(videoId)
        return ResolvedPlaybackStream(
            videoId = videoId,
            streams = streams,
            transportContext = transportContext,
            source = choosePlaybackSource(streams),
        )
    }

    @Synchronized
    fun hasPendingRecovery(videoId: String?, error: Throwable?): Boolean {
        if (videoId.isNullOrBlank() || videoId != activeVideoId) return false
        return isRefreshing || canStartNewRecovery(videoId, error)
    }

    @Synchronized
    fun beginRecoveryIfEligible(videoId: String?, error: Throwable?): Boolean {
        val eligible = canStartNewRecovery(videoId, error)
        if (eligible) {
            isRefreshing = true
            retryCount++
        }
        return eligible
    }

    /** Evicts cached streams and resolves a fresh stream + transport pair for [videoId]. */
    suspend fun recover(videoId: String, resumePositionMs: Long): StreamRecoveryOutcome {
        return try {
            runCatching {
                repository.invalidateStreamsCache(videoId)
                val fresh = resolve(videoId)
                if (activeVideoId != videoId) {
                    StreamRecoveryOutcome.Stale
                } else {
                    StreamRecoveryOutcome.Recovered(
                        resolved = fresh,
                        resumePositionMs = resumePositionMs.coerceAtLeast(0L),
                    )
                }
            }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    StreamRecoveryOutcome.Failed(error)
                }
        } finally {
            synchronized(this) {
                if (activeVideoId == videoId) {
                    isRefreshing = false
                }
            }
        }
    }

    /**
     * Idle during startup or while recovery is pending must not tear the player down; idle after
     * playback started with no controllers or recovery means the session is done.
     */
    fun shouldDestroyOnIdle(
        videoId: String?,
        playerError: Throwable?,
        isTransitioning: Boolean,
        hasPlaybackStarted: Boolean,
        hasConnectedControllers: Boolean,
    ): Boolean {
        val canStop = hasPlaybackStarted && !hasConnectedControllers && !isTransitioning
        return canStop && !hasPendingRecovery(videoId, playerError)
    }

    fun isRecoverableError(error: Throwable?): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current = error
        var depth = 0
        var recoverable = false
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            if (isRecoverableSingleError(current)) {
                recoverable = true
                break
            }
            current = current.cause
            depth++
        }
        return recoverable
    }

    @Synchronized
    private fun canStartNewRecovery(videoId: String?, error: Throwable?): Boolean {
        val matchesActive = !videoId.isNullOrBlank() && videoId == activeVideoId
        val withinBudget = !isRefreshing && retryCount < maxRetries
        return matchesActive && withinBudget && isRecoverableError(error)
    }

    private fun isRecoverableSingleError(error: Throwable): Boolean =
        when (error) {
            is HttpDataSource.InvalidResponseCodeException ->
                error.responseCode == HTTP_FORBIDDEN || error.responseCode == HTTP_GONE
            is SabrStreamException ->
                error.isReloadRequired ||
                    error.httpStatusCode == HTTP_FORBIDDEN ||
                    error.httpStatusCode == HTTP_GONE
            else -> false
        }

    companion object {
        const val MAX_STREAM_REFRESH_RETRIES = 1
        private const val MAX_CAUSE_DEPTH = 16
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_GONE = 410

        /**
         * Picks a stream that ExoPlayer can actually open. A plain video URL without an index range
         * cannot be used for a generated DASH manifest.
         */
        fun choosePlaybackSource(streams: Streams): PlaybackSource =
            if (streams.isLive) {
                when {
                    !streams.dash.isNullOrBlank() -> PlaybackSource.RemoteDash(streams.dash)
                    !streams.hls.isNullOrBlank() -> PlaybackSource.Hls(streams.hls)
                    else -> PlaybackSource.None
                }
            } else {
                val playableVideo = streams.videoStreams.any { it.isPlayableDashVideo() }
                val playableAudio = streams.audioStreams.any { it.isPlayableDashAudio() }
                val hasSabr =
                    !streams.serverAbrStreamingUrl.isNullOrBlank() &&
                        !streams.videoPlaybackUstreamerConfig.isNullOrBlank()
                when {
                    playableVideo && playableAudio -> PlaybackSource.GeneratedDash
                    hasSabr -> PlaybackSource.Sabr
                    !streams.hls.isNullOrBlank() -> PlaybackSource.Hls(streams.hls)
                    playableVideo || playableAudio -> PlaybackSource.GeneratedDash
                    else -> PlaybackSource.None
                }
            }
    }
}
