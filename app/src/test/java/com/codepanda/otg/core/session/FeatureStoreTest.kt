package com.codepanda.otg.core.session

import com.codepanda.otg.ui.Async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The behaviour that fixes "switch tabs and it spins forever".
 *
 * A feature's data is loaded once, cached, de-duplicated, and owned by the
 * session rather than by the screen that asked for it. Real dispatchers are used
 * on purpose: the point of the store is how it behaves when a load is genuinely
 * in flight while another screen asks for the same data.
 */
class FeatureStoreTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun <T> StateFlow<Async<T>>.awaitSuccess(): T =
        withTimeout(TIMEOUT_MS) { first { it is Async.Success } }.let {
            (it as Async.Success).data
        }

    private suspend fun <T> StateFlow<Async<T>>.awaitFailure(): String =
        withTimeout(TIMEOUT_MS) { first { it is Async.Failure } }.let {
            (it as Async.Failure).message
        }

    @Test
    fun `loads once and serves the cache afterwards`() = runBlocking {
        val loads = AtomicInteger()
        val store = FeatureStore("packages", scope) {
            loads.incrementAndGet()
            listOf("com.example")
        }

        store.ensureLoaded()
        assertEquals(listOf("com.example"), store.state.awaitSuccess())

        // Re-entering the screen must not re-enumerate the device.
        store.ensureLoaded()
        store.ensureLoaded()
        assertEquals(1, loads.get())
    }

    @Test
    fun `refresh re-reads the device`() = runBlocking {
        val loads = AtomicInteger()
        val store = FeatureStore("packages", scope) { listOf("load-${loads.incrementAndGet()}") }

        store.ensureLoaded()
        assertEquals(listOf("load-1"), store.state.awaitSuccess())

        store.refresh()
        withTimeout(TIMEOUT_MS) { store.state.first { it == Async.Success(listOf("load-2")) } }
        assertEquals(2, loads.get())
    }

    @Test
    fun `concurrent requests collapse into one load`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val store = FeatureStore("files", scope) {
            loads.incrementAndGet()
            started.complete(Unit)
            gate.await()
            listOf("a")
        }

        store.ensureLoaded()
        started.await()
        store.ensureLoaded()
        store.refresh()

        assertEquals("a second request must not start a second load", 1, loads.get())
        assertTrue(store.state.value is Async.Loading)

        gate.complete(Unit)
        assertEquals(listOf("a"), store.state.awaitSuccess())
    }

    @Test
    fun `a failure becomes a message, never a permanent spinner`() = runBlocking {
        val store = FeatureStore<List<String>>("packages", scope) {
            throw IllegalStateException("device refused")
        }

        store.ensureLoaded()
        assertEquals("device refused", store.state.awaitFailure())

        // A retry is possible because the store is not in a "loaded" state.
        store.ensureLoaded()
        assertEquals("device refused", store.state.awaitFailure())
    }

    @Test
    fun `update patches the cache without a round trip`() = runBlocking {
        val loads = AtomicInteger()
        val store = FeatureStore("packages", scope) {
            loads.incrementAndGet()
            listOf("a", "b")
        }
        store.ensureLoaded()
        store.state.awaitSuccess()

        store.update { it + "c" }

        assertEquals(listOf("a", "b", "c"), store.state.value.dataOrNull)
        assertEquals(1, loads.get())
    }

    @Test
    fun `invalidate forces the next visit to reload`() = runBlocking {
        val loads = AtomicInteger()
        val store = FeatureStore("packages", scope) { listOf("load-${loads.incrementAndGet()}") }
        store.ensureLoaded()
        store.state.awaitSuccess()

        store.invalidate()
        assertEquals(Async.Idle, store.state.value)

        store.ensureLoaded()
        withTimeout(TIMEOUT_MS) { store.state.first { it == Async.Success(listOf("load-2")) } }
        assertEquals(2, loads.get())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
