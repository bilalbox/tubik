package com.github.libretube.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.github.libretube.R
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.updater.AppUpdater
import com.github.libretube.updater.UpdateInstallStatus

/**
 * Receives [PackageInstaller] session status callbacks, launches user confirmation when safe, and
 * delegates cleanup + event emission to [AppUpdater].
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val updater = AppUpdater.getInstance(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (!updater.canOpenExternalLink()) {
                    Toast.makeText(
                            context,
                            R.string.update_unpin_before_install,
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    updater.onInstallCallback(UpdateInstallStatus.BlockedByScreenPin, apkPath)
                } else {
                    val confirmIntent = intent.parcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmIntent != null) {
                        context.startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, R.string.update_install_success, Toast.LENGTH_SHORT).show()
                updater.onInstallCallback(UpdateInstallStatus.Succeeded, apkPath)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                updater.onInstallCallback(UpdateInstallStatus.Aborted, apkPath)
            }
            else -> {
                Toast.makeText(
                        context,
                        context.getString(
                            R.string.update_install_failed,
                            message ?: status.toString(),
                        ),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                updater.onInstallCallback(UpdateInstallStatus.Failed(message), apkPath)
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS =
            "com.github.libretube.receivers.UpdateInstallReceiver.ACTION_INSTALL_STATUS"
        const val EXTRA_APK_PATH = "extra_apk_path"
    }
}
