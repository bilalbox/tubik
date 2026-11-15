package com.github.libretube.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.github.libretube.R
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.helpers.ScreenPinHelper
import com.github.libretube.helpers.UpdateCheckHelper
import com.github.libretube.updater.ApkInstaller
import com.github.libretube.updater.UpdateInstallStatus
import com.github.libretube.updater.UpdateRepository
import java.io.File

/**
 * Receives [PackageInstaller] session status callbacks, launches user confirmation when safe, and
 * cleans up staged update APKs on terminal outcomes.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (ScreenPinHelper.isPinned(context)) {
                    cleanupCachedApks(context, apkPath)
                    Toast.makeText(
                            context,
                            R.string.update_unpin_before_install,
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    ApkInstaller.emitEvent(UpdateInstallStatus.BlockedByScreenPin)
                    return
                }
                val confirmIntent = intent.parcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    context.startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                cleanupCachedApks(context, apkPath)
                Toast.makeText(context, R.string.update_install_success, Toast.LENGTH_SHORT).show()
                ApkInstaller.emitEvent(UpdateInstallStatus.Succeeded)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                cleanupCachedApks(context, apkPath)
                ApkInstaller.emitEvent(UpdateInstallStatus.Aborted)
            }
            else -> {
                cleanupCachedApks(context, apkPath)
                Toast.makeText(
                        context,
                        context.getString(
                            R.string.update_install_failed,
                            message ?: status.toString(),
                        ),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                ApkInstaller.emitEvent(UpdateInstallStatus.Failed(message))
            }
        }
    }

    private fun cleanupCachedApks(context: Context, apkPath: String?) {
        apkPath?.let { File(it).delete() }
        UpdateRepository.cleanDirectory(UpdateCheckHelper.updatesCacheDir(context))
    }

    companion object {
        const val ACTION_INSTALL_STATUS =
            "com.github.libretube.receivers.UpdateInstallReceiver.ACTION_INSTALL_STATUS"
        const val EXTRA_APK_PATH = "extra_apk_path"
    }
}
