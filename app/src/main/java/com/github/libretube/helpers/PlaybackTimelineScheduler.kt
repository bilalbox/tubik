package com.github.libretube.helpers

import android.os.Handler
import androidx.core.os.postDelayed
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.enums.SbSkipOptions

interface DelayedTaskScheduler {
    fun schedule(token: String, delayMs: Long, action: () -> Unit)

    fun cancel(token: String)

    fun cancelAll()
}

class HandlerDelayedTaskScheduler(
    private val handler: Handler,
) : DelayedTaskScheduler {
    override fun schedule(token: String, delayMs: Long, action: () -> Unit) {
        handler.postDelayed(delayMs, token, action)
    }

    override fun cancel(token: String) {
        handler.removeCallbacksAndMessages(token)
    }

    override fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
    }
}

class PlaybackTimelineScheduler(
    private val taskScheduler: DelayedTaskScheduler,
) {
    fun evaluateAndScheduleSegments(
        currentPositionMs: Long,
        playbackSpeed: Float,
        isPlaying: Boolean,
        sponsorBlockEnabled: Boolean,
        autoSkipEnabled: Boolean,
        segments: List<Segment>,
        sponsorBlockConfig: Map<String, SbSkipOptions>,
        includeSegmentEnd: Boolean = true,
        onAutoSkip: (Segment, Long) -> Unit = { _, _ -> },
        onReschedule: () -> Unit = {},
    ): Pair<Segment, SbSkipOptions>? {
        taskScheduler.cancel(SEGMENT_TOKEN)
        if (!sponsorBlockEnabled || segments.isEmpty()) return null

        var effectivePositionMs = currentPositionMs
        val currentMatch =
            getCurrentSegmentForPosition(
                effectivePositionMs,
                segments,
                sponsorBlockConfig,
            )

        if (currentMatch != null) {
            val (segment, skipOption) = currentMatch
            if (
                autoSkipEnabled &&
                    skipOption in arrayOf(SbSkipOptions.AUTOMATIC, SbSkipOptions.AUTOMATIC_ONCE)
            ) {
                val targetPositionMs = segment.boundsMs().second
                segment.skipped = true
                onAutoSkip(segment, targetPositionMs)
                effectivePositionMs = targetPositionMs
            }
        }

        if (isPlaying) {
            val nextDelayMs =
                computeNextSegmentCheckDelayMs(
                    currentPositionMs = effectivePositionMs,
                    playbackSpeed = playbackSpeed,
                    segments = segments,
                    sponsorBlockConfig = sponsorBlockConfig,
                    includeSegmentEnd = includeSegmentEnd,
                )
            if (nextDelayMs != null) {
                taskScheduler.schedule(SEGMENT_TOKEN, nextDelayMs, onReschedule)
            }
        }

        return currentMatch
    }

    fun evaluateAndScheduleChapters(
        currentPositionMs: Long,
        playbackSpeed: Float,
        isPlaying: Boolean,
        isViewAttached: Boolean,
        chapters: List<ChapterSegment>,
        onReschedule: () -> Unit = {},
    ): Int? {
        taskScheduler.cancel(CHAPTER_TOKEN)
        if (chapters.isEmpty()) return null

        val currentIndex = getCurrentChapterIndex(currentPositionMs, chapters)

        if (isPlaying && isViewAttached) {
            val nextDelayMs =
                computeNextChapterCheckDelayMs(
                    currentPositionMs = currentPositionMs,
                    playbackSpeed = playbackSpeed,
                    chapters = chapters,
                )
            if (nextDelayMs != null) {
                taskScheduler.schedule(CHAPTER_TOKEN, nextDelayMs, onReschedule)
            }
        }

        return currentIndex
    }

    fun evaluateAndSchedulePosition(
        currentPositionMs: Long,
        playbackSpeed: Float,
        isPlaying: Boolean,
        isViewActive: Boolean,
        onReschedule: () -> Unit = {},
    ): Long? {
        taskScheduler.cancel(POSITION_TOKEN)
        val nextDelayMs =
            computeNextPositionUpdateDelayMs(
                currentPositionMs = currentPositionMs,
                playbackSpeed = playbackSpeed,
                isPlaying = isPlaying,
                isViewActive = isViewActive,
            )
        if (nextDelayMs != null) {
            taskScheduler.schedule(POSITION_TOKEN, nextDelayMs, onReschedule)
        }
        return nextDelayMs
    }

    fun cancel(token: String) {
        taskScheduler.cancel(token)
    }

    fun cancelAll() {
        taskScheduler.cancelAll()
    }

    private fun getCurrentSegmentForPosition(
        currentPositionMs: Long,
        segments: List<Segment>,
        sponsorBlockConfig: Map<String, SbSkipOptions>,
    ): Pair<Segment, SbSkipOptions>? =
        segments
            .asSequence()
            .filter { it.category != PlayerHelper.SPONSOR_HIGHLIGHT_CATEGORY }
            .mapNotNull { segment ->
                val (segmentStart, segmentEnd) = segment.boundsMs()
                val skipOption = sponsorBlockConfig[segment.category]
                // avoid seeking to the same segment multiple times, e.g. when the SB segment is
                // at the end of the video
                val isEligible =
                    segmentEnd - currentPositionMs !in 0..END_OF_SEGMENT_GUARD_MS &&
                        currentPositionMs in segmentStart until segmentEnd &&
                        !(skipOption == SbSkipOptions.AUTOMATIC_ONCE && segment.skipped)
                if (isEligible) segment to (skipOption ?: SbSkipOptions.AUTOMATIC) else null
            }
            .firstOrNull()

    private fun computeNextSegmentCheckDelayMs(
        currentPositionMs: Long,
        playbackSpeed: Float,
        segments: List<Segment>,
        sponsorBlockConfig: Map<String, SbSkipOptions>,
        includeSegmentEnd: Boolean,
    ): Long? {
        if (segments.isEmpty() || currentPositionMs < 0L) return null

        val nextBoundaryMs =
            segments
                .asSequence()
                .mapNotNull { segment ->
                    nextSegmentBoundaryMs(
                        segment = segment,
                        currentPositionMs = currentPositionMs,
                        sponsorBlockConfig = sponsorBlockConfig,
                        includeSegmentEnd = includeSegmentEnd,
                    )
                }
                .minOrNull()

        return nextBoundaryMs?.let { scaleDelayMs(it - currentPositionMs, playbackSpeed) }
    }

    private fun getCurrentChapterIndex(
        currentPositionMs: Long,
        chapters: List<ChapterSegment>,
    ): Int? {
        val currentPositionSeconds = currentPositionMs / MS_PER_SECOND_LONG
        return chapters
            .sortedBy { it.start }
            .indexOfLast { currentPositionSeconds >= it.start }
            .takeIf { it >= 0 }
            ?.takeIf { index ->
                val chapter = chapters[index]
                // remove the video highlight if it's already longer ago than
                // [ChapterSegment.HIGHLIGHT_LENGTH],
                // otherwise the SponsorBlock highlight would be shown from its starting point to
                // the end
                val isWithinMaxHighlightDuration =
                    (currentPositionSeconds - chapter.start) < ChapterSegment.HIGHLIGHT_LENGTH
                chapter.highlightDrawable == null || isWithinMaxHighlightDuration
            }
    }

    private fun computeNextChapterCheckDelayMs(
        currentPositionMs: Long,
        playbackSpeed: Float,
        chapters: List<ChapterSegment>,
    ): Long? {
        if (chapters.isEmpty() || currentPositionMs < 0L) return null

        val nextBoundaryMs =
            chapters
                .asSequence()
                .flatMap { chapter ->
                    val startMs = chapter.start * MS_PER_SECOND_LONG
                    val highlightEndMs =
                        if (chapter.highlightDrawable != null) {
                            (chapter.start + ChapterSegment.HIGHLIGHT_LENGTH) * MS_PER_SECOND_LONG
                        } else {
                            null
                        }
                    sequenceOf(startMs, highlightEndMs).filterNotNull()
                }
                .filter { it > currentPositionMs }
                .minOrNull()

        return nextBoundaryMs?.let { scaleDelayMs(it - currentPositionMs, playbackSpeed) }
    }

    private fun computeNextPositionUpdateDelayMs(
        currentPositionMs: Long,
        playbackSpeed: Float,
        isPlaying: Boolean,
        isViewActive: Boolean,
    ): Long? {
        if (!isPlaying || !isViewActive || currentPositionMs < 0L) return null
        val remainderMs = currentPositionMs.mod(MS_PER_SECOND_LONG)
        val mediaMsToNextSecond = MS_PER_SECOND_LONG - remainderMs
        return scaleDelayMs(
                mediaDeltaMs = mediaMsToNextSecond,
                playbackSpeed = playbackSpeed,
                minDelayMs = MIN_POSITION_UPDATE_DELAY_MS,
            )
            .coerceAtMost(MS_PER_SECOND_LONG)
    }

    companion object {
        const val SEGMENT_TOKEN = "sponsorBlockSegmentCheck"
        const val CHAPTER_TOKEN = "chapterBoundaryCheck"
        const val POSITION_TOKEN = "positionUpdateCheck"

        internal const val MS_PER_SECOND = 1000f
        private const val MS_PER_SECOND_LONG = 1000L
        private const val END_OF_SEGMENT_GUARD_MS = 1000L
        internal const val MIN_SCHEDULE_DELAY_MS = 16L
        private const val MIN_POSITION_UPDATE_DELAY_MS = 100L
        internal const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }
}

