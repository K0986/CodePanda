package com.codepanda.otg.adb.service

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.AdbStream
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
import java.io.ByteArrayOutputStream

/**
 * Runs commands on the connected device and reports what actually happened.
 *
 * Prefers `shell,v2,raw:` (separate stdout/stderr plus an exit status) and falls
 * back to `exec:` with a status marker on devices that predate it. Both paths
 * return the same [ShellResult], so callers never have to guess success from
 * string matching — which is exactly what used to make failed `pm` commands look
 * like successes.
 *
 * Every command and its outcome is logged, because "I pressed uninstall and
 * nothing happened" is only debuggable if the exit code was written down.
 */
class AdbShell(private val connection: AdbConnection) {

    /**
     * Whether the device can frame stdout/stderr and report an exit code.
     * Public because a couple of operations (streaming an APK into stdin) have
     * to shape their command differently on the fallback path.
     */
    val supportsShellV2: Boolean
        get() = connection.features.contains(ShellV2.FEATURE)

    /** Run [command] and capture stdout, stderr and the exit status. */
    fun run(command: String): ShellResult {
        val started = System.currentTimeMillis()
        val result = if (supportsShellV2) runShellV2(command) else runExecFallback(command)
        val elapsed = System.currentTimeMillis() - started

        val summary = "exit=${result.exitCode} in ${elapsed}ms " +
            "stdout=${result.stdout.length}B stderr=${result.stderr.length}B"
        if (result.isSuccess) {
            AppLog.d(TAG, "$ $command -> $summary")
        } else {
            AppLog.w(TAG, "$ $command -> $summary :: ${LogFormat.preview(result.errorText)}")
        }
        if (result.stdout.isNotEmpty()) {
            AppLog.v(TAG, "stdout: ${LogFormat.preview(result.stdout, 400)}")
        }
        if (result.stderr.isNotEmpty()) {
            AppLog.v(TAG, "stderr: ${LogFormat.preview(result.stderr, 400)}")
        }
        return result
    }

    /**
     * Run [command] and return its raw stdout bytes (binary safe), plus the
     * status. Used for `screencap -p` and other binary producers.
     */
    fun runBinary(command: String): BinaryResult {
        val started = System.currentTimeMillis()
        val result = if (supportsShellV2) {
            connection.withStream(ShellV2.service(command)) { stream ->
                val stdout = ByteArrayOutputStream()
                val stderr = StringBuilder()
                var exit = ShellResult.EXIT_UNKNOWN
                while (true) {
                    val (header, payload) = ShellV2.readFrame(stream) ?: break
                    when (header.id) {
                        ShellV2.ID_STDOUT -> stdout.write(payload)
                        ShellV2.ID_STDERR -> stderr.append(String(payload, Charsets.UTF_8))
                        ShellV2.ID_EXIT -> {
                            exit = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                            break
                        }
                    }
                }
                BinaryResult(command, exit, stdout.toByteArray(), stderr.toString())
            }
        } else {
            connection.withStream("exec:$command") { stream ->
                BinaryResult(command, ShellResult.EXIT_UNKNOWN, stream.readAll(), "")
            }
        }
        AppLog.d(
            TAG,
            "$ $command -> exit=${result.exitCode}, ${LogFormat.bytes(result.stdout.size.toLong())} " +
                "in ${System.currentTimeMillis() - started}ms",
        )
        if (result.stderr.isNotBlank()) {
            AppLog.w(TAG, "$command stderr: ${LogFormat.preview(result.stderr)}")
        }
        return result
    }

    /**
     * Open a long-lived command whose stdout is consumed incrementally (the
     * screen mirror). The caller owns the stream and must close it.
     *
     * With `shell_v2` the returned reader also surfaces stderr, which is how a
     * failing `screenrecord` finally explains itself instead of producing a
     * black rectangle.
     */
    fun openStreaming(command: String): StreamingCommand {
        AppLog.i(TAG, "streaming: $ $command")
        return if (supportsShellV2) {
            StreamingCommand.ShellV2Command(connection.open(ShellV2.service(command)), command)
        } else {
            StreamingCommand.RawCommand(connection.open("exec:$command"), command)
        }
    }

    // ---- implementations ---------------------------------------------------

    private fun runShellV2(command: String): ShellResult =
        connection.withStream(ShellV2.service(command)) { stream ->
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            var exit = ShellResult.EXIT_UNKNOWN
            while (true) {
                val (header, payload) = ShellV2.readFrame(stream) ?: break
                when (header.id) {
                    ShellV2.ID_STDOUT -> stdout.append(String(payload, Charsets.UTF_8))
                    ShellV2.ID_STDERR -> stderr.append(String(payload, Charsets.UTF_8))
                    ShellV2.ID_EXIT -> {
                        exit = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        break
                    }
                }
            }
            ShellResult(command, exit, stdout.toString(), stderr.toString())
        }

