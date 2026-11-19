package com.github.libretube.updater

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.enums.NotificationId
import com.github.libretube.helpers.ScreenPinHelper
import com.github.libretube.receivers.UpdateInstallReceiver
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.workers.UpdateCheckWorker
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Production Android [UpdatePlatform] adapter bridging [AppUpdater] with [ScreenPinHelper],
 * [PackageManager], [PackageInstaller], [WorkManager], and [NotificationManagerCompat].
 */
internal class AndroidUpdatePlatform(
    private val context: Context,
) : UpdatePlatform {
    override val packageName: String
        get() = context.packageName

    override val updatesDir: File
        get() = File(context.cacheDir, UPDATES_CACHE_DIR)

    override fun isScreenPinned(): Boolean = ScreenPinHelper.isPinned(context)

    override fun canRequestPackageInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override fun readApkVerificationInfo(apkFile: File): ApkVerificationInfo? {
        val info = queryPackageInfo(archivePath = apkFile.absolutePath) ?: return null
        return ApkVerificationInfo(
            packageName = info.packageName,
            signerSha256Set = extractSignerFingerprints(info),
        )
    }

    override fun readInstalledSigners(packageName: String): Set<String>? {
        val info =
            runCatching { queryPackageInfo(installedPackageName = packageName) }.getOrNull()
                ?: return null
        return extractSignerFingerprints(info)
    }

    override fun commitInstall(apkFile: File): Result<Unit> = runCatching {
        check(!isScreenPinned()) { "Screen Pin is active; unpin before installing an update" }
        check(canRequestPackageInstalls()) {
            "Unknown app sources permission is required to install updates"
        }
        require(apkFile.isFile) { "APK file does not exist: ${apkFile.absolutePath}" }

        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
            }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        var committed = false
        try {
            session.openWrite(SESSION_ENTRY_NAME, 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val callbackIntent =
                Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                    .putExtra(UpdateInstallReceiver.EXTRA_APK_PATH, apkFile.absolutePath)
            val pendingIntent =
                requireNotNull(
                    PendingIntentCompat.getBroadcast(
                        context,
                        sessionId,
                        callbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT,
                        true,
                    ),
                ) {
                    "Failed to create mutable PendingIntent for PackageInstaller"
                }
            session.commit(pendingIntent.intentSender)
            committed = true
        } finally {
            if (!committed) runCatching { session.abandon() }
            session.close()
        }
    }

    override fun schedulePeriodicCheck(intervalHours: Long, replaceExisting: Boolean) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        val policy =
            if (replaceExisting) {
                ExistingPeriodicWorkPolicy.UPDATE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UPDATE_CHECK_WORK_NAME, policy, request)
    }

    override fun cancelPeriodicCheck() {
        WorkManager.getInstance(context).cancelUniqueWork(UPDATE_CHECK_WORK_NAME)
    }

    override fun notifyUpdateAvailable(manifest: UpdateManifest) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val settingsIntent =
            Intent(context, SettingsActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(
                    SettingsActivity.REDIRECT_KEY,
                    SettingsActivity.REDIRECT_TO_UPDATE_SETTINGS,
                )

        val pendingIntent =
            PendingIntentCompat.getActivity(
                context,
                NotificationId.APP_UPDATE.id,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )

        val notification =
            NotificationCompat.Builder(context, LibreTubeApp.APP_UPDATE_CHANNEL_NAME)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(
                    context.getString(R.string.update_notification_text, manifest.versionName),
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        NotificationManagerCompat.from(context).notify(NotificationId.APP_UPDATE.id, notification)
    }

    private fun queryPackageInfo(
        archivePath: String? = null,
        installedPackageName: String? = null,
    ): PackageInfo? {
        val pm = context.packageManager
        val target = archivePath ?: requireNotNull(installedPackageName)
        val isArchive = archivePath != null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags =
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                )
            if (isArchive) pm.getPackageArchiveInfo(target, flags)
            else pm.getPackageInfo(target, flags)
        } else {
            @Suppress("DEPRECATION")
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
            @Suppress("DEPRECATION")
            if (isArchive) pm.getPackageArchiveInfo(target, flags)
            else pm.getPackageInfo(target, flags)
        }
    }

    private fun extractSignerFingerprints(info: PackageInfo): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION") info.signatures
            }
        return signatures
            ?.map { AppUpdater.fingerprintCertificate(it.toByteArray()) }
            ?.toSet()
            .orEmpty()
    }

    companion object {
        private const val UPDATE_CHECK_WORK_NAME = "AppUpdateCheck"
        private const val UPDATES_CACHE_DIR = "updates"
        private const val SESSION_ENTRY_NAME = "tubik-update.apk"
    }
}
