package com.codepanda.otg.core.session

import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.ui.Async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Session-owned state for one feature (the package list, the debloat list, the
 * device snapshot, one directory of the file browser…).
 *
 * The screens used to own this state in their ViewModels and reload it in
 * `init`, which produced two bugs at once: switching tabs re-enumerated
 * everything from scratch, and a load interrupted by that switch left the
 * screen's state pinned on `Loading` with nothing left running to move it on.
 *
 * Hoisting the state into the session fixes both. The store:
 *
 *  - runs its loader in the **session** scope, so navigation cannot cancel it;
 *  - is **single-flight**: a second request while one is in flight is a no-op;
 *  - **caches** the result, so returning to a tab is instant and re-enumeration
 *    only happens when the user asks for it (or an operation invalidates it);
 *  - keeps exactly one copy of the data per device, bounding memory.
 */
class FeatureStore<T>(
    private val name: String,
    private val scope: CoroutineScope,
    private val loader: suspend () -> T,
) {
    private val _state = MutableStateFlow<Async<T>>(Async.Idle)
    val state: StateFlow<Async<T>> = _state.asStateFlow()

    private var job: Job? = null

    /** Load unless data is already present or a load is running. */
    fun ensureLoaded() {
        if (_state.value is Async.Success) return
        load(force = false)
    }

    /** Reload, discarding any cached value. */
    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        if (job?.isActive == true) {
            AppLog.v(TAG, "$name load already in flight; ignoring duplicate request")
            return
        }
        if (!force && _state.value is Async.Success) return

        _state.value = Async.Loading
        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val data = loader()
                _state.value = Async.Success(data)
                AppLog.i(TAG, "$name loaded in ${System.currentTimeMillis() - startedAt}ms")
            } catch (cancelled: CancellationException) {
                // The session itself is going away (disconnect); leave no spinner.
                _state.value = Async.Idle
                throw cancelled
            } catch (t: Throwable) {
                val message = t.message ?: "Failed to load $name"
                _state.value = Async.Failure(message)
                AppLog.e(TAG, "$name failed after ${System.currentTimeMillis() - startedAt}ms", t)
            }
        }
    }

    /** Patch the cached value in place, e.g. after toggling one package. */
    fun update(transform: (T) -> T) {
        val current = _state.value
        if (current is Async.Success) {
            _state.value = Async.Success(transform(current.data))
        }
    }

    /** Drop the cache so the next [ensureLoaded] re-reads the device. */
    fun invalidate() {
        _state.value = Async.Idle
    }

    companion object {
        private const val TAG = "FeatureStore"
    }
}
