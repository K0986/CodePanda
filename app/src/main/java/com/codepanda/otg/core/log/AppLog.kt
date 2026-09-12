package com.codepanda.otg.core.log

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own log: every line goes to a rotating `.txt` file on disk **and** to
 * an in-memory ring buffer the Logs screen renders live.
 *
 * Why not just logcat? Because the interesting failures here happen on a phone
 * that is plugged into another phone, in someone's hand, with no laptop in
 * sight. A USB cable that renegotiates, a device that refuses `pm uninstall`, a
 * stream that stalls — all of it has to be readable after the fact, from the
 * device itself. So this logger is deliberately boring and durable:
 *
 *  - **Callers never block.** Entries go onto a bounded queue; if the writer
 *    cannot keep up the *oldest* entries are dropped and a marker is recorded.
 *    Logging must never become the thing that makes the app hang.
 *  - **Memory is bounded.** The UI buffer keeps the last [MAX_MEMORY_ENTRIES]
 *    entries, the file rotates at [MAX_FILE_BYTES] and keeps [KEPT_FILES]
 *    generations. A month-long session cannot fill the disk.
 *  - **Crashes are captured.** An uncaught exception is written and flushed
 *    before the default handler tears the process down.
 */
object AppLog {

    private const val MAX_MEMORY_ENTRIES = 1500
    private const val QUEUE_CAPACITY = 4096
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val KEPT_FILES = 3
    private const val FLUSH_INTERVAL_MS = 750L

    private val queue = ArrayBlockingQueue<LogEntry>(QUEUE_CAPACITY)
    private val started = AtomicBoolean(false)
    private val ring = ArrayDeque<LogEntry>(MAX_MEMORY_ENTRIES)
    private val ringLock = Any()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Live view of the most recent entries, newest last. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _minLevel = MutableStateFlow(LogLevel.DEBUG)
    val minLevel: StateFlow<LogLevel> = _minLevel.asStateFlow()

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var dropped = 0L

    /**
     * Wire up the logger. Safe to call more than once (the app calls it from
     * both `Application` and `Activity` startup paths).
     */
    fun install(context: Context) {
        if (!started.compareAndSet(false, true)) return

        logDir = resolveLogDir(context)
        Thread({ writerLoop() }, "codepanda-log-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
            start()
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Crash", "Uncaught exception on thread ${thread.name}", error)
            flushBlocking()
            previous?.uncaughtException(thread, error)
        }

        i("AppLog", "Logging to ${currentFile()?.absolutePath ?: "(unavailable)"}")
        i(
            "AppLog",
            "Device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), app ${context.packageName}",
        )
    }

