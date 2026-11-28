package com.github.libretube.util

import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.LocalPlaylistItem
import com.github.libretube.db.obj.Series
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Canonical channel ID normalization shared across Access Control and playback state. */
internal fun normalizeChannelId(primary: String?, fallback: String? = null): String? {
    val first = primary?.takeIf { it.isNotBlank() }?.toID()?.takeIf { it.isNotBlank() }
    return first ?: fallback?.takeIf { it.isNotBlank() }?.toID()?.takeIf { it.isNotBlank() }
}

private object PinCrypto {
    const val PBKDF2_PREFIX = "pbkdf2_sha256$"
    const val PBKDF2_ITERATIONS = 120_000
    const val PBKDF2_KEY_BITS = 256

    fun hashPin(
        pin: String,
        saltBytes: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): String {
        val spec = PBEKeySpec(pin.toCharArray(), saltBytes, iterations, PBKDF2_KEY_BITS)
        val key =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        return "${PBKDF2_PREFIX}${iterations}$${Base64.getEncoder().encodeToString(key)}"
    }

    fun legacySha256Hash(pin: String, saltBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(saltBytes)
        val hashed = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashed)
    }

    fun matchesStoredHash(
        normalizedPin: String,
        saltBytes: ByteArray,
        storedHash: String,
    ): Boolean {
        val candidateHash =
            if (storedHash.startsWith(PBKDF2_PREFIX)) {
                val parts = storedHash.removePrefix(PBKDF2_PREFIX).split('$', limit = 2)
                val iterations = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 }
                if (parts.size != 2 || iterations == null) return false
                hashPin(normalizedPin, saltBytes, iterations)
            } else {
                legacySha256Hash(normalizedPin, saltBytes)
            }
        return MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.UTF_8),
            candidateHash.toByteArray(Charsets.UTF_8),
        )
    }
}

/** Persistence port for Parent PIN, Allowlist, Music Removal, Channel Opt-Out, and Screen Pin. */
interface AccessControlStorage {
    var pinHash: String?
    var pinSalt: String?
    var failedPinAttempts: Int
    var pinLockedUntilEpochMillis: Long
    var allowlistEnabled: Boolean
    var musicRemovalEnabled: Boolean
    var musicRemovalDisabledChannels: Set<String>
    var screenPinEnabled: Boolean
    var settingsPinLockEnabled: Boolean
}

internal data class InMemoryAccessControlStorage(
    override var pinHash: String? = null,
    override var pinSalt: String? = null,
    override var failedPinAttempts: Int = 0,
    override var pinLockedUntilEpochMillis: Long = 0L,
    override var allowlistEnabled: Boolean = false,
    override var musicRemovalEnabled: Boolean = true,
    override var musicRemovalDisabledChannels: Set<String> = emptySet(),
    override var screenPinEnabled: Boolean = false,
    override var settingsPinLockEnabled: Boolean = false,
) : AccessControlStorage

/** Result of a Parent PIN verification attempt, including exponential backoff lockouts. */
sealed interface PinVerificationResult {
    data object Verified : PinVerificationResult

    data class Incorrect(val failedAttempts: Int) : PinVerificationResult

    data class LockedOut(val remainingMillis: Long) : PinVerificationResult
}

/** UI prompt adapter for verifying the Parent PIN before protected actions or weakening changes. */
fun interface PinPromptAdapter {
    fun requestPinVerification(onVerified: () -> Unit, onCancelled: () -> Unit)
}

/** External actions gated by Parent PIN that are executed outside [AccessControl]. */
sealed interface ProtectedAction {
    data object OpenSettings : ProtectedAction

    data class ChangeSubscription(val subscribe: Boolean) : ProtectedAction

    data object BackupOrRestore : ProtectedAction
}

/** Policy mutations owned atomically (Parent PIN check + persistence) by [AccessControl]. */
sealed interface PolicyChange {
    data class SetAllowlist(val enabled: Boolean) : PolicyChange

    data class SetMusicRemoval(val enabled: Boolean) : PolicyChange

    data class SetChannelMusicRemoval(val channelIdOrUrl: String, val enabled: Boolean) :
        PolicyChange

    data class SetScreenPin(val enabled: Boolean) : PolicyChange

    data class SetSettingsPinLock(val enabled: Boolean) : PolicyChange
}

