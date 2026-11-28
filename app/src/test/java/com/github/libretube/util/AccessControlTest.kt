package com.github.libretube.util

import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.obj.WatchHistoryEntry
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.LocalPlaylistItem
import com.github.libretube.db.obj.Series
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessControlTest {

    private class CountingPrompt(
        private val approve: Boolean = true,
    ) : PinPromptAdapter {
        var promptCount = 0
            private set

        override fun requestPinVerification(onVerified: () -> Unit, onCancelled: () -> Unit) {
            promptCount++
            if (approve) onVerified() else onCancelled()
        }
    }

    @Test
    fun `setPin stores salted hash, checkPin verifies trimmed input, and changePin rotates PIN`() {
        val storage = InMemoryAccessControlStorage()
        val ac = AccessControl(storage)

        assertFalse(ac.policy.isPinSet)
        assertFalse(ac.setPin("   "))
        assertFalse(ac.policy.isPinSet)

        assertTrue(ac.setPin(" 1234 "))
        assertTrue(ac.policy.isPinSet)
        assertNotEquals("1234", storage.pinHash)
        assertTrue(storage.pinHash.orEmpty().startsWith("pbkdf2_sha256$120000$"))
        assertTrue(ac.checkPin("1234"))
        assertTrue(ac.checkPin(" 1234 "))
        assertFalse(ac.checkPin("0000"))
        assertFalse(ac.checkPin(""))

        assertFalse(ac.changePin("9999", "5678"))
        assertTrue(ac.checkPin("1234"))

        assertTrue(ac.changePin("1234", "5678"))
        assertFalse(ac.checkPin("1234"))
        assertTrue(ac.checkPin("5678"))
    }

    @Test
    fun `checkPin verifies legacy SHA-256 hash and transparently upgrades storage to PBKDF2`() {
        val saltBytes = ByteArray(16) { it.toByte() }
        val encodedSalt = Base64.getEncoder().encodeToString(saltBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(saltBytes)
        val legacyHash =
            Base64.getEncoder().encodeToString(digest.digest("1234".toByteArray(Charsets.UTF_8)))

        val storage = InMemoryAccessControlStorage(pinHash = legacyHash, pinSalt = encodedSalt)
        val ac = AccessControl(storage)

        assertFalse(ac.checkPin("9999"))
        assertEquals(legacyHash, storage.pinHash)

        assertTrue(ac.checkPin("1234"))
        assertTrue(storage.pinHash.orEmpty().startsWith("pbkdf2_sha256$120000$"))
        assertNotEquals(legacyHash, storage.pinHash)
        assertTrue(ac.checkPin("1234"))
    }

    @Test
    fun `verifyPin enforces exponential backoff after 5 failed attempts and resets on success`() {
        var now = 1_000_000L
        val storage = InMemoryAccessControlStorage()
        val ac = AccessControl(storage, clockMillis = { now }).apply { setPin("1234") }

        for (attempt in 1..4) {
            assertEquals(PinVerificationResult.Incorrect(attempt), ac.verifyPin("0000"))
        }
        assertEquals(PinVerificationResult.LockedOut(30_000L), ac.verifyPin("0000"))

        now += 10_000L
        assertEquals(PinVerificationResult.LockedOut(20_000L), ac.verifyPin("1234"))
        assertFalse(ac.checkPin("1234"))

        now += 20_000L
        assertEquals(PinVerificationResult.LockedOut(60_000L), ac.verifyPin("0000"))

        now += 60_000L
        assertEquals(PinVerificationResult.Verified, ac.verifyPin("1234"))
        assertEquals(0, storage.failedPinAttempts)
        assertEquals(0L, storage.pinLockedUntilEpochMillis)

        assertEquals(PinVerificationResult.Incorrect(1), ac.verifyPin("0000"))
        assertTrue(ac.setPin("5678"))
        assertEquals(0, storage.failedPinAttempts)
        assertEquals(0L, storage.pinLockedUntilEpochMillis)
    }

    @Test
    fun `apply gates weakening changes behind Parent PIN and leaves state untouched on cancel`() {
        val storage =
            InMemoryAccessControlStorage(
                allowlistEnabled = true,
                musicRemovalEnabled = true,
                screenPinEnabled = true,
                settingsPinLockEnabled = true,
            )
        val ac = AccessControl(storage).apply { setPin("1234") }
        val deny = CountingPrompt(approve = false)
        var cancelled = 0

        listOf(
                PolicyChange.SetAllowlist(false),
                PolicyChange.SetMusicRemoval(false),
                PolicyChange.SetChannelMusicRemoval("UC_CH1", enabled = false),
                PolicyChange.SetScreenPin(false),
                PolicyChange.SetSettingsPinLock(false),
            )
            .forEach { ac.apply(it, deny, onCancelled = { cancelled++ }) }

        assertEquals(5, deny.promptCount)
        assertEquals(5, cancelled)
        assertTrue(ac.policy.allowlistEnabled)
        assertTrue(ac.policy.musicRemovalEnabled)
        assertTrue(ac.isMusicRemovalEnabledForChannel("UC_CH1"))
        assertTrue(ac.policy.screenPinEnabled)
        assertTrue(ac.policy.settingsPinLockEnabled)
        assertEquals(0L, ac.musicRemovalRevision.value)
    }

    @Test
    fun `apply persists weakening changes on approval and skips PIN for strengthening changes`() {
        val storage =
            InMemoryAccessControlStorage(
                allowlistEnabled = true,
                musicRemovalEnabled = true,
                screenPinEnabled = true,
                settingsPinLockEnabled = true,
            )
        val ac = AccessControl(storage).apply { setPin("1234") }
        val allow = CountingPrompt(approve = true)

        var appliedAllowlist: Boolean? = null
        ac.apply(PolicyChange.SetAllowlist(false), allow) { appliedAllowlist = it.allowlistEnabled }
        ac.apply(
            PolicyChange.SetChannelMusicRemoval("https://www.youtube.com/channel/UC_CH1", false),
            allow,
        )
        ac.apply(PolicyChange.SetMusicRemoval(false), allow)
        ac.apply(PolicyChange.SetScreenPin(false), allow)
        ac.apply(PolicyChange.SetSettingsPinLock(false), allow)

        assertEquals(5, allow.promptCount)
        assertEquals(false, appliedAllowlist)
        assertFalse(storage.allowlistEnabled)
        assertTrue(ac.isMusicRemovalDisabledForChannel("UC_CH1"))
        assertFalse(storage.musicRemovalEnabled)
        assertFalse(storage.screenPinEnabled)
        assertFalse(storage.settingsPinLockEnabled)
        assertEquals(2L, ac.musicRemovalRevision.value)

        val noPrompt = CountingPrompt(approve = false)
        listOf(
                PolicyChange.SetAllowlist(true),
                PolicyChange.SetMusicRemoval(true),
                PolicyChange.SetChannelMusicRemoval("UC_CH1", true),
                PolicyChange.SetScreenPin(true),
                PolicyChange.SetSettingsPinLock(true),
            )
            .forEach { ac.apply(it, noPrompt) }

        assertEquals(0, noPrompt.promptCount)
        assertTrue(ac.policy.allowlistEnabled)
        assertTrue(ac.policy.musicRemovalEnabled)
        assertTrue(ac.isMusicRemovalEnabledForChannel("UC_CH1"))
        assertTrue(ac.policy.screenPinEnabled)
        assertTrue(ac.policy.settingsPinLockEnabled)
    }

    @Test
    fun `apply runs without prompting when no Parent PIN is set`() {
        val ac =
            AccessControl(
                InMemoryAccessControlStorage(allowlistEnabled = true, screenPinEnabled = true),
            )
        val prompt = CountingPrompt(approve = false)

        ac.apply(PolicyChange.SetAllowlist(false), prompt)
        ac.apply(PolicyChange.SetScreenPin(false), prompt)
        assertEquals(0, prompt.promptCount)
        assertFalse(ac.policy.allowlistEnabled)
        assertFalse(ac.policy.screenPinEnabled)
    }

    @Test
    fun `authorize gates OpenSettings, unsubscribe, and BackupOrRestore according to PIN state`() {
        val storage = InMemoryAccessControlStorage(settingsPinLockEnabled = true)
        val ac = AccessControl(storage)

        assertFalse(ac.policy.settingsPinLockEnabled)
        val noPinPrompt = CountingPrompt(approve = false)
        var authorized = 0
        ac.authorize(ProtectedAction.OpenSettings, noPinPrompt) { authorized++ }
        ac.authorize(ProtectedAction.ChangeSubscription(subscribe = false), noPinPrompt) {
            authorized++
        }
        ac.authorize(ProtectedAction.BackupOrRestore, noPinPrompt) { authorized++ }
        assertEquals(0, noPinPrompt.promptCount)
        assertEquals(3, authorized)

        ac.setPin("2468")
        assertTrue(ac.policy.settingsPinLockEnabled)

        val prompt = CountingPrompt(approve = true)
        ac.authorize(ProtectedAction.ChangeSubscription(subscribe = true), prompt) { authorized++ }
        assertEquals(0, prompt.promptCount)

        ac.authorize(ProtectedAction.OpenSettings, prompt) { authorized++ }
        ac.authorize(ProtectedAction.ChangeSubscription(subscribe = false), prompt) { authorized++ }
        ac.authorize(ProtectedAction.BackupOrRestore, prompt) { authorized++ }
        assertEquals(3, prompt.promptCount)
        assertEquals(7, authorized)
    }

    @Test
    fun `policy exposes consistent UI capability flags for Allowlist and Music Removal`() {
        val storage =
            InMemoryAccessControlStorage(allowlistEnabled = false, musicRemovalEnabled = false)
        val ac = AccessControl(storage)

        assertTrue(ac.policy.canModifySubscriptions)
        assertTrue(ac.policy.canViewComments)
        assertTrue(ac.policy.canViewRelatedStreams(true))
        assertFalse(ac.policy.canViewRelatedStreams(false))
        assertFalse(ac.policy.areSearchSuggestionsEnabled(false))
        assertFalse(ac.policy.shouldHideMusicSearchChips)

        storage.musicRemovalEnabled = true
        assertTrue(ac.policy.shouldHideMusicSearchChips)

        storage.musicRemovalEnabled = false
        storage.allowlistEnabled = true
        assertFalse(ac.policy.canModifySubscriptions)
        assertFalse(ac.policy.canViewComments)
        assertFalse(ac.policy.canViewRelatedStreams(true))
        assertTrue(ac.policy.areSearchSuggestionsEnabled(false))
        assertTrue(ac.policy.shouldHideMusicSearchChips)
    }

    @Test
    fun `snapshot filters channels, streams, and playlists`() = runBlocking {
        val ac =
            AccessControl(
                storage = InMemoryAccessControlStorage(allowlistEnabled = true),
                getAllowedChannelIds = { setOf("/channel/UC_ALLOWED_1", "UC_ALLOWED_2") },
            )
        val snap = ac.snapshot()
        assertTrue(snap.allowlistEnabled)
        assertTrue(snap.isChannelAllowed("UC_ALLOWED_1"))
        assertTrue(snap.isChannelAllowed("https://www.youtube.com/channel/UC_ALLOWED_2"))
        assertFalse(snap.isChannelAllowed("UC_BLOCKED"))
        assertFalse(snap.isChannelAllowed(null))

        val allowedVideo =
            StreamItem(url = "v1", type = StreamItem.TYPE_STREAM, uploaderUrl = "UC_ALLOWED_1")
        val blockedVideo =
            StreamItem(url = "v2", type = StreamItem.TYPE_STREAM, uploaderUrl = "UC_BLOCKED")
        val allowedChannel = StreamItem(url = "UC_ALLOWED_2", type = StreamItem.TYPE_CHANNEL)
        assertEquals(
            listOf("v1", "UC_ALLOWED_2"),
            snap.filterStreamItems(listOf(allowedVideo, blockedVideo, allowedChannel)).map {
                it.url
            },
        )
        assertTrue(
            snap.isContentItemAllowed(
                ContentItem(url = "UC_ALLOWED_1", type = StreamItem.TYPE_CHANNEL, thumbnail = ""),
            ),
        )
        assertEquals(
            listOf("v1"),
            snap
                .filterPlaylist(Playlist(relatedStreams = listOf(allowedVideo, blockedVideo)))
                .relatedStreams
                .map { it.url },
        )
    }

    @Test
    fun `snapshot filters local watch history, playlist items, bookmarks, and downloads`() =
        runBlocking {
            val snap =
                AccessControl(
                        storage = InMemoryAccessControlStorage(allowlistEnabled = true),
                        getAllowedChannelIds = { setOf("UC_ALLOWED_1") },
                    )
                    .snapshot()

            val history =
                listOf(
                    WatchHistoryEntry(
                        metadata = WatchHistoryEntryMetadata("v1", 1L, false),
                        video = StreamItem(url = "v1", uploaderUrl = "UC_ALLOWED_1"),
                    ),
                    WatchHistoryEntry(
                        metadata = WatchHistoryEntryMetadata("v2", 2L, false),
                        video = StreamItem(url = "v2", uploaderUrl = "UC_BLOCKED"),
                    ),
                )
            assertEquals(
                listOf("v1"),
                snap.filterWatchHistoryEntries(history).map { it.metadata.videoId },
            )

            val localItems =
                listOf(
                    LocalPlaylistItem(playlistId = 1, videoId = "v1", uploaderUrl = "UC_ALLOWED_1"),
                    LocalPlaylistItem(playlistId = 1, videoId = "v2", uploaderUrl = "UC_BLOCKED"),
                )
            assertEquals(listOf("v1"), snap.filterLocalPlaylistItems(localItems).map { it.videoId })

            val bookmarks =
                listOf(
                    Series(playlistId = "p1", uploaderUrl = "UC_ALLOWED_1"),
                    Series(playlistId = "p2", uploaderUrl = "UC_BLOCKED"),
                )
            assertEquals(
                listOf("p1"),
                snap.filterSeries(bookmarks).map { it.playlistId },
            )

            val downloads =
                listOf(
                    DownloadWithItems(
                        Download(videoId = "v1", uploaderUrl = "UC_ALLOWED_1"),
                        emptyList(),
                    ),
                    DownloadWithItems(
                        Download(videoId = "v2", uploaderUrl = "UC_BLOCKED"),
                        emptyList(),
                    ),
                )
            assertEquals(
                listOf("v1"),
                snap.filterDownloads(downloads).map { it.download.videoId },
            )
        }

    @Test
    fun `snapshot falls back to subscriptions on non-cancellation errors and rethrows CancellationException`() =
        runBlocking {
            val fallbackAc =
                AccessControl(
                    storage = InMemoryAccessControlStorage(allowlistEnabled = true),
                    getAllowedChannelIds = { error("DB busy") },
                    getAllowedSubscriptions = {
                        listOf(
                            Subscription(
                                url = "/channel/UC_FALLBACK",
                                name = "Fallback",
                                verified = true,
                            ),
                        )
                    },
                )
            val snap = fallbackAc.snapshot()
            assertTrue(snap.isChannelAllowed("UC_FALLBACK"))
            assertFalse(snap.isChannelAllowed("UC_OTHER"))

            var fallbackInvoked = false
            val cancelledAc =
                AccessControl(
                    storage = InMemoryAccessControlStorage(allowlistEnabled = true),
                    getAllowedChannelIds = { throw CancellationException("Cancelled") },
                    getAllowedSubscriptions = {
                        fallbackInvoked = true
                        emptyList()
                    },
                )
            try {
                cancelledAc.snapshot()
                fail("Expected CancellationException to propagate")
            } catch (_: CancellationException) {
                // Expected
            }
            assertFalse(fallbackInvoked)
        }
}
