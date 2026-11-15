package com.github.libretube.helpers

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.updater.PreferenceUpdateStorage
import com.github.libretube.updater.UpdateCheckCoordinator
import com.github.libretube.updater.UpdateManifest
import com.github.libretube.updater.UpdateNotifier
import com.github.libretube.updater.UpdateRepository
import com.github.libretube.updater.UpdateWorkScheduler
import com.github.libretube.workers.UpdateCheckWorker
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bridges Tubik's OTA updater domain logic with Android [WorkManager], notifications, and the
 * app-private updates cache directory.
 */
object UpdateCheckHelper {
    private const val UPDATE_CHECK_WORK_NAME = "AppUpdateCheck"
    private const val UPDATES_CACHE_DIR = "updates"

    /** Returns the app-private directory used to stage downloaded update APKs. */
    fun updatesCacheDir(context: Context): File = File(context.cacheDir, UPDATES_CACHE_DIR)

    /**
     * Builds a [UpdateCheckCoordinator] wired to Android storage, WorkManager, and notifications.
     */
    fun createCoordinator(context: Context): UpdateCheckCoordinator {
        val appContext = context.applicationContext
        return UpdateCheckCoordinator(
            repository = UpdateRepository(storage = PreferenceUpdateStorage),
            storage = PreferenceUpdateStorage,
            scheduler = WorkManagerUpdateScheduler(appContext),
            notifier = UpdateNotifier { manifest -> notifyUpdateAvailable(appContext, manifest) },
        )
    }

    /**
     * Synchronizes the periodic 24-hour WorkManager update check on app startup or toggle change.
     */
    fun enqueueWork(context: Context, existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy) {
        PreferenceHelper.initialize(context)
        val replaceExisting = existingPeriodicWorkPolicy == ExistingPeriodicWorkPolicy.UPDATE
        createCoordinator(context).syncSchedule(replaceExisting = replaceExisting)
    }

    private fun notifyUpdateAvailable(context: Context, manifest: UpdateManifest) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
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

    private class WorkManagerUpdateScheduler(private val context: Context) : UpdateWorkScheduler {
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
    }
}