/** Point-in-time view of Access Control flags and derived UI capability rules. */
data class AccessPolicy(
    val isPinSet: Boolean,
    val allowlistEnabled: Boolean,
    val musicRemovalEnabled: Boolean,
    val screenPinEnabled: Boolean,
    val settingsPinLockEnabled: Boolean,
) {
    val canModifySubscriptions: Boolean
        get() = !allowlistEnabled

    val canViewComments: Boolean
        get() = !allowlistEnabled

    val shouldHideMusicSearchChips: Boolean
        get() = allowlistEnabled || musicRemovalEnabled

    fun canViewRelatedStreams(relatedStreamsEnabled: Boolean): Boolean =
        relatedStreamsEnabled && !allowlistEnabled

    fun areSearchSuggestionsEnabled(searchSuggestionsEnabled: Boolean): Boolean =
        searchSuggestionsEnabled || allowlistEnabled
}

/** Immutable point-in-time snapshot of Allowlist enforcement state and Allowed Channel IDs. */
data class AllowedSnapshot(
    val allowlistEnabled: Boolean,
    val allowedChannelIds: Set<String> = emptySet(),
) {
    private val normalizedAllowedIds: Set<String> =
        allowedChannelIds.mapNotNullTo(mutableSetOf()) { normalizeChannelId(it) }

    fun isChannelAllowed(channelIdOrUrl: String?): Boolean {
        if (!allowlistEnabled) return true
        val normalizedId = normalizeChannelId(channelIdOrUrl)
        return normalizedId != null && normalizedId in normalizedAllowedIds
    }

    fun isStreamItemAllowed(item: StreamItem): Boolean =
        isTypedItemAllowed(item.type, item.url, item.uploaderUrl)

    fun isContentItemAllowed(item: ContentItem): Boolean =
        isTypedItemAllowed(item.type, item.url, item.uploaderUrl)

    private fun isTypedItemAllowed(type: String?, url: String?, uploaderUrl: String?): Boolean {
        val channelIdOrUrl = if (type == StreamItem.TYPE_CHANNEL) url else uploaderUrl
        return isChannelAllowed(channelIdOrUrl)
    }

    fun filterStreamItems(items: List<StreamItem>): List<StreamItem> =
        if (!allowlistEnabled) items else items.filter(::isStreamItemAllowed)

    fun filterPlaylist(playlist: Playlist): Playlist =
        if (!allowlistEnabled) {
            playlist
        } else {
            playlist.copy(relatedStreams = filterStreamItems(playlist.relatedStreams))
        }

    fun filterWatchHistoryEntries(entries: List<WatchHistoryEntry>): List<WatchHistoryEntry> =
        filterWhenEnabled(entries) { it.video.uploaderUrl }

    fun filterLocalPlaylistItems(items: List<LocalPlaylistItem>): List<LocalPlaylistItem> =
        filterWhenEnabled(items) { it.uploaderUrl }

    fun filterSeries(bookmarks: List<Series>): List<Series> =
        filterWhenEnabled(bookmarks) { it.uploaderUrl }

    fun filterDownloads(downloads: List<DownloadWithItems>): List<DownloadWithItems> =
        filterWhenEnabled(downloads) { it.download.uploaderUrl }

    private inline fun <T> filterWhenEnabled(
        items: List<T>,
        channelSelector: (T) -> String?,
    ): List<T> =
        if (!allowlistEnabled) items else items.filter { isChannelAllowed(channelSelector(it)) }
}

/**
 * Unified Access Control module owning Parent PIN verification, Allowlist & Allowed Channel
 * snapshots, Music Removal switch & Channel Opt-Out state, and Screen Pin policy.
 */
