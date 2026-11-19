package com.github.libretube.player

import com.github.libretube.api.obj.Streams
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.util.AccessControl
import com.github.libretube.util.normalizeChannelId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Immutable snapshot of active track identity, speed policy, and Overload Mute telemetry. */
data class PlaybackSessionState(
    val sessionToken: Long? = null,
    val videoId: String? = null,
    val channelId: String? = null,
    val category: String? = null,
    val isMusicRemovalEnabled: Boolean = false,
    val preferredSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val effectiveSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val isLongPressFastForwardActive: Boolean = false,
    val isOverloadMuted: Boolean = false,
) {
    val isMusicCategory: Boolean
        get() = category == Streams.CATEGORY_MUSIC

    companion object {
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }
}

/**
 * Shared playback session state coordinating track identity, Preferred Speed preservation, Music
 * Removal 1.5x clamping, Channel Opt-Out live updates, CATEGORY_MUSIC overrides, long-press
 * fast-forward restoration, and Overload Mute telemetry.
 */
class PlaybackSession(
    private val isMusicRemovalEnabledForChannel: (String?) -> Boolean = { channelId ->
        AccessControl.instance.isMusicRemovalEnabledForChannel(channelId)
    },
    musicRemovalRevision: Flow<Long> = AccessControl.instance.musicRemovalRevision,
    private val readPreferredSpeed: () -> Float = { PlayerHelper.readPersistedPlaybackSpeed() },
    private val writePreferredSpeed: (Float) -> Unit = { speed ->
        PlayerHelper.writePersistedPlaybackSpeed(speed)
    },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {
    private val stateLock = Any()
    private val tokenGenerator = AtomicLong(0L)

    private var activeSessionToken: Long? = null
    private var highestBoundToken: Long = 0L
    private var currentVideoId: String? = null
    private var currentChannelId: String? = null
    private var currentCategory: String? = null
    private var preferredSpeed: Float = sanitizeSpeed(readPreferredSpeed())
    private var trackSpeedOverride: Float? = null
    private var isLongPressFastForwardActive: Boolean = false
    private var fastForwardTargetSpeed: Float? = null
    private var processorOverloadMuted: Boolean = false

    private val _state = MutableStateFlow(PlaybackSessionState())
    val state: StateFlow<PlaybackSessionState> = _state

    init {
        updateState()
        scope.launch {
            musicRemovalRevision.collect {
                synchronized(stateLock) {
                    updateState()
                }
            }
        }
    }

    fun nextSessionToken(): Long = tokenGenerator.incrementAndGet()

    fun bindTrack(
        sessionToken: Long,
        videoId: String,
        channelId: String? = null,
        category: String? = null,
    ): Boolean {
        synchronized(stateLock) {
            if (sessionToken <= 0L || sessionToken < highestBoundToken) {
                return false
            }
            val trackChanged = activeSessionToken != sessionToken || currentVideoId != videoId
            highestBoundToken = sessionToken
            activeSessionToken = sessionToken
            currentVideoId = videoId
            currentChannelId = normalizeChannelId(channelId)
            currentCategory = category?.takeIf { it.isNotBlank() }

            if (trackChanged) {
                processorOverloadMuted = false
                isLongPressFastForwardActive = false
                fastForwardTargetSpeed = null
            }
            trackSpeedOverride =
                if (currentCategory == Streams.CATEGORY_MUSIC) MUSIC_CATEGORY_SPEED else null

            updateState()
            return true
        }
    }

    fun clearTrack(sessionToken: Long): Boolean {
        synchronized(stateLock) {
            if (sessionToken <= 0L || activeSessionToken != sessionToken) {
                return false
            }
            activeSessionToken = null
            currentVideoId = null
            currentChannelId = null
            currentCategory = null
            trackSpeedOverride = null
            isLongPressFastForwardActive = false
            fastForwardTargetSpeed = null
            processorOverloadMuted = false
            updateState()
            return true
        }
    }

    fun setPreferredSpeed(speed: Float): Float {
        synchronized(stateLock) {
            val sanitized = sanitizeSpeed(speed)
            preferredSpeed = sanitized
            trackSpeedOverride = null
            writePreferredSpeed(sanitized)
            updateState()
            return _state.value.effectiveSpeed
        }
    }

    fun overrideTrackSpeed(speed: Float): Float {
        synchronized(stateLock) {
            trackSpeedOverride = sanitizeSpeed(speed)
            updateState()
            return _state.value.effectiveSpeed
        }
    }

    fun beginLongPressFastForward(): Float {
        synchronized(stateLock) {
            if (isLongPressFastForwardActive) {
                return _state.value.effectiveSpeed
            }
            val currentEffective = _state.value.effectiveSpeed
            fastForwardTargetSpeed =
                minOf(
                    currentEffective * PlayerHelper.FAST_FORWARD_SPEED_FACTOR,
                    PlayerHelper.MAXIMUM_PLAYBACK_SPEED,
                )
            isLongPressFastForwardActive = true
            updateState()
            return _state.value.effectiveSpeed
        }
    }

    fun endLongPressFastForward(): Float {
        synchronized(stateLock) {
            if (!isLongPressFastForwardActive) {
                return _state.value.effectiveSpeed
            }
            isLongPressFastForwardActive = false
            fastForwardTargetSpeed = null
            updateState()
            return _state.value.effectiveSpeed
        }
    }

    fun setOverloadMuted(sessionToken: Long, muted: Boolean): Boolean {
        synchronized(stateLock) {
            if (sessionToken <= 0L || activeSessionToken != sessionToken) {
                return false
            }
            processorOverloadMuted = muted
            updateState()
            return true
        }
    }

    fun isMusicRemovalEnabledForSession(sessionToken: Long): Boolean {
        synchronized(stateLock) {
            return if (activeSessionToken == null || activeSessionToken == sessionToken) {
                _state.value.isMusicRemovalEnabled
            } else {
                isMusicRemovalEnabledForChannel(null)
            }
        }
    }

    fun effectiveSpeedForSession(sessionToken: Long): Float {
        synchronized(stateLock) {
            return if (activeSessionToken == null || activeSessionToken == sessionToken) {
                _state.value.effectiveSpeed
            } else {
                PlaybackSessionState.DEFAULT_PLAYBACK_SPEED
            }
        }
    }

    private fun updateState() {
        val musicRemovalEnabled = isMusicRemovalEnabledForChannel(currentChannelId)
        val unclampedSpeed =
            if (isLongPressFastForwardActive) {
                fastForwardTargetSpeed ?: (trackSpeedOverride ?: preferredSpeed)
            } else {
                trackSpeedOverride ?: preferredSpeed
            }
        val effectiveSpeed =
            MusicRemovalEngine.clampPlaybackSpeed(unclampedSpeed, musicRemovalEnabled)
        _state.value =
            PlaybackSessionState(
                sessionToken = activeSessionToken,
                videoId = currentVideoId,
                channelId = currentChannelId,
                category = currentCategory,
                isMusicRemovalEnabled = musicRemovalEnabled,
                preferredSpeed = preferredSpeed,
                effectiveSpeed = effectiveSpeed,
                isLongPressFastForwardActive = isLongPressFastForwardActive,
                isOverloadMuted = processorOverloadMuted && musicRemovalEnabled,
            )
    }

    companion object {
        private const val MUSIC_CATEGORY_SPEED = 1.0f

        val instance: PlaybackSession by lazy { PlaybackSession() }
    }
}

private const val MinPlaybackSpeed = 0.25f

private fun sanitizeSpeed(speed: Float): Float {
    return speed
        .takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(MinPlaybackSpeed, PlayerHelper.MAXIMUM_PLAYBACK_SPEED)
        ?: PlaybackSessionState.DEFAULT_PLAYBACK_SPEED
}
