package com.github.libretube.util

import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ParentPinStorage {
    var pinHash: String?
    var pinSalt: String?
    var allowlistEnabled: Boolean
    var musicRemovalEnabled: Boolean
    var musicRemovalDisabledChannels: Set<String>
    var screenPinEnabled: Boolean
    var settingsPinLockEnabled: Boolean
}

sealed interface ProtectedAction {
    data object OpenSettings : ProtectedAction

    data class ChangeSubscription(val subscribe: Boolean) : ProtectedAction

    data class SetChannelMusicRemoval(val enabled: Boolean) : ProtectedAction

    data class SetMusicRemovalEnabled(val enabled: Boolean) : ProtectedAction

    data object BackupOrRestore : ProtectedAction

    data class SetAllowlistEnabled(val enabled: Boolean) : ProtectedAction
}

fun interface PinPromptAdapter {
    fun requestPinVerification(onVerified: () -> Unit, onCancelled: () -> Unit)
}

fun interface ScreenLockTaskAdapter {
    fun startLockTask()
}

class ParentPinStore(
    private val storage: ParentPinStorage,
) {
    private val secureRandom = SecureRandom()
    private val _musicRemovalRevision = MutableStateFlow(0L)
    val musicRemovalRevision: StateFlow<Long> = _musicRemovalRevision

    fun authorize(
        action: ProtectedAction,
        prompt: PinPromptAdapter,
        onCancelled: () -> Unit = {},
        onAuthorized: () -> Unit,
    ) {
        val requiresPin =
            when (action) {
                ProtectedAction.OpenSettings -> isSettingsPinLockEnabled()
                is ProtectedAction.ChangeSubscription -> !action.subscribe && isPinSet()
                is ProtectedAction.SetChannelMusicRemoval -> !action.enabled && isPinSet()
                is ProtectedAction.SetMusicRemovalEnabled -> !action.enabled && isPinSet()
                ProtectedAction.BackupOrRestore -> isPinSet()
                is ProtectedAction.SetAllowlistEnabled -> !action.enabled && isPinSet()
            }
        if (requiresPin) {
            prompt.requestPinVerification(onVerified = onAuthorized, onCancelled = onCancelled)
        } else {
            onAuthorized()
        }
    }

    fun isPinSet(): Boolean {
        return !storage.pinHash.isNullOrBlank() && !storage.pinSalt.isNullOrBlank()
    }

    fun isAllowlistEnabled(): Boolean = storage.allowlistEnabled

    fun isMusicRemovalEnabled(): Boolean = storage.musicRemovalEnabled

    fun setMusicRemovalEnabled(enabled: Boolean) {
        storage.musicRemovalEnabled = enabled
        _musicRemovalRevision.value++
    }

    fun isMusicRemovalDisabledForChannel(channelIdOrUrl: String?): Boolean {
        val normalized = normalizeChannelId(channelIdOrUrl) ?: return false
        return normalized in storage.musicRemovalDisabledChannels
    }

    fun isMusicRemovalEnabledForChannel(channelIdOrUrl: String?): Boolean {
        if (!storage.musicRemovalEnabled) return false
        val normalized = normalizeChannelId(channelIdOrUrl) ?: return true
        return normalized !in storage.musicRemovalDisabledChannels
    }

    fun setMusicRemovalEnabledForChannel(channelIdOrUrl: String, enabled: Boolean) {
        val normalized = normalizeChannelId(channelIdOrUrl) ?: return
        val updated = storage.musicRemovalDisabledChannels.toMutableSet()
        if (enabled) {
            updated.remove(normalized)
        } else {
            updated.add(normalized)
        }
        storage.musicRemovalDisabledChannels = updated
        _musicRemovalRevision.value++
    }

    fun toggleMusicRemovalForChannel(channelIdOrUrl: String): Boolean {
        val nextEnabled = !isMusicRemovalEnabledForChannel(channelIdOrUrl)
        setMusicRemovalEnabledForChannel(channelIdOrUrl, nextEnabled)
        return nextEnabled
    }

    private fun normalizeChannelId(channelIdOrUrl: String?): String? {
        if (channelIdOrUrl.isNullOrBlank()) return null
        val normalized = channelIdOrUrl.toID()
        return normalized.takeIf { it.isNotBlank() }
    }

    fun isScreenPinEnabled(): Boolean = storage.screenPinEnabled

    fun startScreenPin(lockTaskAdapter: ScreenLockTaskAdapter): Boolean {
        if (!isScreenPinEnabled()) return false
        lockTaskAdapter.startLockTask()
        return true
    }

    fun isSettingsPinLockEnabled(): Boolean {
        if (!isPinSet()) return false
        return storage.settingsPinLockEnabled
    }

    fun setPin(pin: String): Boolean {
        val normalized = pin.trim()
        if (normalized.isEmpty()) return false

        val saltBytes = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(saltBytes)
        val salt = Base64.getEncoder().encodeToString(saltBytes)
        val hash = hashPin(normalized, saltBytes)

        storage.pinSalt = salt
        storage.pinHash = hash
        return true
    }

    fun checkPin(pin: String): Boolean {
        val storedHash = storage.pinHash ?: return false
        val storedSalt = storage.pinSalt ?: return false
        val normalized = pin.trim()
        if (normalized.isEmpty()) return false

        val saltBytes =
            runCatching { Base64.getDecoder().decode(storedSalt) }.getOrNull() ?: return false
        val candidateHash = hashPin(normalized, saltBytes)
        return MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.UTF_8),
            candidateHash.toByteArray(Charsets.UTF_8),
        )
    }

    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!checkPin(currentPin)) return false
        return setPin(newPin)
    }

    private fun hashPin(pin: String, saltBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(saltBytes)
        val hashed = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashed)
    }

    companion object {
        private const val SALT_BYTES = 16
        val instance: ParentPinStore by lazy { ParentPinStore(PreferenceParentPinStorage) }
    }
}

object PreferenceParentPinStorage : ParentPinStorage {
    override var pinHash: String?
        get() = PreferenceHelper.getParentPinHash()
        set(value) = PreferenceHelper.setParentPinHash(value)

    override var pinSalt: String?
        get() = PreferenceHelper.getParentPinSalt()
        set(value) = PreferenceHelper.setParentPinSalt(value)

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
