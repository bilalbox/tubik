package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentPinStoreTest {

    private class FakePinStorage(
        override var pinHash: String? = null,
        override var pinSalt: String? = null,
        override var allowlistEnabled: Boolean = false,
        override var musicRemovalEnabled: Boolean = true,
        override var musicRemovalDisabledChannels: Set<String> = emptySet(),
        override var screenPinEnabled: Boolean = false,
        override var settingsPinLockEnabled: Boolean = false,
    ) : ParentPinStorage

    private class FakePinPromptAdapter(var approve: Boolean = true) : PinPromptAdapter {
        var promptCount: Int = 0
            private set

        override fun requestPinVerification(onVerified: () -> Unit, onCancelled: () -> Unit) {
            promptCount++
            if (approve) onVerified() else onCancelled()
        }
    }

    private class FakeScreenLockTaskAdapter(private val canStart: Boolean = true) :
        ScreenLockTaskAdapter {
        var startCount: Int = 0
            private set

        override fun startLockTask(): Boolean {
            startCount++
            return canStart
        }
    }

    @Test
    fun `when no PIN is set, settings are open by default, music removal is on, and switches reflect storage`() {
        val storage = FakePinStorage()
        val store = ParentPinStore(storage)

        assertFalse(store.isPinSet())
        assertFalse(store.isSettingsPinLockEnabled())
        assertFalse(store.isAllowlistEnabled())
        assertTrue(store.isMusicRemovalEnabled())
        assertFalse(store.isScreenPinEnabled())

        // Even if settingsPinLockEnabled is set in storage without a PIN, settings must not lock
        storage.settingsPinLockEnabled = true
        assertFalse(store.isSettingsPinLockEnabled())

        // Toggles changed in open settings take effect immediately without a PIN
        storage.musicRemovalEnabled = false
        storage.allowlistEnabled = true
        storage.screenPinEnabled = true
        assertFalse(store.isMusicRemovalEnabled())
        assertTrue(store.isAllowlistEnabled())
        assertTrue(store.isScreenPinEnabled())
    }

    @Test
    fun `settings lock requires both a PIN and the lock toggle, and setPin preserves existing toggles`() {
        val storage =
            FakePinStorage(
                allowlistEnabled = true,
                musicRemovalEnabled = false,
                screenPinEnabled = true,
                settingsPinLockEnabled = false,
            )
        val store = ParentPinStore(storage)

        assertTrue(store.setPin("1234"))
        assertTrue(store.isPinSet())
        assertFalse(store.isSettingsPinLockEnabled())
        assertTrue(store.isAllowlistEnabled())
        assertFalse(store.isMusicRemovalEnabled())
        assertTrue(store.isScreenPinEnabled())

        storage.settingsPinLockEnabled = true
        assertTrue(store.isSettingsPinLockEnabled())
    }

    @Test
    fun `setPin and checkPin accept right PIN, reject wrong PIN, and store only salted hash`() {
        val storage = FakePinStorage()
        val store = ParentPinStore(storage)

        assertFalse(store.checkPin("1234"))
        assertFalse(store.setPin("   "))
        assertFalse(store.isPinSet())

        assertTrue(store.setPin("1234"))
        assertTrue(store.isPinSet())
        assertTrue(store.checkPin("1234"))
        assertFalse(store.checkPin("9999"))
        assertFalse(store.checkPin(""))

        val firstHash = storage.pinHash
        val firstSalt = storage.pinSalt
        assertFalse(firstHash.isNullOrBlank())
        assertFalse(firstSalt.isNullOrBlank())
        assertFalse(firstHash!!.contains("1234"))

        // Setting the same PIN again uses a fresh random salt -> different stored hash
        assertTrue(store.setPin("1234"))
        assertFalse(storage.pinHash == firstHash)
        assertTrue(store.checkPin("1234"))
    }

    @Test
    fun `changePin requires valid current PIN and updates to new PIN`() {
        val storage = FakePinStorage()
        val store = ParentPinStore(storage)
        store.setPin("1234")

        // Wrong current PIN is rejected and does not change the PIN
        assertFalse(store.changePin(currentPin = "0000", newPin = "5678"))
        assertTrue(store.checkPin("1234"))
        assertFalse(store.checkPin("5678"))

        // Blank new PIN is rejected
        assertFalse(store.changePin(currentPin = "1234", newPin = "   "))
        assertTrue(store.checkPin("1234"))

        // Correct current PIN changes the PIN
        assertTrue(store.changePin(currentPin = "1234", newPin = "5678"))
        assertFalse(store.checkPin("1234"))
        assertTrue(store.checkPin("5678"))
    }

    @Test
    fun `per-channel music removal allows opt-out when global is on, and global off is master off`() {
        val storage = FakePinStorage(musicRemovalEnabled = true)
        val store = ParentPinStore(storage)

        // Global on: channels are enabled by default, including unknown/blank channels
        assertTrue(store.isMusicRemovalEnabledForChannel(null))
        assertTrue(store.isMusicRemovalEnabledForChannel(""))
        assertTrue(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_1"))
        assertFalse(store.isMusicRemovalDisabledForChannel("UC_CHANNEL_1"))

        // Opting out via URL normalizes to channel ID
        store.setMusicRemovalEnabledForChannel(
            "https://www.youtube.com/channel/UC_CHANNEL_1",
            enabled = false,
        )
        assertTrue(store.isMusicRemovalDisabledForChannel("UC_CHANNEL_1"))
        assertFalse(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_1"))
        assertFalse(store.isMusicRemovalEnabledForChannel("/channel/UC_CHANNEL_1"))
        assertTrue(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_2"))

        // Global off acts as master off switch for all channels, while preserving opt-out state
        storage.musicRemovalEnabled = false
        assertFalse(store.isMusicRemovalEnabledForChannel(null))
        assertFalse(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_1"))
        assertFalse(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_2"))
        assertTrue(store.isMusicRemovalDisabledForChannel("UC_CHANNEL_1"))

        // Turning global back on restores per-channel opt-out, and toggling re-enables
        storage.musicRemovalEnabled = true
        assertFalse(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_1"))
        assertTrue(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_2"))
        assertTrue(store.toggleMusicRemovalForChannel("/channel/UC_CHANNEL_1"))
        assertTrue(store.isMusicRemovalEnabledForChannel("UC_CHANNEL_1"))
        assertFalse(store.isMusicRemovalDisabledForChannel("UC_CHANNEL_1"))
    }

    @Test
    fun `authorize OpenSettings requires PIN only when settings lock and PIN are both set`() {
        val storage = FakePinStorage(settingsPinLockEnabled = true)
        val store = ParentPinStore(storage)
        val prompt = FakePinPromptAdapter(approve = false)
        var authorized = 0
        var cancelled = 0

        // No PIN set yet: opens without prompting even when storage flag is true
        store.authorize(
            ProtectedAction.OpenSettings,
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(1, authorized)
        assertEquals(0, cancelled)

        // PIN set, settings lock off: opens without prompting
        store.setPin("1234")
        storage.settingsPinLockEnabled = false
        store.authorize(
            ProtectedAction.OpenSettings,
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(2, authorized)
        assertEquals(0, cancelled)

        // PIN set, settings lock on, cancelled prompt: cancels and does not authorize
        storage.settingsPinLockEnabled = true
        store.authorize(
            ProtectedAction.OpenSettings,
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(1, prompt.promptCount)
        assertEquals(2, authorized)
        assertEquals(1, cancelled)

        // PIN set, settings lock on, approved prompt: authorizes
        prompt.approve = true
        store.authorize(
            ProtectedAction.OpenSettings,
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(2, prompt.promptCount)
        assertEquals(3, authorized)
        assertEquals(1, cancelled)
    }

    @Test
    fun `authorize ChangeSubscription gates unsubscribing only when PIN is set`() {
        val store = ParentPinStore(FakePinStorage())
        val prompt = FakePinPromptAdapter(approve = false)
        var authorized = 0
        var cancelled = 0

        // No PIN set: unsubscribing authorizes immediately without prompting
        store.authorize(
            ProtectedAction.ChangeSubscription(subscribe = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(1, authorized)

        // PIN set: subscribing still requires no prompt
        store.setPin("1234")
        store.authorize(
            ProtectedAction.ChangeSubscription(subscribe = true),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(2, authorized)

        // PIN set: unsubscribing prompts and cancels when rejected
        store.authorize(
            ProtectedAction.ChangeSubscription(subscribe = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(1, prompt.promptCount)
        assertEquals(2, authorized)
        assertEquals(1, cancelled)

        // PIN set: unsubscribing authorizes when verified
        prompt.approve = true
        store.authorize(
            ProtectedAction.ChangeSubscription(subscribe = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(2, prompt.promptCount)
        assertEquals(3, authorized)
        assertEquals(1, cancelled)
    }

    @Test
    fun `authorize SetChannelMusicRemoval gates enabling Channel Opt-Out only when PIN is set`() {
        val store = ParentPinStore(FakePinStorage())
        val prompt = FakePinPromptAdapter(approve = false)
        var authorized = 0
        var cancelled = 0

        // No PIN set: turning Music Removal off for a channel authorizes without prompting
        store.authorize(
            ProtectedAction.SetChannelMusicRemoval(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(1, authorized)

        // PIN set: re-enabling Music Removal (removing Channel Opt-Out) requires no prompt
        store.setPin("1234")
        store.authorize(
            ProtectedAction.SetChannelMusicRemoval(enabled = true),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(2, authorized)

        // PIN set: enabling Channel Opt-Out prompts and cancels when rejected
        store.authorize(
            ProtectedAction.SetChannelMusicRemoval(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(1, prompt.promptCount)
        assertEquals(2, authorized)
        assertEquals(1, cancelled)

        // PIN set: enabling Channel Opt-Out authorizes when verified
        prompt.approve = true
        store.authorize(
            ProtectedAction.SetChannelMusicRemoval(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(2, prompt.promptCount)
        assertEquals(3, authorized)
        assertEquals(1, cancelled)
    }

    @Test
    fun `authorize BackupOrRestore requires PIN whenever PIN is set`() {
        val store = ParentPinStore(FakePinStorage())
        val prompt = FakePinPromptAdapter(approve = false)
        var authorized = 0
        var cancelled = 0

        // No PIN set: authorizes immediately without prompting
        store.authorize(
            ProtectedAction.BackupOrRestore,
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(1, authorized)

        // PIN set, prompt cancelled: cancels and does not authorize
        store.setPin("1234")
        store.authorize(
            ProtectedAction.BackupOrRestore,
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(1, prompt.promptCount)
        assertEquals(1, authorized)
        assertEquals(1, cancelled)

        // PIN set, prompt approved: authorizes
        prompt.approve = true
        store.authorize(
            ProtectedAction.BackupOrRestore,
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(2, prompt.promptCount)
        assertEquals(2, authorized)
        assertEquals(1, cancelled)
    }

    @Test
    fun `authorize SetAllowlistEnabled gates turning Allowlist off only when PIN is set`() {
        val store = ParentPinStore(FakePinStorage())
        val prompt = FakePinPromptAdapter(approve = false)
        var authorized = 0
        var cancelled = 0

        // No PIN set: turning Allowlist off authorizes without prompting
        store.authorize(
            ProtectedAction.SetAllowlistEnabled(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(1, authorized)

        // PIN set: turning Allowlist on requires no prompt
        store.setPin("1234")
        store.authorize(
            ProtectedAction.SetAllowlistEnabled(enabled = true),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(2, authorized)

        // PIN set: turning Allowlist off prompts and cancels when rejected
        store.authorize(
            ProtectedAction.SetAllowlistEnabled(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(1, prompt.promptCount)
        assertEquals(2, authorized)
        assertEquals(1, cancelled)

        // PIN set: turning Allowlist off authorizes when verified
        prompt.approve = true
        store.authorize(
            ProtectedAction.SetAllowlistEnabled(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(2, prompt.promptCount)
        assertEquals(3, authorized)
        assertEquals(1, cancelled)
    }

    @Test
    fun `authorize SetMusicRemovalEnabled gates turning Music Removal off only when PIN is set`() {
        val store = ParentPinStore(FakePinStorage())
        val prompt = FakePinPromptAdapter(approve = false)
        var authorized = 0
        var cancelled = 0

        // No PIN set: turning Music Removal off authorizes without prompting
        store.authorize(
            ProtectedAction.SetMusicRemovalEnabled(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(1, authorized)

        // PIN set: turning Music Removal on requires no prompt
        store.setPin("1234")
        store.authorize(
            ProtectedAction.SetMusicRemovalEnabled(enabled = true),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(0, prompt.promptCount)
        assertEquals(2, authorized)

        // PIN set: turning Music Removal off prompts and cancels when rejected
        store.authorize(
            ProtectedAction.SetMusicRemovalEnabled(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(1, prompt.promptCount)
        assertEquals(2, authorized)
        assertEquals(1, cancelled)

        // PIN set: turning Music Removal off authorizes when verified
        prompt.approve = true
        store.authorize(
            ProtectedAction.SetMusicRemovalEnabled(enabled = false),
            prompt,
            onAuthorized = { authorized++ },
            onCancelled = { cancelled++ },
        )
        assertEquals(2, prompt.promptCount)
        assertEquals(3, authorized)
        assertEquals(1, cancelled)
    }

    @Test
    fun `musicRemovalRevision increments on valid Channel Opt-Out updates`() {
        val store = ParentPinStore(FakePinStorage(musicRemovalEnabled = true))

        assertEquals(0L, store.musicRemovalRevision.value)

        store.setMusicRemovalEnabledForChannel("", enabled = false)
        assertEquals(0L, store.musicRemovalRevision.value)

        store.setMusicRemovalEnabledForChannel("UC_CHANNEL_1", enabled = false)
        assertEquals(1L, store.musicRemovalRevision.value)

        store.toggleMusicRemovalForChannel("/channel/UC_CHANNEL_1")
        assertEquals(2L, store.musicRemovalRevision.value)
    }

    @Test
    fun `startScreenPin invokes lock-task adapter only when Screen Pin is enabled`() {
        val storage = FakePinStorage(screenPinEnabled = false)
        val store = ParentPinStore(storage)
        val lockTask = FakeScreenLockTaskAdapter()

        assertFalse(store.startScreenPin(lockTask))
        assertEquals(0, lockTask.startCount)

        store.setScreenPinEnabled(true)
        assertTrue(ParentPinStore(storage).startScreenPin(lockTask))
        assertEquals(1, lockTask.startCount)

        store.setScreenPinEnabled(false)
        assertFalse(ParentPinStore(storage).startScreenPin(lockTask))
        assertEquals(1, lockTask.startCount)
    }

    @Test
    fun `startScreenPin reports when Android cannot start lock task`() {
        val store = ParentPinStore(FakePinStorage(screenPinEnabled = true))
        val lockTask = FakeScreenLockTaskAdapter(canStart = false)

        assertFalse(store.startScreenPin(lockTask))
        assertEquals(1, lockTask.startCount)
    }
}
