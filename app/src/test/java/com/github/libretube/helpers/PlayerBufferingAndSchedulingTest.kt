package com.github.libretube.helpers

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.util.PlayingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerBufferingAndSchedulingTest {

    @Test
    fun `getLoadControl bounds back-buffer to 30s and stops loading when 32MB heap target is reached`() {
        val loadControl: LoadControl = PlayerHelper.getLoadControl(bufferingGoalMs = 300_000)
        val playerId = PlayerId.UNSET
        loadControl.onPrepared(playerId)

        assertEquals(30_000_000L, loadControl.getBackBufferDurationUs(playerId))
        assertFalse(loadControl.retainBackBufferFromKeyframe(playerId))

        val allocator = loadControl.getAllocator(playerId)
        val timeline =
            SinglePeriodTimeline(
                /* durationUs = */ 600_000_000L,
                /* isSeekable = */ true,
                /* isDynamic = */ false,
                /* useLiveConfiguration = */ false,
                /* manifest = */ null,
                MediaItem.Builder().build(),
            )
        val mediaPeriodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

        val underBudgetParams =
            LoadControl.Parameters(
                playerId,
                timeline,
                mediaPeriodId,
                /* playbackPositionUs = */ 0L,
                /* bufferedDurationUs = */ 5_000_000L,
                /* playbackSpeed = */ 1.0f,
                /* playWhenReady = */ true,
                /* rebuffering = */ false,
                /* targetLiveOffsetUs = */ C.TIME_UNSET,
                /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
            )
        loadControl.onTracksSelected(underBudgetParams, TrackGroupArray.EMPTY, emptyArray())
        assertTrue(loadControl.shouldContinueLoading(underBudgetParams))

        val segmentCountFor32Mb =
            PlayerHelper.TARGET_BUFFER_BYTES / allocator.individualAllocationLength
        val allocations = Array(segmentCountFor32Mb) { allocator.allocate() }
        assertEquals(PlayerHelper.TARGET_BUFFER_BYTES, allocator.totalBytesAllocated)

        val overHeapBudgetParams =
            LoadControl.Parameters(
                playerId,
                timeline,
                mediaPeriodId,
                /* playbackPositionUs = */ 0L,
                /* bufferedDurationUs = */ 20_000_000L,
                /* playbackSpeed = */ 1.0f,
                /* playWhenReady = */ true,
                /* rebuffering = */ false,
                /* targetLiveOffsetUs = */ C.TIME_UNSET,
                /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
            )
        assertFalse(loadControl.shouldContinueLoading(overHeapBudgetParams))

        allocations.forEach { allocator.release(it) }
        loadControl.onReleased(playerId)
    }

    @Test
    fun `SponsorBlock segment scheduler triggers at exact boundaries and never leaks duplicate timers across queue switches or pause`() {
        val fakeQueue = FakeDelayedTaskQueue()
        val scheduler = PlaybackTimelineScheduler(fakeQueue)

        val video1Segments =
            listOf(
                createSegment(uuid = "seg-1", startSec = 10f, endSec = 20f, category = "sponsor"),
                createSegment(uuid = "seg-2", startSec = 30f, endSec = 35f, category = "selfpromo"),
            )
        val config =
            mapOf(
                "sponsor" to SbSkipOptions.AUTOMATIC,
                "selfpromo" to SbSkipOptions.MANUAL,
            )

        val autoSkippedTargets = mutableListOf<Long>()

        // Video 1 starts at 2.0s at 1.0x speed -> schedules exact 8_000ms delay for 10.0s boundary
        scheduler.evaluateAndScheduleSegments(
            currentPositionMs = 2_000L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            sponsorBlockEnabled = true,
            autoSkipEnabled = true,
            segments = video1Segments,
            sponsorBlockConfig = config,
            includeSegmentEnd = true,
            onAutoSkip = { _, targetMs -> autoSkippedTargets.add(targetMs) },
        )
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.SEGMENT_TOKEN))
        assertEquals(8_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.SEGMENT_TOKEN))

        // Speed changes to 2.0x at 2.0s -> replaces existing callback with 4_000ms delay (no
        // duplicate timer)
        scheduler.evaluateAndScheduleSegments(
            currentPositionMs = 2_000L,
            playbackSpeed = 2.0f,
            isPlaying = true,
            sponsorBlockEnabled = true,
            autoSkipEnabled = true,
            segments = video1Segments,
            sponsorBlockConfig = config,
            includeSegmentEnd = true,
            onAutoSkip = { _, targetMs -> autoSkippedTargets.add(targetMs) },
        )
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.SEGMENT_TOKEN))
        assertEquals(4_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.SEGMENT_TOKEN))

        // Reach 10.0s boundary at 1.0x -> auto-skips to 20_000ms and schedules next check for 30.0s
        // (10_000ms delay)
        scheduler.evaluateAndScheduleSegments(
            currentPositionMs = 10_000L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            sponsorBlockEnabled = true,
            autoSkipEnabled = true,
            segments = video1Segments,
            sponsorBlockConfig = config,
            includeSegmentEnd = true,
            onAutoSkip = { _, targetMs -> autoSkippedTargets.add(targetMs) },
        )
        assertEquals(listOf(20_000L), autoSkippedTargets)
        assertTrue(video1Segments[0].skipped)
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.SEGMENT_TOKEN))
        assertEquals(10_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.SEGMENT_TOKEN))

        // Inside manual segment at 31.0s -> does not auto-skip, schedules check for end of manual
        // segment at 35.0s (4_000ms)
        val activeSegment =
            scheduler.evaluateAndScheduleSegments(
                currentPositionMs = 31_000L,
                playbackSpeed = 1.0f,
                isPlaying = true,
                sponsorBlockEnabled = true,
                autoSkipEnabled = true,
                segments = video1Segments,
                sponsorBlockConfig = config,
                includeSegmentEnd = true,
                onAutoSkip = { _, targetMs -> autoSkippedTargets.add(targetMs) },
            )
        assertEquals("seg-2", activeSegment?.first?.uuid)
        assertEquals(SbSkipOptions.MANUAL, activeSegment?.second)
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.SEGMENT_TOKEN))
        assertEquals(4_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.SEGMENT_TOKEN))

        // Switching across multiple videos in a queue replaces callback cleanly without
        // accumulating timers
        val video2Segments =
            listOf(
                createSegment(
                    uuid = "v2-seg-1",
                    startSec = 15f,
                    endSec = 25f,
                    category = "sponsor",
                ),
            )
        val video3Segments =
            listOf(
                createSegment(uuid = "v3-seg-1", startSec = 5f, endSec = 12f, category = "sponsor"),
            )
        scheduler.evaluateAndScheduleSegments(
            currentPositionMs = 0L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            sponsorBlockEnabled = true,
            autoSkipEnabled = true,
            segments = video2Segments,
            sponsorBlockConfig = config,
            includeSegmentEnd = false,
            onAutoSkip = { _, _ -> },
        )
        scheduler.evaluateAndScheduleSegments(
            currentPositionMs = 1_000L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            sponsorBlockEnabled = true,
            autoSkipEnabled = true,
            segments = video3Segments,
            sponsorBlockConfig = config,
            includeSegmentEnd = false,
            onAutoSkip = { _, _ -> },
        )
        assertEquals(1, fakeQueue.totalPendingCount())
        assertEquals(4_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.SEGMENT_TOKEN))

        // Pausing cancels the scheduled segment callback immediately
        scheduler.evaluateAndScheduleSegments(
            currentPositionMs = 1_000L,
            playbackSpeed = 1.0f,
            isPlaying = false,
            sponsorBlockEnabled = true,
            autoSkipEnabled = true,
            segments = video3Segments,
            sponsorBlockConfig = config,
            includeSegmentEnd = false,
            onAutoSkip = { _, _ -> },
        )
        assertEquals(0, fakeQueue.totalPendingCount())
        assertNull(fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.SEGMENT_TOKEN))
    }

    @Test
    fun `chapter and position schedulers align with exact boundaries and stop when paused hidden or detached`() {
        val fakeQueue = FakeDelayedTaskQueue()
        val scheduler = PlaybackTimelineScheduler(fakeQueue)

        val chapters =
            listOf(
                ChapterSegment(title = "Intro", start = 0L),
                ChapterSegment(title = "Part 1", start = 30L),
                ChapterSegment(title = "Conclusion", start = 90L),
            )

        // At 5.0s, 1.0x speed -> current chapter is 0 ("Intro"), next boundary is 30.0s (25_000ms
        // delay)
        val idxAt5s =
            scheduler.evaluateAndScheduleChapters(
                currentPositionMs = 5_000L,
                playbackSpeed = 1.0f,
                isPlaying = true,
                isViewAttached = true,
                chapters = chapters,
            )
        assertEquals(0, idxAt5s)
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.CHAPTER_TOKEN))
        assertEquals(25_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.CHAPTER_TOKEN))

        // Speed changes to 1.25x at 5.0s -> replaces callback with 20_000ms delay without
        // duplicating
        scheduler.evaluateAndScheduleChapters(
            currentPositionMs = 5_000L,
            playbackSpeed = 1.25f,
            isPlaying = true,
            isViewAttached = true,
            chapters = chapters,
        )
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.CHAPTER_TOKEN))
        assertEquals(20_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.CHAPTER_TOKEN))

        // In final chapter at 95.0s -> current chapter is 2 ("Conclusion"), no more boundaries so 0
        // callbacks scheduled
        val idxAt95s =
            scheduler.evaluateAndScheduleChapters(
                currentPositionMs = 95_000L,
                playbackSpeed = 1.0f,
                isPlaying = true,
                isViewAttached = true,
                chapters = chapters,
            )
        assertEquals(2, idxAt95s)
        assertEquals(0, fakeQueue.pendingCount(PlaybackTimelineScheduler.CHAPTER_TOKEN))

        // Position update when playing and controller visible at 10_250ms -> schedules 750ms to
        // next second
        scheduler.evaluateAndSchedulePosition(
            currentPositionMs = 10_250L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            isViewActive = true,
        )
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
        assertEquals(750L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))

        // Hiding controller or detaching view cancels position timer immediately
        scheduler.evaluateAndSchedulePosition(
            currentPositionMs = 10_500L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            isViewActive = false,
        )
        assertEquals(0, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
    }

    @Test
    fun `audio-only position scheduler aligns to second boundaries across speeds and clamps delay bounds`() {
        val fakeQueue = FakeDelayedTaskQueue()
        val scheduler = PlaybackTimelineScheduler(fakeQueue)

        // Playing at 10.25s at 1.0x -> 750ms until 11.0s
        val delayAt1x =
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = 10_250L,
                playbackSpeed = 1.0f,
                isPlaying = true,
                isViewActive = true,
            )
        assertEquals(750L, delayAt1x)
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
        assertEquals(750L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))

        // Speed increases to 2.0x at 10.25s -> 375ms, replacing previous task
        val delayAt2x =
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = 10_250L,
                playbackSpeed = 2.0f,
                isPlaying = true,
                isViewActive = true,
            )
        assertEquals(375L, delayAt2x)
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
        assertEquals(375L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))

        // Near second boundary (10.95s at 1.0x) -> clamped to 100ms minimum delay
        val clampedMinDelay =
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = 10_950L,
                playbackSpeed = 1.0f,
                isPlaying = true,
                isViewActive = true,
            )
        assertEquals(100L, clampedMinDelay)
        assertEquals(100L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))

        // Slow speed (0.5x at 10.0s) -> clamped to 1000ms maximum delay
        val clampedMaxDelay =
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = 10_000L,
                playbackSpeed = 0.5f,
                isPlaying = true,
                isViewActive = true,
            )
        assertEquals(1_000L, clampedMaxDelay)
        assertEquals(1_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))

        // Invalid non-positive playback speed falls back to 1.0x
        val fallbackSpeedDelay =
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = 10_400L,
                playbackSpeed = 0f,
                isPlaying = true,
                isViewActive = true,
            )
        assertEquals(600L, fallbackSpeedDelay)
        assertEquals(600L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))
    }

    @Test
    fun `audio-only position scheduler replaces timers on reschedule ticks and cancels independently of chapters`() {
        val fakeQueue = FakeDelayedTaskQueue()
        val scheduler = PlaybackTimelineScheduler(fakeQueue)

        val chapters =
            listOf(
                ChapterSegment(title = "Intro", start = 0L),
                ChapterSegment(title = "Next", start = 30L),
            )
        scheduler.evaluateAndScheduleChapters(
            currentPositionMs = 11_000L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            isViewAttached = true,
            chapters = chapters,
        )

        var ticks = 0
        fun simulateAudioTick(positionMs: Long) {
            ticks++
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = positionMs,
                playbackSpeed = 1.0f,
                isPlaying = true,
                isViewActive = true,
                onReschedule = { simulateAudioTick(12_000L) },
            )
        }
        simulateAudioTick(11_100L)
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
        assertEquals(900L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))

        fakeQueue.runPending(PlaybackTimelineScheduler.POSITION_TOKEN)
        assertEquals(2, ticks)
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
        assertEquals(1_000L, fakeQueue.pendingDelayMs(PlaybackTimelineScheduler.POSITION_TOKEN))
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.CHAPTER_TOKEN))

        // Unset / negative position or pause cancels only the position timer
        val unsetDelay =
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = C.TIME_UNSET,
                playbackSpeed = 1.0f,
                isPlaying = true,
                isViewActive = true,
            )
        assertNull(unsetDelay)
        assertEquals(0, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.CHAPTER_TOKEN))

        scheduler.evaluateAndSchedulePosition(
            currentPositionMs = 12_200L,
            playbackSpeed = 1.0f,
            isPlaying = true,
            isViewActive = true,
        )
        assertEquals(1, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))

        val pausedDelay =
            scheduler.evaluateAndSchedulePosition(
                currentPositionMs = 12_300L,
                playbackSpeed = 1.0f,
                isPlaying = false,
                isViewActive = true,
            )
        assertNull(pausedDelay)
        assertEquals(0, fakeQueue.pendingCount(PlaybackTimelineScheduler.POSITION_TOKEN))
    }

    @Test
    fun `PlayingQueue notifies listeners on queue mutations and stops notifying after listener removal`() {
        PlayingQueue.clear()
        var notifications = 0
        val States = mutableListOf<Pair<Boolean, Boolean>>()
        val listener: () -> Unit = {
            notifications++
            States.add(PlayingQueue.hasPrev() to PlayingQueue.hasNext())
        }

        PlayingQueue.addOnQueueChangedListener(listener)
        try {
            val item1 = StreamItem(url = "/watch?v=vid00000001", title = "Video 1")
            val item2 = StreamItem(url = "/watch?v=vid00000002", title = "Video 2")
            val item3 = StreamItem(url = "/watch?v=vid00000003", title = "Video 3")

            PlayingQueue.updateCurrent(item1)
            assertTrue(notifications >= 1)
            assertEquals(false to false, States.last())

            PlayingQueue.add(item2, item3)
            assertEquals(false to true, States.last())

            PlayingQueue.updateCurrent(item2)
            assertEquals(true to true, States.last())

            val countBeforeRemove = notifications
            PlayingQueue.removeOnQueueChangedListener(listener)

            PlayingQueue.updateCurrent(item3)
            PlayingQueue.clear()
            assertEquals(countBeforeRemove, notifications)
        } finally {
            PlayingQueue.removeOnQueueChangedListener(listener)
            PlayingQueue.clear()
        }
    }

    private fun createSegment(
        uuid: String,
        startSec: Float,
        endSec: Float,
        category: String,
        skipped: Boolean = false,
    ) =
        Segment(
            uuid = uuid,
            actionType = "skip",
            category = category,
            locked = 0,
            segment = listOf(startSec, endSec),
            videoDuration = 120.0,
            votes = 5,
            skipped = skipped,
        )
}

private class FakeDelayedTaskQueue : DelayedTaskScheduler {
    private data class ScheduledEntry(val delayMs: Long, val action: () -> Unit)

    private val tasks = mutableMapOf<String, MutableList<ScheduledEntry>>()

    override fun schedule(token: String, delayMs: Long, action: () -> Unit) {
        tasks.getOrPut(token) { mutableListOf() }.add(ScheduledEntry(delayMs, action))
    }

    override fun cancel(token: String) {
        tasks.remove(token)
    }

    override fun cancelAll() {
        tasks.clear()
    }

    fun runPending(token: String) {
        val pending = tasks.remove(token).orEmpty()
        pending.forEach { it.action() }
    }

    fun pendingCount(token: String): Int = tasks[token]?.size ?: 0

    fun pendingDelayMs(token: String): Long? = tasks[token]?.lastOrNull()?.delayMs

    fun totalPendingCount(): Int = tasks.values.sumOf { it.size }
}
