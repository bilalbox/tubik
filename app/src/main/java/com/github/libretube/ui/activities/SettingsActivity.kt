package com.github.libretube.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.github.libretube.R
import com.github.libretube.databinding.ActivitySettingsBinding
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.ParentPinDialogs
import com.github.libretube.util.AccessControl
import com.github.libretube.util.ProtectedAction

class SettingsActivity : BaseActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var pinUnlocked = false
    private var pendingOpenUpdateDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pinUnlocked = savedInstanceState?.getBoolean(STATE_PIN_UNLOCKED, false) ?: false
        pendingOpenUpdateDialog =
            savedInstanceState?.getBoolean(STATE_PENDING_UPDATE_DIALOG, false) ?: false

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        val navController = binding.settings.getFragment<NavHostFragment>().navController
        setSupportActionBar(binding.toolbar)
        setContentView(binding.root)

        // ensure that the toolbar's back button is always visible
        val appBarConfiguration =
            AppBarConfiguration.Builder()
                .setFallbackOnNavigateUpListener {
                    finish()
                    true
                }
                .build()
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        when (intent.extras?.getString(REDIRECT_KEY)) {
            REDIRECT_TO_INTENT_SETTINGS -> {
                navController.navigate(R.id.action_global_instanceSettings)
            }
            REDIRECT_TO_UPDATE_SETTINGS -> {
                pendingOpenUpdateDialog = true
            }
        }

        if (!pinUnlocked) {
            binding.root.isInvisible = true
            AccessControl.instance.authorize(
                ProtectedAction.OpenSettings,
                ParentPinDialogs.promptAdapter(this),
                onAuthorized = {
                    pinUnlocked = true
                    binding.root.isVisible = true
                    maybeDispatchPendingUpdateRedirect()
                },
                onCancelled = {
                    finish()
                },
            )
        } else {
            maybeDispatchPendingUpdateRedirect()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PIN_UNLOCKED, pinUnlocked)
        outState.putBoolean(STATE_PENDING_UPDATE_DIALOG, pendingOpenUpdateDialog)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.extras?.getString(REDIRECT_KEY) == REDIRECT_TO_UPDATE_SETTINGS) {
            pendingOpenUpdateDialog = true
            if (pinUnlocked) {
                binding.settings
                    .getFragment<NavHostFragment>()
                    .navController
                    .popBackStack(R.id.mainSettings, false)
                maybeDispatchPendingUpdateRedirect()
            }
            return
        }
    }

    private fun maybeDispatchPendingUpdateRedirect() {
        if (!pendingOpenUpdateDialog || !pinUnlocked) return
        pendingOpenUpdateDialog = false
        binding.settings
            .getFragment<NavHostFragment>()
            .childFragmentManager
            .setFragmentResult(REQUEST_OPEN_UPDATE_DIALOG, bundleOf())
    }

    companion object {
        const val REDIRECT_KEY = "redirect"
        const val REDIRECT_TO_INTENT_SETTINGS = "intent_settings"
        const val REDIRECT_TO_UPDATE_SETTINGS = "update_settings"
        const val REQUEST_OPEN_UPDATE_DIALOG = "request_open_update_dialog"
        private const val STATE_PIN_UNLOCKED = "state_pin_unlocked"
        private const val STATE_PENDING_UPDATE_DIALOG = "state_pending_update_dialog"
    }
}
