package com.codepanda.otg.feature.files

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
 * Session-scoped state of the file browser: where we are, what is there, and a
 * small cache of directories we have already read.
 *
 * Keeping this outside the ViewModel is what makes the browser survive tab
 * switches. Previously the ViewModel re-listed `/sdcard` in its `init` every
 * time the screen came back, and a listing interrupted by navigation left the
 * screen stuck on a spinner because nothing was left running to finish it.
 *
 * The cache is a bounded LRU: going back up a tree is instant, and a device with
 * a hundred thousand files cannot make the app grow without limit.
 */
class FileBrowser(
    private val scope: CoroutineScope,
    private val repo: FileRepository,
) {
    private val _path = MutableStateFlow(START_PATH)
    val path: StateFlow<String> = _path.asStateFlow()

    private val _listing = MutableStateFlow<Async<List<FileItem>>>(Async.Idle)
    val listing: StateFlow<Async<List<FileItem>>> = _listing.asStateFlow()

    private val cache = object : LinkedHashMap<String, List<FileItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileItem>>) =
            size > MAX_CACHED_DIRECTORIES
    }

    private var job: Job? = null

    /** Show [target], from cache when possible. */
    fun open(target: String, force: Boolean = false) {
        val normalised = normalise(target)
        _path.value = normalised

        if (!force) {
            val cached = synchronized(cache) { cache[normalised] }
            if (cached != null) {
                _listing.value = Async.Success(cached)
                AppLog.v(TAG, "served $normalised from cache (${cached.size} entries)")
                return
            }
        }
        load(normalised)
    }

    fun refresh() = open(_path.value, force = true)

    fun goUp() {
        val current = _path.value
        if (current == "/") return
        val parent = current.trimEnd('/').substringBeforeLast('/', "")
        open(if (parent.isEmpty()) "/" else parent)
    }

    /** Forget a directory so the next visit re-reads it (after a delete, say). */
    fun invalidate(directory: String = _path.value) {
        synchronized(cache) { cache.remove(normalise(directory)) }
    }

    private fun load(target: String) {
        job?.cancel()
        _listing.value = Async.Loading
        job = scope.launch {
            try {
                val items = repo.list(target)
                synchronized(cache) { cache[target] = items }
                // Only publish if the user has not navigated elsewhere meanwhile.
                if (_path.value == target) _listing.value = Async.Success(items)
            } catch (cancelled: CancellationException) {
                if (_path.value == target) _listing.value = Async.Idle
                throw cancelled
            } catch (t: Throwable) {
                if (_path.value == target) {
                    _listing.value = Async.Failure(t.message ?: "Could not list $target")
                }
            }
        }
    }

    private fun normalise(path: String): String =
        if (path.length > 1) path.trimEnd('/') else path

    companion object {
        const val START_PATH = "/sdcard"
        private const val TAG = "FileBrowser"
        private const val MAX_CACHED_DIRECTORIES = 24
    }
}
