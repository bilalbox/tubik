package com.github.libretube.ui.activities

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import com.github.libretube.R
import com.github.libretube.databinding.ActivityAboutBinding
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.ui.base.BaseActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** Cards displayed on the About screen. */
internal enum class AboutCard {
    WEBSITE,
    GITHUB,
    PRIVACY_POLICY,
    LICENSE,
    DEVICE,
}

/** Callbacks invoked when the user interacts with an About screen card. */
internal interface AboutCardActions {
    fun openLink(href: String)

    fun copyAndOfferOpen(href: String)

    fun showLicense()

    fun showDeviceInfo()
}

private val ABOUT_LINK_CARDS: List<Pair<AboutCard, String>> =
    listOf(
        AboutCard.WEBSITE to AboutActivity.WEBSITE_URL,
        AboutCard.GITHUB to AboutActivity.GITHUB_URL,
        AboutCard.PRIVACY_POLICY to AboutActivity.PRIVACY_POLICY_URL,
    )

/** Registers click and optional long-click handlers for every card on [AboutActivity]. */
internal fun bindAboutCards(
    actions: AboutCardActions,
    registerCard: (card: AboutCard, onClick: () -> Unit, onLongClick: (() -> Unit)?) -> Unit,
) {
    ABOUT_LINK_CARDS.forEach { (card, url) ->
        registerCard(card, { actions.openLink(url) }, { actions.copyAndOfferOpen(url) })
    }
    registerCard(
        AboutCard.LICENSE,
        { actions.showLicense() },
        { actions.copyAndOfferOpen(AboutActivity.LICENSE_URL) },
    )
    registerCard(AboutCard.DEVICE, { actions.showDeviceInfo() }, null)
}

class AboutActivity : BaseActivity() {
    private lateinit var binding: ActivityAboutBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val cardViews: Map<AboutCard, MaterialCardView> =
            mapOf(
                AboutCard.WEBSITE to binding.website,
                AboutCard.GITHUB to binding.github,
                AboutCard.PRIVACY_POLICY to binding.privacyPolicy,
                AboutCard.LICENSE to binding.license,
                AboutCard.DEVICE to binding.device,
            )

        val actions =
            object : AboutCardActions {
                override fun openLink(href: String) {
                    IntentHelper.openLinkFromHref(this@AboutActivity, supportFragmentManager, href)
                }

                override fun copyAndOfferOpen(href: String) {
                    onLongClick(href)
                }

                override fun showLicense() {
                    this@AboutActivity.showLicense()
                }

                override fun showDeviceInfo() {
                    this@AboutActivity.showDeviceInfo()
                }
            }

        bindAboutCards(actions) { card, onClick, onLongClick ->
            val cardView = cardViews.getValue(card)
            cardView.setOnClickListener { onClick() }
            if (onLongClick != null) {
                cardView.setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }
    }

    private fun onLongClick(href: String) {
        // copy the link to the clipboard
        ClipboardHelper.save(this, text = href)
        // show the snackBar with open action
        Snackbar.make(
                binding.root,
                R.string.copied_to_clipboard,
                Snackbar.LENGTH_LONG,
            )
            .setAction(R.string.open_copied) {
                IntentHelper.openLinkFromHref(this, supportFragmentManager, href)
            }
            .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
            .show()
    }

    private fun showLicense() {
        val licenseHtml =
            assets
                .open("gpl3.html")
                .bufferedReader()
                .use { it.readText() }
                .parseAsHtml(HtmlCompat.FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH)

        MaterialAlertDialogBuilder(this)
            .setPositiveButton(getString(R.string.okay)) { _, _ -> }
            .setMessage(licenseHtml)
            .create()
            .show()
    }

    private fun showDeviceInfo() {
        val metrics = Resources.getSystem().displayMetrics

        val text =
            "Manufacturer: ${Build.MANUFACTURER}\n" +
                "Board: ${Build.BOARD}\n" +
                "Arch: ${Build.SUPPORTED_ABIS[0]}\n" +
                "Android SDK: ${Build.VERSION.SDK_INT}\n" +
                "OS: Android ${Build.VERSION.RELEASE}\n" +
                "Display: ${metrics.widthPixels}x${metrics.heightPixels}\n" +
                "Font scale: ${Resources.getSystem().configuration.fontScale}"

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_info)
            .setMessage(text)
            .setNegativeButton(R.string.copy_tooltip) { _, _ ->
                ClipboardHelper.save(this@AboutActivity, text = text)
            }
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    companion object {
        internal const val WEBSITE_URL = "https://tubik.23p.dev"
        const val GITHUB_URL = "https://github.com/bilalbox/tubik"
        internal const val PRIVACY_POLICY_URL = "https://tubik.23p.dev/privacy"
        internal const val LICENSE_URL = "https://gnu.org/"
    }
}
