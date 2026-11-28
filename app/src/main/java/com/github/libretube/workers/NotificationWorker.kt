package com.github.libretube.workers

import android.Manifest
import android.app.Notification
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.NotificationWithIdAndTag
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.LibreTubeApp.Companion.PUSH_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.views.TimePickerPreference
import com.github.libretube.util.DataSaverMode
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/** The notification worker which checks for new streams in a certain frequency */
class NotificationWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {
    private val notificationManager = NotificationManagerCompat.from(appContext)

    override suspend fun doWork(): Result {
        if (!checkTime()) return Result.success()
        // check whether there are new streams and notify if there are some
        val result = checkForNewStreams()
        // return success if the API request succeeded
        return if (result) Result.success() else Result.retry()
    }

    /** Determine whether the time is valid to notify */
    private fun checkTime(): Boolean {
        if (!PreferenceHelper.getBoolean(PreferenceKeys.NOTIFICATION_TIME_ENABLED, false)) {
            return true
        }

        val start = getTimePickerPref(PreferenceKeys.NOTIFICATION_START_TIME)
        val end = getTimePickerPref(PreferenceKeys.NOTIFICATION_END_TIME)
        val currentTime = LocalTime.now()

        return if (start > end) {
            currentTime !in end..start
        } else {
            currentTime in start..end
        }
    }

    private fun getTimePickerPref(key: String): LocalTime {
        return LocalTime.parse(
            PreferenceHelper.getString(key, TimePickerPreference.DEFAULT_VALUE),
        )
    }

    /** check whether new streams are available in subscriptions */
    private suspend fun checkForNewStreams(): Boolean {
        Log.d(TAG(), "Work manager started")

        // fetch the users feed
        val videoFeed =
            try {
                withContext(Dispatchers.IO) {
                        SubscriptionHelper.getFeed(FeedRefresh.All)
                    }
                    .filter { !it.isUpcoming }
            } catch (_: Exception) {
                return false
            }

        val lastFeedCheckMillis = PreferenceHelper.getLastCheckedFeedTime(seenByUser = false)

        // Seed baseline on first run so subsequent checks only notify for newly uploaded streams
        if (seedInitialNotificationFeedTimeIfFirstRun(lastFeedCheckMillis, videoFeed)) {
            return true
        }
        if (videoFeed.none { it.uploaded > lastFeedCheckMillis }) return true

        val channelsToIgnore = PreferenceHelper.getIgnorableNotificationChannels()
        val enableShortsNotification =
            PreferenceHelper.getBoolean(PreferenceKeys.SHORTS_NOTIFICATIONS, false)

        val channelGroups =
            groupNewStreamsForNotifications(
                videoFeed = videoFeed,
                lastFeedCheckMillis = lastFeedCheckMillis,
                enableShortsNotification = enableShortsNotification,
                channelsToIgnore = channelsToIgnore,
            )

        // update the last feed check time in order to not show the same notification again
        PreferenceHelper.updateLastFeedWatchedTime(videoFeed.first().uploaded, seenByUser = false)

        // return if the previous video didn't get found or all the channels have notifications
        // disabled
        if (channelGroups.isEmpty()) return true

        Log.d(TAG(), "Create notifications for new videos")

        // create a notification for each new stream
        channelGroups.forEach { (channelId, streams) ->
            createNotificationsForChannel(channelId, streams)
        }
        // return whether the work succeeded
        return true
    }

