package com.github.libretube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.github.libretube.api.obj.Streams
import com.github.libretube.player.parser.SabrStreamException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/**
 * Coordinates bounded single-retry stream recovery when mid-stream playback fails with HTTP 403/410
 * or a SABR player reload directive.
 */
@OptIn(UnstableApi::class)
class StreamRefreshCoordinator(
    private val invalidateStreamsCache: (String) -> Unit,
    private val fetchStreams: suspend (String) -> Streams,
    private val resolveTransportContext: (String) -> StreamTransportContext = { videoId ->
        StreamTransportContext(videoId = videoId)
    },
    private val maxRetries: Int = MAX_STREAM_REFRESH_RETRIES,
) {
    sealed interface RecoveryOutcome {
        data class Recovered(
            val streams: Streams,
            val transportContext: StreamTransportContext,
            val resumePositionMs: Long,
        ) : RecoveryOutcome

        data class Failed(val error: Throwable) : RecoveryOutcome

        data object Stale : RecoveryOutcome
    }

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

    @Synchronized
    fun canStartNewRecovery(videoId: String?, error: Throwable?): Boolean {
        if (videoId.isNullOrBlank() || videoId != activeVideoId) return false
        if (isRefreshing || retryCount >= maxRetries) return false
        return isRecoverableError(error)
    }

    @Synchronized
    fun hasPendingRecovery(videoId: String?, error: Throwable?): Boolean {
        if (videoId.isNullOrBlank() || videoId != activeVideoId) return false
        return isRefreshing || canStartNewRecovery(videoId, error)
    }

    @Synchronized
    fun beginRecoveryIfEligible(videoId: String?, error: Throwable?): Boolean {
        if (!canStartNewRecovery(videoId, error)) return false
        isRefreshing = true
        retryCount++
        return true
    }

    suspend fun executeRecovery(videoId: String, resumePositionMs: Long): RecoveryOutcome {
        return try {
            invalidateStreamsCache(videoId)
            val freshStreams = fetchStreams(videoId)
            if (activeVideoId != videoId) {
                RecoveryOutcome.Stale
            } else {
                RecoveryOutcome.Recovered(
                    streams = freshStreams,
                    transportContext = resolveTransportContext(videoId),
                    resumePositionMs = resumePositionMs.coerceAtLeast(0L),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            RecoveryOutcome.Failed(t)
        } finally {
            synchronized(this) {
                if (activeVideoId == videoId) {
                    isRefreshing = false
                }
            }
        }
    }

    fun isRecoverableError(error: Throwable?): Boolean {
        if (error == null) return false
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            when (current) {
                is HttpDataSource.InvalidResponseCodeException -> {
                    if (current.responseCode == 403 || current.responseCode == 410) {
                        return true
                    }
                }
                is SabrStreamException -> {
                    if (
                        current.isReloadRequired ||
                            current.httpStatusCode == 403 ||
                            current.httpStatusCode == 410
                    ) {
                        return true
                    }
                }
            }
            current = current.cause
            depth++
        }
        return false
    }

    companion object {
        const val MAX_STREAM_REFRESH_RETRIES = 1
        private const val MAX_CAUSE_DEPTH = 16

        internal inline fun applyRecoveryOutcome(
            currentVideoId: String?,
            targetVideoId: String,
            outcome: RecoveryOutcome,
            hasPendingRecovery: Boolean,
            onRecovered: (RecoveryOutcome.Recovered) -> Unit,
            onFailed: (Throwable) -> Unit,
            onDestroyService: () -> Unit,
        ) {
            when (outcome) {
                is RecoveryOutcome.Recovered -> {
                    if (currentVideoId == targetVideoId) {
                        onRecovered(outcome)
                    }
                }

                is RecoveryOutcome.Failed -> {
                    if (currentVideoId == targetVideoId) {
                        onFailed(outcome.error)
                        if (!hasPendingRecovery) {
                            onDestroyService()
                        }
                    }
                }

                RecoveryOutcome.Stale -> Unit
            }
        }
    }
}
