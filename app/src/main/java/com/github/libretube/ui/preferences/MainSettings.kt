package com.github.libretube.ui.preferences

import android.os.Bundle
import android.text.format.DateUtils
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.UpdateCheckHelper
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.AppUpdateDialog
import com.github.libretube.ui.dialogs.ErrorDialog
import com.github.libretube.ui.dialogs.ParentPinDialogs
import com.github.libretube.updater.PreferenceUpdateStorage
import com.github.libretube.updater.UpdateCheckResult
import com.github.libretube.updater.UpdateRepository
import com.github.libretube.util.ParentPinStore
import com.github.libretube.util.ProtectedAction

class MainSettings : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        val store = ParentPinStore.instance
        val allowlistPref = findPreference<SwitchPreferenceCompat>(PreferenceKeys.ALLOWLIST_ENABLED)
        val lockSettingsPref =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.SETTINGS_PIN_LOCK_ENABLED)
        val parentPinPref = findPreference<Preference>(PreferenceKeys.CHANGE_PARENT_PIN)

        if (!store.isPinSet() && lockSettingsPref?.isChecked == true) {
            lockSettingsPref.isChecked = false
        }

        fun updateParentPinPreference() {
            if (store.isPinSet()) {
                parentPinPref?.setTitle(R.string.change_parent_pin_title)
                parentPinPref?.setSummary(R.string.change_parent_pin_summary)
            } else {
                parentPinPref?.setTitle(R.string.set_parent_pin_title)
                parentPinPref?.setSummary(R.string.set_parent_pin_summary)
            }
        }
        updateParentPinPreference()

        allowlistPref?.setOnPreferenceChangeListener { _, newValue ->
            val enableAllowlist = newValue as? Boolean ?: false
            store.authorize(
                ProtectedAction.SetAllowlistEnabled(enableAllowlist),
                ParentPinDialogs.promptAdapter(requireContext()),
                onAuthorized = {
                    allowlistPref.isChecked = enableAllowlist
                },
            )
            false
        }

        val musicRemovalPref =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.MUSIC_REMOVAL_ENABLED)
        musicRemovalPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: true
            store.authorize(
                ProtectedAction.SetMusicRemovalEnabled(enabled),
                ParentPinDialogs.promptAdapter(requireContext()),
                onAuthorized = {
                    store.setMusicRemovalEnabled(enabled)
                    musicRemovalPref.isChecked = enabled
                },
            )
            false
        }

        lockSettingsPref?.setOnPreferenceChangeListener { _, newValue ->
            val enableLock = newValue as? Boolean ?: false
            if (enableLock && !store.isPinSet()) {
                ParentPinDialogs.showSetupPinDialog(
                    requireContext(),
                    onPinSet = {
                        lockSettingsPref.isChecked = true
                        updateParentPinPreference()
                    },
                )
                false
            } else {
                true
            }
        }

        parentPinPref?.setOnPreferenceClickListener {
            if (store.isPinSet()) {
                ParentPinDialogs.showChangePinDialog(requireContext())
            } else {
                ParentPinDialogs.showSetupPinDialog(
                    requireContext(),
                    onPinSet = {
                        updateParentPinPreference()
                    },
                )
            }
            true
        }

        val crashlog = findPreference<Preference>("crashlog")
        crashlog?.isVisible = PreferenceHelper.getErrorLog().isNotEmpty() && BuildConfig.DEBUG
        crashlog?.setOnPreferenceClickListener {
            ErrorDialog().show(childFragmentManager, null)
            crashlog.isVisible = false
            true
        }

        val autoUpdatePref =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.AUTO_UPDATE_CHECK_ENABLED)
        autoUpdatePref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: true
            UpdateCheckHelper.createCoordinator(requireContext()).setAutoUpdateEnabled(enabled)
            true
        }

        val appUpdatePref = findPreference<Preference>(KEY_APP_UPDATE_CHECK)
        refreshUpdateSummary(appUpdatePref)
        appUpdatePref?.setOnPreferenceClickListener {
            openAppUpdateDialog(appUpdatePref)
            true
        }

        parentFragmentManager.setFragmentResultListener(
            SettingsActivity.REQUEST_OPEN_UPDATE_DIALOG,
            this,
        ) { _, _ ->
            openAppUpdateDialog(appUpdatePref)
        }

        listOf(
                "general" to R.id.action_global_generalSettings,
                "account" to R.id.action_global_instanceSettings,
                "appearance" to R.id.action_global_appearanceSettings,
                "sponsorblock" to R.id.action_global_sponsorBlockSettings,
                "player" to R.id.action_global_playerSettings,
                "audio_video" to R.id.action_global_audioVideoSettings,
                "notifications" to R.id.action_global_notificationSettings,
                "backup_restore" to R.id.action_global_backupRestoreSettings,
            )
            .forEach { (preferenceKey, actionId) ->
                findPreference<Preference>(preferenceKey)?.setOnPreferenceClickListener { _ ->
                    findNavController().navigate(actionId)
                    true
                }
            }
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateSummary(findPreference(KEY_APP_UPDATE_CHECK))
    }

    private fun openAppUpdateDialog(appUpdatePref: Preference?) {
        AppUpdateDialog(
                context = requireContext(),
                fragmentManager = childFragmentManager,
                scope = viewLifecycleOwner.lifecycleScope,
                onStateUpdated = { refreshUpdateSummary(appUpdatePref) },
            )
            .show()
    }

    private fun refreshUpdateSummary(appUpdatePref: Preference?) {
        appUpdatePref ?: return
        val cached = UpdateRepository(storage = PreferenceUpdateStorage).getCachedState()
        appUpdatePref.summary =
            when (val result = cached.result) {
                is UpdateCheckResult.UpdateAvailable ->
                    getString(
                        R.string.update_summary_available,
                        result.manifest.versionName,
                        BuildConfig.VERSION_NAME,
                    )
                is UpdateCheckResult.IncompatibleSdk ->
                    getString(
                        R.string.update_summary_incompatible,
                        BuildConfig.VERSION_NAME,
                        result.manifest.versionName,
                        result.manifest.minSdk,
                    )
                is UpdateCheckResult.UpToDate -> {
                    if (cached.lastCheckTimestampMillis > 0L) {
                        val relative =
                            DateUtils.getRelativeTimeSpanString(
                                cached.lastCheckTimestampMillis,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            )
                        getString(
                            R.string.update_summary_up_to_date_checked,
                            BuildConfig.VERSION_NAME,
                            relative,
                        )
                    } else {
                        getString(R.string.update_summary_up_to_date, BuildConfig.VERSION_NAME)
                    }
                }
                else -> getString(R.string.update_summary_unchecked, BuildConfig.VERSION_NAME)
            }
    }

    companion object {
        private const val KEY_APP_UPDATE_CHECK = "app_update_check"
    }
}
