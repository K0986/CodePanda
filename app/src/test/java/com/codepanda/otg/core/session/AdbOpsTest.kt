package com.codepanda.otg.core.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [AdbOps] is the guard that stops two screens from talking over each other on
 * one USB cable. These tests pin the properties that make the app survive a user
 * who taps through the tabs while a command is in flight.
 */
class AdbOpsTest {

    @Test
    fun `operations never overlap`() = runBlocking {
        val ops = AdbOps()
        val inFlight = AtomicInteger()
        val maxObserved = AtomicInteger()

        withContext(Dispatchers.Default) {
            (1..8).map { index ->
                launch {
                    ops.run("op-$index") {
                        val now = inFlight.incrementAndGet()
                        maxObserved.updateAndGet { maxOf(it, now) }
                        Thread.sleep(5)
                        inFlight.decrementAndGet()
                    }
                }
            }.forEach { it.join() }
        }

        assertEquals("operations ran concurrently", 1, maxObserved.get())
    }

    @Test
    fun `a hung operation times out instead of blocking forever`() = runBlocking {
        val ops = AdbOps()

        val failure = runCatching {
            ops.run("hang", timeoutMs = 100) { Thread.sleep(10_000) }
        }.exceptionOrNull()

        assertTrue(
            "expected a timeout, got $failure",
            failure is com.codepanda.otg.adb.AdbTimeoutException,
        )
    }

    @Test
    fun `a timed-out operation releases the cable for the next one`() = runBlocking {
        val ops = AdbOps()
        runCatching { ops.run("hang", timeoutMs = 100) { Thread.sleep(10_000) } }

        val result = withTimeoutOrNull(5_000) { ops.run("next") { "ok" } }

        assertEquals("ok", result)
    }

    @Test
    fun `failures propagate with their original cause`() = runBlocking {
        val ops = AdbOps()

        val failure = runCatching {
            ops.run("boom") { throw IllegalStateException("device refused") }
        }.exceptionOrNull()

        assertTrue("got $failure", failure is IllegalStateException)
        assertEquals("device refused", failure?.message)
    }

    @Test
    fun `cancelling one caller does not strand the lock`() = runBlocking {
        val ops = AdbOps()
        val started = CompletableDeferred<Unit>()

        val victim = async(Dispatchers.Default) {
            ops.run("long", timeoutMs = 30_000) {
                started.complete(Unit)
                Thread.sleep(10_000)
            }
        }
        started.await()
        victim.cancel()

        // The blocking body runs inside runInterruptible, so cancelling the
        // caller interrupts the I/O and frees the cable for the next request.
        val result = withTimeoutOrNull(5_000) { ops.run("after") { "free" } }
        assertEquals("free", result)
    }
}
