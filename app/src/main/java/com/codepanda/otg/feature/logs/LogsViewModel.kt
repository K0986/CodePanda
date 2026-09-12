package com.codepanda.otg.feature.logs

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogEntry
import com.codepanda.otg.core.log.LogFormat
import com.codepanda.otg.core.log.LogLevel

/**
 * Backs the Logs screen: filtering, sharing and clearing the on-device log.
 *
 * The log is the app's primary diagnostic. A user who says "it just hangs" can
 * now send a file that shows the USB handshake, every shell command with its
 * exit status, every transfer with its byte count, and the exact exception.
 */
class LogsViewModel : ViewModel() {

    var minLevel by mutableStateOf(LogLevel.DEBUG)
        private set

    var query by mutableStateOf("")
        private set

    var autoScroll by mutableStateOf(true)
        private set

    val entries get() = AppLog.entries

    fun setLevel(level: LogLevel) {
        minLevel = level
        AppLog.setMinLevel(level)
    }

    fun updateQuery(value: String) {
        query = value
    }

    fun toggleAutoScroll() {
        autoScroll = !autoScroll
    }

    fun visible(all: List<LogEntry>): List<LogEntry> = all.filter { entry ->
        entry.level.atLeast(minLevel) &&
            (
                query.isBlank() ||
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.tag.contains(query, ignoreCase = true)
                )
    }

    fun logLocation(): String = AppLog.currentFile()?.absolutePath ?: "(not available)"

    fun logSize(): String = LogFormat.bytes(AppLog.totalSizeBytes())

    fun clear() = AppLog.clear()

    /**
     * Share the log file with any app that accepts text.
     *
     * Logs live under `Android/data/<pkg>/files/logs`, which modern Android does
     * not let a file manager browse, so a share sheet (through a FileProvider
     * grant) is the practical way to get the file off the phone.
     */
    fun share(context: Context) {
        AppLog.flushBlocking()
        val file = AppLog.currentFile() ?: return
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CodePanda OTG log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