class AccessControl(
    private val storage: AccessControlStorage,
    private val getAllowedChannelIds: suspend () -> Set<String> = {
        SubscriptionHelper.getSubscriptionChannelIds().toSet()
    },
    private val getAllowedSubscriptions: suspend () -> List<Subscription> = {
        runCatching { SubscriptionHelper.getSubscriptions() }.getOrDefault(emptyList())
    },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val secureRandom = SecureRandom()
    private val _musicRemovalRevision = MutableStateFlow(0L)
    val musicRemovalRevision: StateFlow<Long> = _musicRemovalRevision

    val policy: AccessPolicy
        get() {
            val pinSet = !storage.pinHash.isNullOrBlank() && !storage.pinSalt.isNullOrBlank()
            return AccessPolicy(
                isPinSet = pinSet,
                allowlistEnabled = storage.allowlistEnabled,
                musicRemovalEnabled = storage.musicRemovalEnabled,
                screenPinEnabled = storage.screenPinEnabled,
                settingsPinLockEnabled = pinSet && storage.settingsPinLockEnabled,
            )
        }

    /** Gates an external [action] behind Parent PIN when required. */
    fun authorize(
        action: ProtectedAction,
        prompt: PinPromptAdapter,
        onCancelled: () -> Unit = {},
        onAuthorized: () -> Unit,
    ) {
        val current = policy
        val requiresPin =
            when (action) {
                ProtectedAction.OpenSettings -> current.settingsPinLockEnabled
                is ProtectedAction.ChangeSubscription -> !action.subscribe && current.isPinSet
                ProtectedAction.BackupOrRestore -> current.isPinSet
            }
        if (requiresPin) {
            prompt.requestPinVerification(onVerified = onAuthorized, onCancelled = onCancelled)
        } else {
            onAuthorized()
        }
    }

    /**
     * Atomically verifies Parent PIN (whenever [change] weakens protection) and persists the
     * mutation to storage, reporting the updated [AccessPolicy] to [onApplied].
     */
    fun apply(
        change: PolicyChange,
        prompt: PinPromptAdapter,
        onCancelled: () -> Unit = {},
        onApplied: (AccessPolicy) -> Unit = {},
    ) {
        val pinSet = policy.isPinSet
        val requiresPin =
            when (change) {
                is PolicyChange.SetAllowlist -> !change.enabled && pinSet
                is PolicyChange.SetMusicRemoval -> !change.enabled && pinSet
                is PolicyChange.SetChannelMusicRemoval -> !change.enabled && pinSet
                is PolicyChange.SetScreenPin -> !change.enabled && pinSet
                is PolicyChange.SetSettingsPinLock -> !change.enabled && pinSet
            }
        val commit = {
            applyMutation(change)
            onApplied(policy)
        }
        if (requiresPin) {
            prompt.requestPinVerification(onVerified = commit, onCancelled = onCancelled)
        } else {
            commit()
        }
    }

    /** Resolves a fresh Allowlist snapshot from current subscriptions. */
    suspend fun snapshot(): AllowedSnapshot {
        if (!storage.allowlistEnabled) {
            return AllowedSnapshot(allowlistEnabled = false)
        }
        val channelIds =
            try {
                getAllowedChannelIds()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                getAllowedSubscriptions().mapNotNullTo(mutableSetOf()) { sub ->
                    normalizeChannelId(sub.url)
                }
            }
        return AllowedSnapshot(allowlistEnabled = true, allowedChannelIds = channelIds)
    }

    fun isMusicRemovalDisabledForChannel(channelIdOrUrl: String?): Boolean {
        val normalized = normalizeChannelId(channelIdOrUrl)
        return normalized != null && normalized in storage.musicRemovalDisabledChannels
    }

    fun isMusicRemovalEnabledForChannel(channelIdOrUrl: String?): Boolean {
        val normalized = normalizeChannelId(channelIdOrUrl)
        return storage.musicRemovalEnabled &&
            (normalized == null || normalized !in storage.musicRemovalDisabledChannels)
    }

    fun setPin(pin: String): Boolean {
        val normalized = pin.trim()
        val valid = normalized.isNotEmpty()
        if (valid) {
            val saltBytes = ByteArray(SALT_BYTES)
            secureRandom.nextBytes(saltBytes)
            storage.pinSalt = Base64.getEncoder().encodeToString(saltBytes)
            storage.pinHash = PinCrypto.hashPin(normalized, saltBytes)
            storage.failedPinAttempts = 0
            storage.pinLockedUntilEpochMillis = 0L
        }
        return valid
    }

    fun verifyPin(pin: String): PinVerificationResult {
        val now = clockMillis()
        val lockedUntil = storage.pinLockedUntilEpochMillis
        if (lockedUntil > now) {
            return PinVerificationResult.LockedOut(lockedUntil - now)
        }
        val storedHash = storage.pinHash
        val saltBytes =
            storage.pinSalt?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val normalized = pin.trim()
        return when {
            storedHash == null || saltBytes == null -> PinVerificationResult.Incorrect(0)
            normalized.isNotEmpty() &&
                PinCrypto.matchesStoredHash(normalized, saltBytes, storedHash) -> {
                storage.failedPinAttempts = 0
                storage.pinLockedUntilEpochMillis = 0L
                if (!storedHash.startsWith(PinCrypto.PBKDF2_PREFIX)) {
                    setPin(normalized)
                }
                PinVerificationResult.Verified
            }
            else -> recordFailedAttempt(now)
        }
    }

    fun checkPin(pin: String): Boolean = verifyPin(pin) == PinVerificationResult.Verified

    fun changePin(currentPin: String, newPin: String): Boolean =
        checkPin(currentPin) && setPin(newPin)

    private fun recordFailedAttempt(now: Long): PinVerificationResult {
        val failedAttempts = storage.failedPinAttempts + 1
        storage.failedPinAttempts = failedAttempts
        return if (failedAttempts >= MAX_FAILED_ATTEMPTS_BEFORE_LOCKOUT) {
            val shift =
                (failedAttempts - MAX_FAILED_ATTEMPTS_BEFORE_LOCKOUT).coerceAtMost(
                    MAX_BACKOFF_SHIFT,
                )
            val cooldown = minOf(BASE_LOCKOUT_MILLIS shl shift, MAX_LOCKOUT_MILLIS)
            storage.pinLockedUntilEpochMillis = now + cooldown
            PinVerificationResult.LockedOut(cooldown)
        } else {
            PinVerificationResult.Incorrect(failedAttempts)
        }
    }

    private fun applyMutation(change: PolicyChange) {
        when (change) {
            is PolicyChange.SetAllowlist -> storage.allowlistEnabled = change.enabled
            is PolicyChange.SetMusicRemoval -> {
                storage.musicRemovalEnabled = change.enabled
                _musicRemovalRevision.value++
            }
            is PolicyChange.SetChannelMusicRemoval -> {
                val normalized = normalizeChannelId(change.channelIdOrUrl) ?: return
                val updated = storage.musicRemovalDisabledChannels.toMutableSet()
                if (change.enabled) updated.remove(normalized) else updated.add(normalized)
                storage.musicRemovalDisabledChannels = updated
                _musicRemovalRevision.value++
            }
            is PolicyChange.SetScreenPin -> storage.screenPinEnabled = change.enabled
            is PolicyChange.SetSettingsPinLock -> storage.settingsPinLockEnabled = change.enabled
        }
    }

    companion object {
        private const val SALT_BYTES = 16
        private const val MAX_FAILED_ATTEMPTS_BEFORE_LOCKOUT = 5
        private const val BASE_LOCKOUT_MILLIS = 30_000L
        private const val MAX_LOCKOUT_MILLIS = 900_000L
        private const val MAX_BACKOFF_SHIFT = 5

        val instance: AccessControl by lazy { AccessControl(PreferenceAccessControlStorage) }
    }
}

