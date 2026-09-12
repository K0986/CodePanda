package com.codepanda.otg.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {

    @Test
    fun `a line carries timestamp, level, tag, thread and message`() {
        val entry = LogEntry(
            timeMs = 1_757_606_463_123, // 2025-09-11T16:01:03.123Z
            level = LogLevel.INFO,
            tag = "AdbConnection",
            message = "Connected to Pixel 8",
            thread = "adb-reader",
        )

        assertEquals(
            "2025-09-11 16:01:03.123 I/AdbConnection [adb-reader] Connected to Pixel 8",
            LogFormat.line(entry),
        )
    }

    @Test
    fun `timestamps are UTC and stable across the epoch`() {
        assertEquals("1970-01-01 00:00:00.000", LogFormat.timestamp(0))
        assertEquals("2000-02-29 12:00:00.500", LogFormat.timestamp(951_825_600_500))
        assertEquals("2024-12-31 23:59:59.999", LogFormat.timestamp(1_735_689_599_999))
    }

    @Test
    fun `errors are appended with a bounded stack trace`() {
        val cause = IllegalStateException("device said no")
        val error = RuntimeException("pull failed", cause)
        val line = LogFormat.line(
            LogEntry(0, LogLevel.ERROR, "Sync", "RECV /sdcard/x", "main", error),
        )

        assertTrue(line.contains("E/Sync"))
        assertTrue(line.contains("java.lang.RuntimeException: pull failed"))
        assertTrue(line.contains("Caused by: java.lang.IllegalStateException: device said no"))
        // Frames are indented so a log reader can fold them.
        assertTrue(line.lines().any { it.startsWith("        at ") })
    }

    @Test
    fun `byte counts are human readable`() {
        assertEquals("512 B", LogFormat.bytes(512))
        assertEquals("1.0 KB", LogFormat.bytes(1024))
        assertEquals("1.5 MB", LogFormat.bytes(1024 * 1536))
        assertEquals("2.00 GB", LogFormat.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `previews collapse whitespace and truncate`() {
        val messy = "line one\n   line two\t\tline three"
        assertEquals("line one line two line three", LogFormat.preview(messy))
        assertEquals("abcde…", LogFormat.preview("abcdefghij", maxChars = 5))
    }

    @Test
    fun `levels are ordered so filtering is cheap`() {
        assertTrue(LogLevel.ERROR.atLeast(LogLevel.DEBUG))
        assertTrue(LogLevel.DEBUG.atLeast(LogLevel.DEBUG))
        assertTrue(!LogLevel.VERBOSE.atLeast(LogLevel.INFO))
    }
}
