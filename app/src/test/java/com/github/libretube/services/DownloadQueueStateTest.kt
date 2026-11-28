package com.github.libretube.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueStateTest {

    @Test
    fun `nextQueuedId never returns PAUSED or FAILED items`() {
        val tracker = DownloadQueueTracker()
        tracker.enqueue(1)
        tracker.enqueue(2)
        tracker.enqueue(3)

        tracker.markPaused(1)
        tracker.markFailed(2)

        assertEquals(3, tracker.nextQueuedId())

        tracker.markRunning(3)
        assertNull(tracker.nextQueuedId())
    }

    @Test
    fun `QUEUED items are promoted in insertion order`() {
        val tracker = DownloadQueueTracker()
        tracker.enqueue(10)
        tracker.enqueue(20)
        tracker.enqueue(30)

        assertEquals(10, tracker.nextQueuedId())
        tracker.markRunning(10)

        assertEquals(20, tracker.nextQueuedId())
        tracker.markRunning(20)

        assertEquals(30, tracker.nextQueuedId())
        tracker.markRunning(30)

        assertNull(tracker.nextQueuedId())
    }

    @Test
    fun `RUNNING items report activeCount and isRunning accurately`() {
        val tracker = DownloadQueueTracker()
        assertEquals(0, tracker.activeCount())

        tracker.enqueue(1)
        tracker.enqueue(2)
        tracker.enqueue(3)
        tracker.markPaused(2)
        tracker.markFailed(3)

        assertEquals(0, tracker.activeCount())

        tracker.markRunning(1)
        assertEquals(1, tracker.activeCount())
        assertTrue(tracker.isRunning(1))
        assertFalse(tracker.isRunning(2))
        assertFalse(tracker.isRunning(3))

        // Enqueueing an already-running item must not downgrade it back to QUEUED.
        tracker.enqueue(1)
        assertTrue(tracker.isRunning(1))
        assertEquals(1, tracker.activeCount())

        tracker.remove(1)
        assertEquals(0, tracker.activeCount())
        assertNull(tracker.stateOf(1))
    }

    @Test
    fun `re-enqueueing a paused or failed item makes it eligible for nextQueuedId`() {
        val tracker = DownloadQueueTracker()
        tracker.enqueue(5)
        tracker.markPaused(5)
        assertNull(tracker.nextQueuedId())

        tracker.enqueue(5)
        assertEquals(5, tracker.nextQueuedId())

        tracker.markFailed(5)
        assertNull(tracker.nextQueuedId())

        tracker.enqueue(5)
        assertEquals(5, tracker.nextQueuedId())

        tracker.clear()
        assertEquals(0, tracker.activeCount())
        assertNull(tracker.stateOf(5))
        assertNull(tracker.nextQueuedId())
    }
}