object PreferenceAccessControlStorage : AccessControlStorage {
    override var pinHash: String?
        get() = PreferenceHelper.getParentPinHash()
        set(value) = PreferenceHelper.setParentPinHash(value)

    override var pinSalt: String?
        get() = PreferenceHelper.getParentPinSalt()
        set(value) = PreferenceHelper.setParentPinSalt(value)

    override var failedPinAttempts: Int
        get() = PreferenceHelper.getParentPinFailedAttempts()
        set(value) = PreferenceHelper.setParentPinFailedAttempts(value)

    override var pinLockedUntilEpochMillis: Long
        get() = PreferenceHelper.getParentPinLockedUntil()
        set(value) = PreferenceHelper.setParentPinLockedUntil(value)

    override var allowlistEnabled: Boolean
        get() = PreferenceHelper.getBoolean(PreferenceKeys.ALLOWLIST_ENABLED, false)
        set(value) = PreferenceHelper.putBoolean(PreferenceKeys.ALLOWLIST_ENABLED, value)

    override var musicRemovalEnabled: Boolean
        get() = PreferenceHelper.getBoolean(PreferenceKeys.MUSIC_REMOVAL_ENABLED, true)
        set(value) = PreferenceHelper.putBoolean(PreferenceKeys.MUSIC_REMOVAL_ENABLED, value)

    override var musicRemovalDisabledChannels: Set<String>
        get() =
            PreferenceHelper.getString(PreferenceKeys.MUSIC_REMOVAL_DISABLED_CHANNELS, "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        set(value) =
            PreferenceHelper.putString(
                PreferenceKeys.MUSIC_REMOVAL_DISABLED_CHANNELS,
                value.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(","),
            )

    override var screenPinEnabled: Boolean
        get() = PreferenceHelper.getBoolean(PreferenceKeys.SCREEN_PIN_ENABLED, false)
        set(value) = PreferenceHelper.putBoolean(PreferenceKeys.SCREEN_PIN_ENABLED, value)

    override var settingsPinLockEnabled: Boolean
        get() = PreferenceHelper.getBoolean(PreferenceKeys.SETTINGS_PIN_LOCK_ENABLED, false)
        set(value) = PreferenceHelper.putBoolean(PreferenceKeys.SETTINGS_PIN_LOCK_ENABLED, value)
}
