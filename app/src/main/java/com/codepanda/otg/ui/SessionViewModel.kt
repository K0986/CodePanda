package com.codepanda.otg.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.codepanda.otg.adb.service.ShellResult
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.session.DeviceSession
import com.codepanda.otg.core.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared plumbing for the feature ViewModels.
 *
 * The important part is [operate]: a device operation runs in the **session's**
 * scope, not in `viewModelScope`. Leaving the screen therefore no longer
 * cancels a half-finished `pm uninstall` or a file delete — which used to abort
 * the coroutine while the ADB stream it owned stayed open, freezing the whole
 * connection for every other tab.
 */
abstract class SessionViewModel : ViewModel() {

    /** Last user-facing message (snackbar), cleared by the screen. */
    var message by mutableStateOf<String?>(null)

    /** True while an operation started from this screen is running. */
    var busy by mutableStateOf(false)
        protected set

    protected val session: DeviceSession? get() = SessionManager.current

    fun consumeMessage() {
        message = null
    }

    /**
     * Run a device operation and report its outcome.
     *
     * [block] returns the message to show. Exceptions become messages too: an
     * operation that fails silently is the single worst outcome, because the user
     * cannot tell it from one that worked.
     */
    protected fun operate(
        label: String,
        onSuccess: (() -> Unit)? = null,
        block: suspend (DeviceSession) -> String,
    ) {
        val active = session
        if (active == null) {
            message = "Not connected to a device"
            return
        }
        busy = true
        active.scope.launch {
            val result = try {
                val text = block(active)
                onSuccess?.let { withContext(Dispatchers.Main) { it() } }
                text
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.e(TAG, "$label failed", t)
                t.message ?: "$label failed"
            }
            withContext(Dispatchers.Main) {
                busy = false
                message = result
            }
        }
    }

    /** Turn a device command result into a sentence worth showing a human. */
    protected fun describe(action: String, result: ShellResult): String = when {
        result.isSuccess -> "$action succeeded"
        result.exitCode == ShellResult.EXIT_UNKNOWN ->
            "$action: device did not report a status. ${result.stdout.take(120)}".trim()
        else -> "$action failed: ${result.errorText.take(160)}"
    }

    private companion object {
        const val TAG = "SessionViewModel"
    }
}
