package com.github.libretube.workers

import android.app.Application
import android.content.Context
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PreferenceHelper.initialize(context)
        PreferenceHelper.remove(PreferenceKeys.DATA_SAVER_MODE)
        PreferenceHelper.remove(PreferenceKeys.LAST_REFRESHED_FEED_TIME)
        PreferenceHelper.remove(PreferenceKeys.LAST_USER_SEEN_FEED_TIME)
    }

    @Test
    fun `shouldDownloadNotificationImage reads string DATA_SAVER_MODE without ClassCastException`() {
        assertTrue(shouldDownloadNotificationImage(context))

        PreferenceHelper.putString(PreferenceKeys.DATA_SAVER_MODE, "disabled")
        assertTrue(shouldDownloadNotificationImage(context))

        PreferenceHelper.putString(PreferenceKeys.DATA_SAVER_MODE, "enabled")
        assertFalse(shouldDownloadNotificationImage(context))
    }

    @Test
    fun `groupNewStreamsForNotifications skips null or blank uploaderUrl without NPE`() {
        val validItem1 = streamItem(id = "vid1", channel = "/channel/UC_CHAN_1", uploaded = 2_000L)
        val validItem2 = streamItem(id = "vid2", channel = "/channel/UC_CHAN_1", uploaded = 1_500L)
        val nullUploaderItem = streamItem(id = "series1", channel = null, uploaded = 2_500L)
        val blankUploaderItem = streamItem(id = "blank1", channel = "/", uploaded = 2_200L)
        val ignoredItem =
            streamItem(id = "ignored1", channel = "/channel/UC_IGNORED", uploaded = 2_100L)
        val oldItem = streamItem(id = "old1", channel = "/channel/UC_CHAN_2", uploaded = 1_000L)
        val shortItem =
            streamItem(
                id = "short1",
                channel = "/channel/UC_CHAN_2",
                uploaded = 2_300L,
                isShort = true,
            )

        val grouped =
            groupNewStreamsForNotifications(
                videoFeed =
                    listOf(
                        nullUploaderItem,
                        shortItem,
                        blankUploaderItem,
                        ignoredItem,
                        validItem1,
                        validItem2,
                        oldItem,
                    ),
                lastFeedCheckMillis = 1_000L,
                enableShortsNotification = false,
                channelsToIgnore = listOf("UC_IGNORED"),
            )

        assertEquals(mapOf("UC_CHAN_1" to listOf(validItem1, validItem2)), grouped)
    }

    @Test
    fun `first-run seeding records latest timestamp without notifying on historical items`() {
        val historicalNewest =
            streamItem(id = "newest_old", channel = "/channel/UC_CHAN_1", uploaded = 5_000L)
        val historicalOlder =
            streamItem(id = "older_old", channel = "/channel/UC_CHAN_1", uploaded = 3_000L)
        val initialFeed = listOf(historicalNewest, historicalOlder)

        assertTrue(seedInitialNotificationFeedTimeIfFirstRun(0L, initialFeed))
        assertEquals(5_000L, PreferenceHelper.getLastCheckedFeedTime(seenByUser = false))
        assertEquals(0L, PreferenceHelper.getLastCheckedFeedTime(seenByUser = true))

        val newStream =
            streamItem(id = "brand_new", channel = "/channel/UC_CHAN_1", uploaded = 6_000L)
        val nextLastFeedCheckMillis = PreferenceHelper.getLastCheckedFeedTime(seenByUser = false)
        assertFalse(
            seedInitialNotificationFeedTimeIfFirstRun(
                nextLastFeedCheckMillis,
                listOf(newStream, historicalNewest, historicalOlder),
            ),
        )

        val grouped =
            groupNewStreamsForNotifications(
                videoFeed = listOf(newStream, historicalNewest, historicalOlder),
                lastFeedCheckMillis = nextLastFeedCheckMillis,
                enableShortsNotification = false,
                channelsToIgnore = emptyList(),
            )
        assertEquals(mapOf("UC_CHAN_1" to listOf(newStream)), grouped)
    }

    @Test
    fun `seedInitialNotificationFeedTimeIfFirstRun handles empty feed on first run`() {
        assertTrue(seedInitialNotificationFeedTimeIfFirstRun(0L, emptyList()))
        assertEquals(0L, PreferenceHelper.getLastCheckedFeedTime(seenByUser = false))
    }

    private fun streamItem(
        id: String,
        channel: String?,
        uploaded: Long,
        isShort: Boolean = false,
    ) =
        StreamItem(
            url = if (isShort) "/shorts/$id" else "/watch?v=$id",
            title = id,
            uploaderUrl = channel,
            uploaded = uploaded,
            isShort = isShort,
        )
}
