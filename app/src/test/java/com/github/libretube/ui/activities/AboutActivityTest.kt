package com.github.libretube.ui.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AboutActivityTest {

    private class RecordingActions : AboutCardActions {
        val openedLinks = mutableListOf<String>()
        val copiedLinks = mutableListOf<String>()
        var licenseShownCount = 0
        var deviceInfoShownCount = 0

        override fun openLink(href: String) {
            openedLinks += href
        }

        override fun copyAndOfferOpen(href: String) {
            copiedLinks += href
        }

        override fun showLicense() {
            licenseShownCount++
        }

        override fun showDeviceInfo() {
            deviceInfoShownCount++
        }
    }

    private data class RegisteredCard(
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)?,
    )

    @Test
    fun `about link cards open Tubik website, GitHub, and privacy policy without LibreTube or Piped links`() {
        val actions = RecordingActions()
        val cards = linkedMapOf<AboutCard, RegisteredCard>()

        bindAboutCards(actions) { card, onClick, onLongClick ->
            cards[card] = RegisteredCard(onClick, onLongClick)
        }

        assertEquals(
            setOf(
                AboutCard.WEBSITE,
                AboutCard.GITHUB,
                AboutCard.PRIVACY_POLICY,
                AboutCard.LICENSE,
                AboutCard.DEVICE,
            ),
            cards.keys,
        )

        cards.getValue(AboutCard.WEBSITE).onClick()
        cards.getValue(AboutCard.GITHUB).onClick()
        cards.getValue(AboutCard.PRIVACY_POLICY).onClick()

        assertEquals(
            listOf(
                "https://tubik.23p.dev",
                "https://github.com/bilalbox/tubik",
                "https://tubik.23p.dev/privacy",
            ),
            actions.openedLinks,
        )
        assertEquals("https://github.com/bilalbox/tubik", AboutActivity.GITHUB_URL)
        actions.openedLinks.forEach { url ->
            assertFalse(
                "Unexpected upstream LibreTube link: $url",
                url.contains("libretube", ignoreCase = true),
            )
            assertFalse("Unexpected Piped link: $url", url.contains("piped", ignoreCase = true))
            assertFalse("Unexpected Weblate link: $url", url.contains("weblate", ignoreCase = true))
        }
    }

    @Test
    fun `long clicks copy link URLs and license or device clicks invoke their dialogs`() {
        val actions = RecordingActions()
        val cards = linkedMapOf<AboutCard, RegisteredCard>()

        bindAboutCards(actions) { card, onClick, onLongClick ->
            cards[card] = RegisteredCard(onClick, onLongClick)
        }

        val longClickableCards =
            listOf(
                AboutCard.WEBSITE,
                AboutCard.GITHUB,
                AboutCard.PRIVACY_POLICY,
                AboutCard.LICENSE,
            )
        longClickableCards.forEach { card ->
            val handler = cards.getValue(card).onLongClick
            assertNotNull("Expected long-click handler for $card", handler)
            handler!!.invoke()
        }

        assertEquals(
            listOf(
                "https://tubik.23p.dev",
                "https://github.com/bilalbox/tubik",
                "https://tubik.23p.dev/privacy",
                "https://gnu.org/",
            ),
            actions.copiedLinks,
        )

        cards.getValue(AboutCard.LICENSE).onClick()
        cards.getValue(AboutCard.DEVICE).onClick()

        assertEquals(1, actions.licenseShownCount)
        assertEquals(1, actions.deviceInfoShownCount)
        assertNull(cards.getValue(AboutCard.DEVICE).onLongClick)
    }
}