    fun setMinLevel(level: LogLevel) {
        _minLevel.value = level
        i("AppLog", "Minimum log level set to $level")
    }

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message, null)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) =
        log(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, error)

    private fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (!level.atLeast(_minLevel.value)) return

        val entry = LogEntry(
            timeMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            thread = Thread.currentThread().name,
            error = error,
        )

        // Mirror into logcat so `adb logcat` still works during development.
        // Wrapped because a logger must never be the thing that throws.
        runCatching {
            when (level) {
                LogLevel.VERBOSE -> Log.v(tag, message, error)
                LogLevel.DEBUG -> Log.d(tag, message, error)
                LogLevel.INFO -> Log.i(tag, message, error)
                LogLevel.WARN -> Log.w(tag, message, error)
                LogLevel.ERROR -> Log.e(tag, message, error)
            }
        }

        publish(entry)

        if (!queue.offer(entry)) {
            // Writer is behind: make room rather than block the caller.
            queue.poll()
            queue.offer(entry)
            dropped++
        }
    }

    private fun publish(entry: LogEntry) {
        val snapshot = synchronized(ringLock) {
            ring.addLast(entry)
            while (ring.size > MAX_MEMORY_ENTRIES) ring.removeFirst()
            ring.toList()
        }
        _entries.value = snapshot
    }

    // ---- Files -------------------------------------------------------------

    /** The file currently being appended to, if logging is initialised. */
    fun currentFile(): File? = logDir?.let { File(it, "codepanda.log") }

    /** Current file plus rotated generations, newest first. */
    fun allFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return buildList {
            currentFile()?.takeIf { it.exists() }?.let { add(it) }
            for (index in 1..KEPT_FILES) {
                val rotated = File(dir, "codepanda.$index.log")
                if (rotated.exists()) add(rotated)
            }
        }
    }

    fun totalSizeBytes(): Long = allFiles().sumOf { it.length() }

    /** Wipe the in-memory buffer and every log file. */
    fun clear() {
        synchronized(ringLock) { ring.clear() }
        _entries.value = emptyList()
        allFiles().forEach { runCatching { it.delete() } }
        i("AppLog", "Log cleared")
    }

    /**
     * Drain everything queued so far. Used before sharing a log file or handing
     * control to the platform's crash handler, where "eventually" is too late.
     */
    fun flushBlocking(timeoutMs: Long = 1_500) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        synchronized(writerLock) { runCatching { writer?.flush() } }
    }

    // ---- Writer thread -----------------------------------------------------

    private val writerLock = Any()
    private var writer: java.io.Writer? = null
    private var bytesWritten = 0L

    private fun writerLoop() {
        while (true) {
            try {
                val first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                if (first != null) {
                    val batch = ArrayList<LogEntry>(32)
                    batch += first
                    queue.drainTo(batch, 256)
                    writeBatch(batch)
                }
                synchronized(writerLock) { runCatching { writer?.flush() } }
                if (dropped > 0) {
                    val count = dropped
                    dropped = 0
                    writeBatch(
                        listOf(
                            LogEntry(
                                System.currentTimeMillis(),
                                LogLevel.WARN,
                                "AppLog",
                                "Dropped $count log entries: writer could not keep up",
                                Thread.currentThread().name,
                            ),
                        ),
                    )
                }
            } catch (interrupted: InterruptedException) {
                return
            } catch (t: Throwable) {
                // A logger that crashes the app would be worse than no logger.
                Log.w("AppLog", "writer loop error", t)
            }
        }
    }

    private fun writeBatch(batch: List<LogEntry>) {
        val dir = logDir ?: return
        synchronized(writerLock) {
            val out = writer ?: openWriter(dir) ?: return
            val text = buildString {
                batch.forEach { append(LogFormat.line(it)).append('\n') }
            }
            out.write(text)
            bytesWritten += text.length
            if (bytesWritten >= MAX_FILE_BYTES) rotate(dir)
        }
    }

    private fun openWriter(dir: File): java.io.Writer? = try {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "codepanda.log")
        bytesWritten = file.length()
        java.io.BufferedWriter(java.io.FileWriter(file, /* append = */ true), 16 * 1024)
            .also { writer = it }
    } catch (io: IOException) {
        Log.w("AppLog", "cannot open log file", io)
        null
    }

    private fun rotate(dir: File) {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        runCatching {
            File(dir, "codepanda.$KEPT_FILES.log").delete()
            for (index in KEPT_FILES - 1 downTo 1) {
                val from = File(dir, "codepanda.$index.log")
                if (from.exists()) from.renameTo(File(dir, "codepanda.${index + 1}.log"))
            }
            File(dir, "codepanda.log").renameTo(File(dir, "codepanda.1.log"))
        }
        bytesWritten = 0
        openWriter(dir)
    }

    /**
     * Prefer external app storage: the path is quotable in a bug report and the
     * file can be pulled off with a file manager. Falls back to internal storage
     * when external is unavailable (e.g. adopted-storage edge cases).
     */
    private fun resolveLogDir(context: Context): File {
        val external = runCatching { context.getExternalFilesDir("logs") }.getOrNull()
        val dir = external ?: File(context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        return dir
    }
}
