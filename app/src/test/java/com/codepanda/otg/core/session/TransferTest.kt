package com.codepanda.otg.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTest {

    private fun transfer(
        transferred: Long,
        total: Long,
        state: TransferState = TransferState.RUNNING,
        startedAtMs: Long = 0,
        finishedAtMs: Long? = null,
    ) = Transfer(
        id = 1,
        label = "video.mp4",
        direction = TransferDirection.DOWNLOAD,
        transferred = transferred,
        total = total,
        state = state,
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
    )

    @Test
    fun `fraction drives the progress bar and is clamped`() {
        assertEquals(0.5f, transfer(50, 100).fraction!!, 0.0001f)
        assertEquals(1f, transfer(150, 100).fraction!!, 0.0001f)
    }

    @Test
    fun `an unknown total means an indeterminate bar, not a fake percentage`() {
        assertNull(transfer(1024, 0).fraction)
    }

    @Test
    fun `speed is measured over the transfer's own lifetime`() {
        val finished = transfer(
            transferred = 2_000_000,
            total = 2_000_000,
            state = TransferState.SUCCESS,
            startedAtMs = 1_000,
            finishedAtMs = 3_000,
        )
        assertEquals(1_000_000, finished.bytesPerSecond)
    }

    @Test
    fun `the summary reads as bytes of bytes plus a rate while running`() {
        val running = transfer(
            transferred = 1024 * 512,
            total = 1024 * 1024,
            startedAtMs = System.currentTimeMillis() - 1_000,
        )
        val summary = running.summary
        assertTrue(summary, summary.startsWith("512.0 KB of 1.0 MB"))
        assertTrue(summary, summary.contains("/s"))
    }

    @Test
    fun `finished states are distinguishable from running ones`() {
        assertTrue(!transfer(1, 2).isFinished)
        assertTrue(transfer(2, 2, TransferState.SUCCESS).isFinished)
        assertTrue(transfer(1, 2, TransferState.FAILED).isFinished)
        assertTrue(transfer(1, 2, TransferState.CANCELLED).isFinished)
    }
}
