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
import com.github.libretube.util.AccessControl
import com.github.libretube.util.PolicyChange

class MainSettings : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        val accessControl = AccessControl.instance
        bindScreenPinPreference(accessControl)
        bindAccessControlPreferences(accessControl)

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
        findPreference<SwitchPreferenceCompat>(PreferenceKeys.SCREEN_PIN_ENABLED)?.isChecked =
            AccessControl.instance.policy.screenPinEnabled
        refreshUpdateSummary(findPreference(KEY_APP_UPDATE_CHECK))
    }

    private fun bindAccessControlPreferences(accessControl: AccessControl) {
        val allowlistPref = findPreference<SwitchPreferenceCompat>(PreferenceKeys.ALLOWLIST_ENABLED)
        val musicRemovalPref =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.MUSIC_REMOVAL_ENABLED)
        val lockSettingsPref =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.SETTINGS_PIN_LOCK_ENABLED)
        val parentPinPref = findPreference<Preference>(PreferenceKeys.CHANGE_PARENT_PIN)

        if (!accessControl.policy.isPinSet && lockSettingsPref?.isChecked == true) {
            lockSettingsPref.isChecked = false
        }

        fun updateParentPinPreference() {
            val hasPin = accessControl.policy.isPinSet
            parentPinPref?.setTitle(
                if (hasPin) R.string.change_parent_pin_title else R.string.set_parent_pin_title,
            )
            parentPinPref?.setSummary(
                if (hasPin) R.string.change_parent_pin_summary else R.string.set_parent_pin_summary,
            )
        }
        updateParentPinPreference()

        allowlistPref?.setOnPreferenceChangeListener { _, newValue ->
            val enableAllowlist = newValue as? Boolean ?: false
            accessControl.apply(
                PolicyChange.SetAllowlist(enableAllowlist),
                ParentPinDialogs.promptAdapter(requireContext()),
            ) { updated ->
                allowlistPref.isChecked = updated.allowlistEnabled
            }
            false
        }

        musicRemovalPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: true
            accessControl.apply(
                PolicyChange.SetMusicRemoval(enabled),
                ParentPinDialogs.promptAdapter(requireContext()),
            ) { updated ->
                musicRemovalPref.isChecked = updated.musicRemovalEnabled
            }
            false
        }

        bindSettingsPinLockPreference(accessControl, lockSettingsPref, ::updateParentPinPreference)

        parentPinPref?.setOnPreferenceClickListener {
            if (accessControl.policy.isPinSet) {
                ParentPinDialogs.showChangePinDialog(requireContext())
            } else {
                ParentPinDialogs.showSetupPinDialog(
                    requireContext(),
                    onPinSet = ::updateParentPinPreference,
                )
            }
            true
        }
    }

    private fun bindSettingsPinLockPreference(
        accessControl: AccessControl,
        lockSettingsPref: SwitchPreferenceCompat?,
        onPinConfigured: () -> Unit,
    ) {
        lockSettingsPref?.setOnPreferenceChangeListener { _, newValue ->
            val enableLock = newValue as? Boolean ?: false
            if (enableLock && !accessControl.policy.isPinSet) {
                ParentPinDialogs.showSetupPinDialog(
                    requireContext(),
                    onPinSet = {
                        accessControl.apply(
                            PolicyChange.SetSettingsPinLock(true),
                            ParentPinDialogs.promptAdapter(requireContext()),
                        ) { updated ->
                            lockSettingsPref.isChecked = updated.settingsPinLockEnabled
                            onPinConfigured()
                        }
                    },
                )
            } else {
                accessControl.apply(
                    PolicyChange.SetSettingsPinLock(enableLock),
                    ParentPinDialogs.promptAdapter(requireContext()),
                ) { updated ->
                    lockSettingsPref.isChecked = updated.settingsPinLockEnabled
                }
            }
            false
        }
    }

    private fun bindScreenPinPreference(accessControl: AccessControl) {
        val preference =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.SCREEN_PIN_ENABLED) ?: return
        preference.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            accessControl.apply(
                PolicyChange.SetScreenPin(enabled),
                ParentPinDialogs.promptAdapter(requireContext()),
            ) { updated ->
                preference.isChecked = updated.screenPinEnabled
            }
            false
        }
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
