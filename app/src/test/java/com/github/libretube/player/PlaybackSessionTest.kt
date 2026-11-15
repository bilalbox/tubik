package com.github.libretube.player

import com.github.libretube.util.ParentPinStorage
import com.github.libretube.util.ParentPinStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionTest {

    private class FakePinStorage(
        override var pinHash: String? = null,
        override var pinSalt: String? = null,
        override var allowlistEnabled: Boolean = false,
        override var musicRemovalEnabled: Boolean = true,
        override var musicRemovalDisabledChannels: Set<String> = emptySet(),
        override var screenPinEnabled: Boolean = false,
        override var settingsPinLockEnabled: Boolean = false,
    ) : ParentPinStorage

    private fun newSession(
        initialPreferredSpeed: Float = 1.0f,
        musicRemovalEnabled: Boolean = true,
        disabledChannels: Set<String> = emptySet(),
    ): Triple<PlaybackSession, ParentPinStore, () -> Float> {
        var persistedSpeed = initialPreferredSpeed
        val store =
            ParentPinStore(
                FakePinStorage(
                    musicRemovalEnabled = musicRemovalEnabled,
                    musicRemovalDisabledChannels = disabledChannels,
                ),
            )
        val session =
            PlaybackSession(
                isMusicRemovalEnabledForChannel = store::isMusicRemovalEnabledForChannel,
                musicRemovalRevision = store.musicRemovalRevision,
                readPreferredSpeed = { persistedSpeed },
                writePreferredSpeed = { persistedSpeed = it },
            )
        return Triple(session, store, { persistedSpeed })
    }

    @Test
    fun `sessionToken guards track binding and clearing so outgoing service never clears newer track`() {
        val (session, _, _) = newSession()
        val outgoingToken = session.nextSessionToken()
        val newerToken = session.nextSessionToken()

        assertTrue(
            session.bindTrack(
                sessionToken = outgoingToken,
                videoId = "video-1",
                channelId = "https://www.youtube.com/channel/UC_OLD",
            ),
        )
        assertEquals("video-1", session.state.value.videoId)
        assertEquals("UC_OLD", session.state.value.channelId)
        assertEquals(outgoingToken, session.state.value.sessionToken)

        assertTrue(
            session.bindTrack(
                sessionToken = newerToken,
                videoId = "video-2",
                channelId = "/channel/UC_NEW",
            ),
        )
        assertEquals("video-2", session.state.value.videoId)
        assertEquals("UC_NEW", session.state.value.channelId)
        assertEquals(newerToken, session.state.value.sessionToken)

        // Outgoing service destroying or finishing late must neither clear nor overwrite newer
        // track
        assertFalse(session.clearTrack(outgoingToken))
        assertFalse(
            session.bindTrack(
                sessionToken = outgoingToken,
                videoId = "video-stale",
                channelId = "UC_OLD",
            ),
        )
        assertEquals("video-2", session.state.value.videoId)
        assertEquals("UC_NEW", session.state.value.channelId)
        assertEquals(newerToken, session.state.value.sessionToken)

        // Active service clears cleanly
        assertTrue(session.clearTrack(newerToken))
        assertNull(session.state.value.videoId)
        assertNull(session.state.value.channelId)
        assertNull(session.state.value.sessionToken)
    }

    @Test
    fun `preserves Preferred Speed across 1_5x clamped Music Removal channels and live Channel Opt-Out`() {
        val (session, store, readPersisted) =
            newSession(
                initialPreferredSpeed = 2.0f,
                musicRemovalEnabled = true,
                disabledChannels = setOf("UC_OPTED_OUT"),
            )
        val token = session.nextSessionToken()

        // Music Removal channel clamps effective speed to 1.5x while preserving 2.0x Preferred
        // Speed
        session.bindTrack(token, videoId = "v1", channelId = "UC_MUSIC_REMOVAL")
        assertTrue(session.state.value.isMusicRemovalEnabled)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, readPersisted(), 0.001f)

        // Live Channel Opt-Out mid-video immediately uncaps to 2.0x Preferred Speed
        store.setMusicRemovalEnabledForChannel("UC_MUSIC_REMOVAL", enabled = false)
        assertFalse(session.state.value.isMusicRemovalEnabled)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(2.0f, session.state.value.effectiveSpeed, 0.001f)

        // Re-enabling Music Removal mid-video clamps back to 1.5x without clobbering Preferred
        // Speed
        store.setMusicRemovalEnabledForChannel("UC_MUSIC_REMOVAL", enabled = true)
        assertTrue(session.state.value.isMusicRemovalEnabled)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)

        // Updating Preferred Speed while on a Music Removal channel persists 2.5x and clamps
        // effective to 1.5x
        assertEquals(1.5f, session.setPreferredSpeed(2.5f), 0.001f)
        assertEquals(2.5f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.5f, readPersisted(), 0.001f)

        // Leaving to an opted-out channel restores 2.5x effective speed
        session.bindTrack(token, videoId = "v2", channelId = "UC_OPTED_OUT")
        assertFalse(session.state.value.isMusicRemovalEnabled)
        assertEquals(2.5f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(2.5f, session.state.value.effectiveSpeed, 0.001f)
    }

    @Test
    fun `CATEGORY_MUSIC and transient track overrides play at 1f without clobbering Preferred Speed`() {
        val (session, store, readPersisted) =
            newSession(
                initialPreferredSpeed = 2.0f,
                musicRemovalEnabled = true,
                disabledChannels = setOf("UC_OPTED_OUT"),
            )
        val token = session.nextSessionToken()

        // Track initially binds before Streams arrives, then updates with CATEGORY_MUSIC
        session.bindTrack(token, videoId = "v_music", channelId = "UC_OPTED_OUT", category = null)
        assertEquals(2.0f, session.state.value.effectiveSpeed, 0.001f)

        session.bindTrack(
            token,
            videoId = "v_music",
            channelId = "UC_OPTED_OUT",
            category = com.github.libretube.api.obj.Streams.CATEGORY_MUSIC,
        )
        assertTrue(session.state.value.isMusicCategory)
        assertEquals(1.0f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(2.0f, readPersisted(), 0.001f)

        // Toggling Channel Opt-Out during CATEGORY_MUSIC keeps 1.0f override
        store.setMusicRemovalEnabledForChannel("UC_OPTED_OUT", enabled = true)
        assertEquals(1.0f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)

        // Moving to a non-music track restores Preferred Speed (subject to 1.5x Music Removal cap)
        session.bindTrack(
            token,
            videoId = "v_talk",
            channelId = "UC_OPTED_OUT",
            category = "Education",
        )
        assertFalse(session.state.value.isMusicCategory)
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)

        // Transient track override (e.g. livestream end) also leaves Preferred Speed untouched
        session.overrideTrackSpeed(1.0f)
        assertEquals(1.0f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(2.0f, readPersisted(), 0.001f)

        // Replaying the same videoId in the same session clears transient override
        session.bindTrack(token, videoId = "v_talk", channelId = "UC_OPTED_OUT")
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)
    }

    @Test
    fun `long-press fast-forward respects 1_5x cap and restores prior speed without clobbering Preferred Speed`() {
        val (session, _, readPersisted) =
            newSession(
                initialPreferredSpeed = 1.25f,
                musicRemovalEnabled = true,
                disabledChannels = setOf("UC_OPTED_OUT"),
            )
        val token = session.nextSessionToken()

        // Opted-out channel: doubles 1.25x -> 2.5x and restores 1.25x on release
        session.bindTrack(token, videoId = "v1", channelId = "UC_OPTED_OUT")
        assertEquals(2.5f, session.beginLongPressFastForward(), 0.001f)
        assertTrue(session.state.value.isLongPressFastForwardActive)
        assertEquals(2.5f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(1.25f, session.state.value.preferredSpeed, 0.001f)

        // Repeated begin while already active does not double again
        assertEquals(2.5f, session.beginLongPressFastForward(), 0.001f)

        assertEquals(1.25f, session.endLongPressFastForward(), 0.001f)
        assertFalse(session.state.value.isLongPressFastForwardActive)
        assertEquals(1.25f, session.state.value.effectiveSpeed, 0.001f)

        // Music Removal channel: 1.0x fast-forward never bypasses 1.5x cap, and restores 1.0x
        session.setPreferredSpeed(1.0f)
        session.bindTrack(token, videoId = "v2", channelId = "UC_MUSIC_REMOVAL")
        assertEquals(1.5f, session.beginLongPressFastForward(), 0.001f)
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(1.0f, session.endLongPressFastForward(), 0.001f)
        assertEquals(1.0f, session.state.value.effectiveSpeed, 0.001f)

        // Music Removal channel with Preferred Speed 2.0x: stays at 1.5x and keeps Preferred Speed
        // 2.0x
        session.setPreferredSpeed(2.0f)
        assertEquals(1.5f, session.beginLongPressFastForward(), 0.001f)
        assertEquals(1.5f, session.endLongPressFastForward(), 0.001f)
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)

        // CATEGORY_MUSIC on opted-out channel: fast-forwards 1.0x -> 2.0x and restores 1.0x,
        // keeping Preferred Speed 2.0x
        session.bindTrack(
            token,
            videoId = "v3",
            channelId = "UC_OPTED_OUT",
            category = com.github.libretube.api.obj.Streams.CATEGORY_MUSIC,
        )
        assertEquals(1.0f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, session.beginLongPressFastForward(), 0.001f)
        assertEquals(1.0f, session.endLongPressFastForward(), 0.001f)
        assertEquals(1.0f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(2.0f, session.state.value.preferredSpeed, 0.001f)
        assertEquals(2.0f, readPersisted(), 0.001f)
    }

    @Test
    fun `overload mute state is sessionToken-guarded, reflects Channel Opt-Out, and resets on track change or clear`() {
        val (session, store, _) = newSession(musicRemovalEnabled = true)
        val token1 = session.nextSessionToken()
        val token2 = session.nextSessionToken()

        session.bindTrack(token1, videoId = "v1", channelId = "UC_CH1")
        assertFalse(session.state.value.isOverloadMuted)

        assertTrue(session.setOverloadMuted(token1, true))
        assertTrue(session.state.value.isOverloadMuted)

        // Opting out the active channel hides overload mute immediately; re-enabling restores until
        // cleared
        store.setMusicRemovalEnabledForChannel("UC_CH1", enabled = false)
        assertFalse(session.state.value.isOverloadMuted)
        store.setMusicRemovalEnabledForChannel("UC_CH1", enabled = true)
        assertTrue(session.state.value.isOverloadMuted)

        // Newer session resets overload mute; outgoing service reset cannot clear newer session's
        // mute
        session.bindTrack(token2, videoId = "v2", channelId = "UC_CH2")
        assertFalse(session.state.value.isOverloadMuted)
        assertTrue(session.setOverloadMuted(token2, true))
        assertTrue(session.state.value.isOverloadMuted)

        assertFalse(session.setOverloadMuted(token1, false))
        assertTrue(session.state.value.isOverloadMuted)

        // Switching to a new track within the active session resets overload mute
        session.bindTrack(token2, videoId = "v3", channelId = "UC_CH2")
        assertFalse(session.state.value.isOverloadMuted)

        assertTrue(session.setOverloadMuted(token2, true))
        assertTrue(session.state.value.isOverloadMuted)
        assertTrue(session.clearTrack(token2))
        assertFalse(session.state.value.isOverloadMuted)
    }

    @Test
    fun `session helpers report active music removal and effective speed for audio processor`() {
        val (session, store, _) =
            newSession(
                initialPreferredSpeed = 2.0f,
                musicRemovalEnabled = true,
            )
        val token = session.nextSessionToken()

        // Before track bind, fail-safe global Music Removal clamps effective speed
        assertTrue(session.isMusicRemovalEnabledForSession(token))
        assertEquals(1.5f, session.effectiveSpeedForSession(token), 0.001f)

        session.bindTrack(token, videoId = "v1", channelId = "UC_1")
        assertTrue(session.isMusicRemovalEnabledForSession(token))
        assertEquals(1.5f, session.effectiveSpeedForSession(token), 0.001f)

        store.setMusicRemovalEnabledForChannel("UC_1", enabled = false)
        assertFalse(session.isMusicRemovalEnabledForSession(token))
        assertEquals(2.0f, session.effectiveSpeedForSession(token), 0.001f)
    }

    @Test
    fun `global music removal toggle updates speed live and rejects invalid tokens`() {
        val (session, store, _) =
            newSession(initialPreferredSpeed = 2.0f, musicRemovalEnabled = true)
        val token = session.nextSessionToken()

        assertFalse(session.bindTrack(0L, videoId = "v0", channelId = "UC_1"))
        assertFalse(session.clearTrack(0L))
        assertFalse(session.setOverloadMuted(0L, true))

        assertTrue(
            session.bindTrack(
                token,
                videoId = "v_music",
                channelId = "UC_1",
                category = com.github.libretube.api.obj.Streams.CATEGORY_MUSIC,
            ),
        )
        assertEquals(1.0f, session.state.value.effectiveSpeed, 0.001f)

        // Manual speed adjustment overrides the per-track CATEGORY_MUSIC 1f default while
        // respecting 1.5x cap
        assertEquals(1.5f, session.setPreferredSpeed(1.75f), 0.001f)
        assertEquals(1.5f, session.state.value.effectiveSpeed, 0.001f)
        assertEquals(1.75f, session.state.value.preferredSpeed, 0.001f)

        // Global Music Removal turned off live uncaps effective speed to Preferred Speed
        store.setMusicRemovalEnabled(false)
        assertFalse(session.state.value.isMusicRemovalEnabled)
        assertEquals(1.75f, session.state.value.effectiveSpeed, 0.001f)
    }
}
