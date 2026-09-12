package com.codepanda.otg.core.session

import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class TransferDirection { DOWNLOAD, UPLOAD }

enum class TransferState { RUNNING, SUCCESS, FAILED, CANCELLED }

/** One file transfer, with enough detail to draw a progress bar and explain a failure. */
data class Transfer(
    val id: Long,
    val label: String,
    val direction: TransferDirection,
    val transferred: Long,
    val total: Long,
    val state: TransferState,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val destination: String? = null,
    val error: String? = null,
) {
    /** 0..1, or null while the total size is unknown (indeterminate bar). */
    val fraction: Float?
        get() = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else null

    val isFinished: Boolean get() = state != TransferState.RUNNING

    val bytesPerSecond: Long
        get() {
            val end = finishedAtMs ?: System.currentTimeMillis()
            val elapsed = end - startedAtMs
            return if (elapsed > 0) transferred * 1000 / elapsed else 0
        }

    val summary: String
        get() = buildString {
            append(LogFormat.bytes(transferred))
            if (total > 0) {
                append(" of ")
                append(LogFormat.bytes(total))
            }
            if (state == TransferState.RUNNING) {
                append(" · ")
                append(LogFormat.bytes(bytesPerSecond))
                append("/s")
            }
        }
}

/**
 * Owns running file transfers for the connected device.
 *
 * Transfers live in the **session**, not in a screen. Pulling a 900 MB video and
 * then tapping another tab used to cancel the pull (and leak its stream); now
 * the transfer keeps going, its progress stays visible in the top bar, and it
 * can be cancelled deliberately.
 *
 * The history is capped so a long session cannot grow without bound.
 */
class TransferManager(private val scope: CoroutineScope) {

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val nextId = AtomicLong(1)

    /**
     * Start a transfer.
     *
     * [block] receives a `report(transferred, total)` callback it is expected to
     * call as bytes move; everything else (state, speed, logging, cleanup) is
     * handled here.
     */
    fun start(
        label: String,
        direction: TransferDirection,
        total: Long,
        destination: String? = null,
        block: suspend (report: (Long, Long) -> Unit) -> Unit,
    ): Long {
        val id = nextId.getAndIncrement()
        val transfer = Transfer(
            id = id,
            label = label,
            direction = direction,
            transferred = 0,
            total = total,
            state = TransferState.RUNNING,
            startedAtMs = System.currentTimeMillis(),
            destination = destination,
        )
        put(transfer)
        AppLog.i(
            TAG,
            "${direction.name.lowercase()} started: $label " +
                "(${if (total > 0) LogFormat.bytes(total) else "unknown size"})" +
                (destination?.let { " -> $it" } ?: ""),
        )

        jobs[id] = scope.launch {
            try {
                block { transferred, knownTotal ->
                    mutate(id) {
                        it.copy(
                            transferred = transferred,
                            total = if (knownTotal > 0) knownTotal else it.total,
                        )
                    }
                }
                mutate(id) {
                    it.copy(state = TransferState.SUCCESS, finishedAtMs = System.currentTimeMillis())
                }
                val done = _transfers.value.firstOrNull { it.id == id }
                AppLog.i(
                    TAG,
                    "transfer finished: $label ${done?.summary ?: ""}" +
                        (destination?.let { " -> $it" } ?: ""),
                )
            } catch (cancelled: CancellationException) {
                mutate(id) {
                    it.copy(state = TransferState.CANCELLED, finishedAtMs = System.currentTimeMillis())
                }
                AppLog.w(TAG, "transfer cancelled: $label")
                throw cancelled
            } catch (t: Throwable) {
                mutate(id) {
                    it.copy(
                        state = TransferState.FAILED,
                        finishedAtMs = System.currentTimeMillis(),
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
                AppLog.e(TAG, "transfer failed: $label", t)
            } finally {
                jobs.remove(id)
            }
        }
        return id
    }

    fun cancel(id: Long) {
        AppLog.i(TAG, "cancel requested for transfer $id")
        jobs[id]?.cancel(CancellationException("cancelled by user"))
    }

    fun dismiss(id: Long) {
        _transfers.value = _transfers.value.filterNot { it.id == id && it.isFinished }
    }

    fun clearFinished() {
        _transfers.value = _transfers.value.filter { !it.isFinished }
    }

    /** Cancel everything; called when the device goes away. */
    fun cancelAll(reason: String) {
        if (jobs.isNotEmpty()) AppLog.w(TAG, "cancelling ${jobs.size} transfer(s): $reason")
        jobs.values.forEach { it.cancel(CancellationException(reason)) }
        jobs.clear()
    }

    private fun put(transfer: Transfer) {
        val existing = _transfers.value.filterNot { it.id == transfer.id }
        val trimmed = (existing + transfer)
            .sortedByDescending { it.startedAtMs }
            .let { list ->
                val running = list.filter { !it.isFinished }
                val finished = list.filter { it.isFinished }.take(MAX_FINISHED)
                (running + finished).sortedByDescending { it.startedAtMs }
            }
        _transfers.value = trimmed
    }

    private fun mutate(id: Long, transform: (Transfer) -> Transfer) {
        _transfers.value = _transfers.value.map { if (it.id == id) transform(it) else it }
    }

    companion object {
        private const val TAG = "Transfers"
        private const val MAX_FINISHED = 12
    }
}