private fun nextSegmentBoundaryMs(
    segment: Segment,
    currentPositionMs: Long,
    sponsorBlockConfig: Map<String, SbSkipOptions>,
    includeSegmentEnd: Boolean,
): Long? {
    val skipOption = sponsorBlockConfig[segment.category] ?: SbSkipOptions.AUTOMATIC
    val isActive =
        segment.category != PlayerHelper.SPONSOR_HIGHLIGHT_CATEGORY &&
            skipOption != SbSkipOptions.OFF &&
            !(skipOption == SbSkipOptions.AUTOMATIC_ONCE && segment.skipped)
    if (!isActive) return null

    val (startMs, endMs) = segment.boundsMs()

    return when {
        startMs > currentPositionMs -> startMs
        includeSegmentEnd && currentPositionMs in startMs until endMs -> endMs
        else -> null
    }
}

private fun Segment.boundsMs(): Pair<Long, Long> {
    val (start, end) = segmentStartAndEnd
    return (start * PlaybackTimelineScheduler.MS_PER_SECOND).toLong() to
        (end * PlaybackTimelineScheduler.MS_PER_SECOND).toLong()
}

private fun scaleDelayMs(
    mediaDeltaMs: Long,
    playbackSpeed: Float,
    minDelayMs: Long = PlaybackTimelineScheduler.MIN_SCHEDULE_DELAY_MS,
): Long {
    val safeSpeed =
        playbackSpeed.takeIf { it > 0f } ?: PlaybackTimelineScheduler.DEFAULT_PLAYBACK_SPEED
    return (mediaDeltaMs / safeSpeed).toLong().coerceAtLeast(minDelayMs)
}