    /**
     * Group of notifications created when new streams are found in a given channel.
     *
     * For more information, see https://developer.android.com/develop/ui/views/notifications/group
     */
    private suspend fun createNotificationsForChannel(group: String, streams: List<StreamItem>) {
        // Avoid creating notifications if permission is not granted.
        if (
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val validStreams = streams.filter { !it.url?.toID().isNullOrEmpty() }
        if (validStreams.isEmpty()) return

        val summaryId = group.hashCode()
        val intent =
            Intent(applicationContext, MainActivity::class.java)
                .setFlags(INTENT_FLAGS)
                .putExtra(IntentData.channelId, group)
        val pendingIntent =
            PendingIntentCompat.getActivity(
                applicationContext,
                summaryId,
                intent,
                FLAG_UPDATE_CURRENT,
                false,
            )

        // Create summary notification containing new streams for Android versions below 7.0.
        val newStreams =
            applicationContext.resources.getQuantityString(
                R.plurals.channel_new_streams,
                validStreams.size,
                validStreams.size,
            )
        val summary = NotificationCompat.InboxStyle().setSummaryText(newStreams)
        validStreams.forEach {
            summary.addLine(it.title)
        }
        val summaryNotification =
            createNotificationBuilder(group)
                .setContentTitle(validStreams[0].uploaderName)
                .setContentText(newStreams)
                // The intent that will fire when the user taps the notification
                .setContentIntent(pendingIntent)
                .setGroupSummary(true)
                .setStyle(summary)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                // Show channel avatar on Android versions below 7.0.
                .setLargeIcon(downloadImage(validStreams[0].uploaderAvatar))
                .build()

        // Create stream notifications. These are automatically grouped on Android 7.0 and later.
        val notifications =
            withContext(Dispatchers.IO) {
                validStreams
                    .map { async { createStreamNotification(group, it) } }
                    .awaitAll()
                    .filterNotNull()
            }
        if (notifications.isNotEmpty()) {
            notificationManager.notify(notifications)
            notificationManager.notify(summaryId, summaryNotification)
        }
    }

    private suspend fun createStreamNotification(
        group: String,
        stream: StreamItem,
    ): NotificationWithIdAndTag? {
        val videoId = stream.url?.toID()?.takeIf { it.isNotEmpty() } ?: return null
        val intent =
            Intent(applicationContext, MainActivity::class.java)
                .setFlags(INTENT_FLAGS)
                .putExtra(IntentData.videoId, videoId)
        val notificationId = videoId.hashCode()
        val pendingIntent =
            PendingIntentCompat.getActivity(
                applicationContext,
                notificationId,
                intent,
                FLAG_UPDATE_CURRENT,
                false,
            )

        // Load stream thumbnails if the relevant toggle is enabled.
        val thumbnail = downloadImage(stream.thumbnail)

        val notificationBuilder =
            createNotificationBuilder(group)
                .setContentTitle(stream.title)
                .setContentText(stream.uploaderName)
                // The intent that will fire when the user taps the notification
                .setContentIntent(pendingIntent)
                .setSilent(true)
                .setLargeIcon(thumbnail)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .bigLargeIcon(null as Bitmap?), // Hides the icon when expanding
                )
                .setWhen(stream.uploaded)
                .setShowWhen(true)

        return NotificationWithIdAndTag(notificationId, notificationBuilder.build())
    }

    private suspend fun downloadImage(url: String?): Bitmap? {
        return if (shouldDownloadNotificationImage(applicationContext)) {
            ImageHelper.getImage(applicationContext, url)
        } else {
            null
        }
    }

    private fun createNotificationBuilder(group: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(applicationContext, PUSH_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(group)
            .setCategory(Notification.CATEGORY_SOCIAL)
    }

    companion object {
        private const val INTENT_FLAGS =
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}

internal fun shouldDownloadNotificationImage(context: Context): Boolean =
    !DataSaverMode.isEnabled(context)

internal fun seedInitialNotificationFeedTimeIfFirstRun(
    lastFeedCheckMillis: Long,
    videoFeed: List<StreamItem>,
): Boolean {
    if (lastFeedCheckMillis != 0L) return false
    videoFeed.firstOrNull()?.uploaded?.let { latest ->
        PreferenceHelper.updateLastFeedWatchedTime(latest, seenByUser = false)
    }
    return true
}

internal fun groupNewStreamsForNotifications(
    videoFeed: List<StreamItem>,
    lastFeedCheckMillis: Long,
    enableShortsNotification: Boolean,
    channelsToIgnore: Collection<String>,
): Map<String, List<StreamItem>> {
    val ignoredChannelSet = channelsToIgnore.toSet()
    return videoFeed
        .asSequence()
        .filter { it.uploaded > lastFeedCheckMillis }
        .filter { enableShortsNotification || !it.isShort }
        .mapNotNull { item ->
            val channelId = item.uploaderUrl?.toID()?.takeIf { it.isNotEmpty() }
            if (channelId == null || channelId in ignoredChannelSet) {
                null
            } else {
                channelId to item
            }
        }
        .groupBy({ it.first }, { it.second })
}
