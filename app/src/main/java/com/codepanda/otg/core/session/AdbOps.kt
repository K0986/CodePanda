package com.codepanda.otg.core.session

import com.codepanda.otg.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * The gatekeeper for every request that touches the USB cable.
 *
 * Three guarantees, each of which fixes a real failure mode:
 *
 *  1. **Serialised.** Short protocol operations run one at a time. ADB can
 *     multiplex streams, but our own reader/writer plus a device's `adbd` do not
 *     enjoy a file listing, a package dump and a screenshot arriving in the same
 *     millisecond — interleaving them is how the connection used to wedge the
 *     moment you switched tabs mid-load.
 *  2. **Interruptible.** Work runs inside [runInterruptible], so a cancelled
 *     coroutine actually interrupts the blocking read instead of leaving a
 *     thread parked on a stream that adbd then refuses to let go of.
 *  3. **Bounded.** Everything has a deadline and everything is logged with its
 *     duration, so "it hangs" becomes "LIST /sdcard timed out after 60s".
 */
class AdbOps {

    private val mutex = Mutex()

    /** True while an operation holds the cable. Surfaced for diagnostics. */
    val isBusy: Boolean get() = mutex.isLocked

    /**
     * Run [block] exclusively.
     *
     * @param label short description used in logs and error messages
     * @param timeoutMs deadline for the whole operation; transfers pass a large
     *   value because their liveness is enforced per-read by the stream itself
     */
    suspend fun <T> run(
        label: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: () -> T,
    ): T {
        val queuedAt = System.currentTimeMillis()
        return mutex.withLock {
            val startedAt = System.currentTimeMillis()
            val waited = startedAt - queuedAt
            if (waited > SLOW_QUEUE_MS) {
                AppLog.d(TAG, "$label waited ${waited}ms for the cable")
            }
            try {
                withTimeout(timeoutMs) {
                    runInterruptible(Dispatchers.IO) { block() }
                }.also {
                    AppLog.v(TAG, "$label ok in ${System.currentTimeMillis() - startedAt}ms")
                }
            } catch (timeout: TimeoutCancellationException) {
                AppLog.e(TAG, "$label timed out after ${timeoutMs}ms")
                throw com.codepanda.otg.adb.AdbTimeoutException("$label timed out after ${timeoutMs / 1000}s")
            } catch (cancelled: CancellationException) {
                AppLog.d(TAG, "$label cancelled after ${System.currentTimeMillis() - startedAt}ms")
                throw cancelled
            } catch (t: Throwable) {
                AppLog.e(TAG, "$label failed after ${System.currentTimeMillis() - startedAt}ms", t)
                throw t
            }
        }
    }

    companion object {
        private const val TAG = "AdbOps"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val SLOW_QUEUE_MS = 500L

        /** Generous deadline for file transfers, which can legitimately take minutes. */
        const val TRANSFER_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