    private fun runExecFallback(command: String): ShellResult =
        connection.withStream("exec:${ExecFallback.wrap(command)}") { stream ->
            ExecFallback.parse(command, stream.readAllText())
        }

    // ---- convenience -------------------------------------------------------

    /** stdout of [command], ignoring the status. For parsers that tolerate noise. */
    fun exec(command: String): String = run(command).stdout

    /** Run [command] and throw if it failed. For operations with side effects. */
    fun require(command: String): ShellResult {
        val result = run(command)
        if (!result.isSuccess && result.exitCode != ShellResult.EXIT_UNKNOWN) {
            throw com.codepanda.otg.adb.AdbServiceException("$command: ${result.errorText}")
        }
        return result
    }

    companion object {
        private const val TAG = "AdbShell"
    }
}

/** Binary-safe command output. */
data class BinaryResult(
    val command: String,
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0 || exitCode == ShellResult.EXIT_UNKNOWN

    override fun equals(other: Any?): Boolean =
        other is BinaryResult && command == other.command && exitCode == other.exitCode &&
            stdout.contentEquals(other.stdout) && stderr == other.stderr

    override fun hashCode(): Int =
        (command.hashCode() * 31 + exitCode) * 31 + stdout.contentHashCode()
}

/**
 * A running command whose stdout is read as it arrives.
 *
 * Both variants expose the same `readStdout`, so the video decoder does not care
 * whether the bytes are framed by `shell_v2` or raw from `exec:`.
 */
sealed class StreamingCommand(val stream: AdbStream, val command: String) : AutoCloseable {

    /** Next chunk of stdout, or null at end of stream. */
    abstract fun readStdout(): ByteArray?

    /** Feed bytes to the command's stdin (used to stream an APK into `install`). */
    abstract fun writeStdin(data: ByteArray, length: Int = data.size)

    /** Signal end-of-input, so a command reading stdin can finish. */
    open fun closeStdin() = Unit

    /** Anything the command wrote to stderr so far. */
    open val stderrText: String get() = ""

    /** Exit status once known; null on transports that cannot report one. */
    open val exitCode: Int? get() = null

    /** Read the rest of stdout as text (bounded), for commands that end quickly. */
    fun drainStdoutText(limitBytes: Int = 512 * 1024): String {
        val out = StringBuilder()
        while (out.length < limitBytes) {
            val chunk = readStdout() ?: break
            out.append(String(chunk, Charsets.UTF_8))
        }
        return out.toString()
    }

    override fun close() {
        stream.close()
    }

    class RawCommand(stream: AdbStream, command: String) : StreamingCommand(stream, command) {
        override fun readStdout(): ByteArray? = stream.read()

        override fun writeStdin(data: ByteArray, length: Int) {
            stream.write(if (length == data.size) data else data.copyOfRange(0, length))
        }
    }

    class ShellV2Command(stream: AdbStream, command: String) : StreamingCommand(stream, command) {
        private val stderr = StringBuilder()

        @Volatile
        private var exit: Int? = null

        override val stderrText: String get() = stderr.toString()

        override val exitCode: Int? get() = exit

        override fun writeStdin(data: ByteArray, length: Int) {
            val payload = if (length == data.size) data else data.copyOfRange(0, length)
            stream.write(ShellV2.encode(ShellV2.ID_STDIN, payload))
        }

        override fun closeStdin() {
            runCatching { stream.write(ShellV2.encode(ShellV2.ID_CLOSE_STDIN)) }
        }

        override fun readStdout(): ByteArray? {
            while (true) {
                val (header, payload) = ShellV2.readFrame(stream) ?: return null
                when (header.id) {
                    ShellV2.ID_STDOUT -> if (payload.isNotEmpty()) return payload
                    ShellV2.ID_STDERR -> {
                        val text = String(payload, Charsets.UTF_8)
                        synchronized(stderr) {
                            // Bound the buffer: a chatty command must not grow the heap.
                            if (stderr.length < MAX_STDERR) stderr.append(text)
                        }
                        AppLog.w("AdbShell", "$command stderr: ${LogFormat.preview(text)}")
                    }
                    ShellV2.ID_EXIT -> {
                        val code = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        exit = code
                        AppLog.i("AdbShell", "streaming command '$command' exited with $code")
                        return null
                    }
                }
            }
        }

        private companion object {
            const val MAX_STDERR = 8 * 1024
        }
    }
}
