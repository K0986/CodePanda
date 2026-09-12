package com.codepanda.otg.core.log

/** Severity of a log line. Ordered, so a minimum level can filter cheaply. */
enum class LogLevel(val label: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
    ;

    fun atLeast(other: LogLevel): Boolean = ordinal >= other.ordinal
}

/** One logged event. Immutable so it can be shared between the writer and the UI. */
data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val thread: String,
    val error: Throwable? = null,
)

/**
 * Rendering of [LogEntry] into the text that ends up in the `.txt` file.
 *
 * Kept pure and separate from [AppLog] so the format is unit-testable: a log is
 * only useful if it is machine-greppable months later, which means the shape of
 * a line is a contract worth pinning down.
 */
object LogFormat {

    /** `2026-09-11 22:41:03.123 I/AdbConnection [adb-reader] message` */
    fun line(entry: LogEntry): String = buildString {
        append(timestamp(entry.timeMs))
        append(' ')
        append(entry.level.label)
        append('/')
        append(entry.tag)
        append(" [")
        append(entry.thread)
        append("] ")
        append(entry.message)
        entry.error?.let { error ->
            append('\n')
            append(stackTrace(error))
        }
    }

    fun timestamp(timeMs: Long): String {
        // java.time is available from API 26; the app's minSdk is 24, so the
        // formatting is done by hand to avoid desugaring surprises.
        val totalSeconds = timeMs / 1000
        val millis = (timeMs % 1000).toInt()
        val days = Math.floorDiv(totalSeconds, 86_400L)
        val secondOfDay = Math.floorMod(totalSeconds, 86_400L).toInt()

        val (year, month, day) = civilFromDays(days)
        val hour = secondOfDay / 3600
        val minute = (secondOfDay % 3600) / 60
        val second = secondOfDay % 60

        return "%04d-%02d-%02d %02d:%02d:%02d.%03d".format(
            year, month, day, hour, minute, second, millis,
        )
    }

    /**
     * Howard Hinnant's `civil_from_days`: turn a count of days since the Unix
     * epoch into a calendar date, with no timezone database involved. Everything
     * is logged in UTC on purpose — a log that silently shifts by an hour twice
     * a year is a log you cannot correlate.
     */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year.toInt(), m.toInt(), d.toInt())
    }

    fun stackTrace(error: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            out.append(if (depth == 0) "    " else "    Caused by: ")
            out.append(current.javaClass.name)
            current.message?.let { out.append(": ").append(it) }
            out.append('\n')
            current.stackTrace.take(MAX_FRAMES).forEach { frame ->
                out.append("        at ").append(frame.toString()).append('\n')
            }
            current = current.cause
            depth++
        }
        return out.toString().trimEnd('\n')
    }

    /** Human readable byte count, used all over the transfer and shell logs. */
    fun bytes(count: Long): String = when {
        count < 1024 -> "$count B"
        count < 1024 * 1024 -> "%.1f KB".format(count / 1024.0)
        count < 1024L * 1024 * 1024 -> "%.1f MB".format(count / (1024.0 * 1024))
        else -> "%.2f GB".format(count / (1024.0 * 1024 * 1024))
    }

    /** Truncate command output so one chatty `dumpsys` cannot flood the log. */
    fun preview(text: String, maxChars: Int = 200): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }

    private const val MAX_FRAMES = 12
    private const val MAX_CAUSE_DEPTH = 4
}
