package com.codepanda.otg.adb.service

import com.codepanda.otg.adb.AdbServiceException
import com.codepanda.otg.adb.AdbStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The `shell_v2` sub-protocol.
 *
 * Plain `shell:`/`exec:` give you one undifferentiated byte pipe: stdout and
 * stderr are either merged or (for `exec:`) stderr is thrown away, and there is
 * no exit status at all. That is why so many operations in this app used to look
 * like they worked — `pm uninstall` prints its failures on stderr, so the app
 * saw empty output and reported success.
 *
 * `shell,v2,raw:` fixes that by framing the stream:
 *
 * ```
 * ┌────────┬──────────────────┬───────────────┐
 * │ id (1) │ length (4, LE)   │ payload (len) │
 * └────────┴──────────────────┴───────────────┘
 * ```
 *
 * with ids for stdin/stdout/stderr/exit. The exit packet carries a single byte:
 * the command's status code.
 */
object ShellV2 {

    const val ID_STDIN = 0
    const val ID_STDOUT = 1
    const val ID_STDERR = 2
    const val ID_EXIT = 3
    const val ID_CLOSE_STDIN = 4

    const val HEADER_SIZE = 5

    /** The feature flag adbd advertises in its connect banner. */
    const val FEATURE = "shell_v2"

    /** Service string for running [command] with framed output and no PTY. */
    fun service(command: String): String = "shell,v2,raw:$command"

    fun encode(id: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()

    /** A parsed frame header. */
    data class Header(val id: Int, val length: Int)

    fun decodeHeader(bytes: ByteArray): Header {
        require(bytes.size >= HEADER_SIZE) { "shell_v2 header must be $HEADER_SIZE bytes" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val id = buffer.get().toInt() and 0xFF
        val length = buffer.int
        if (length < 0 || length > MAX_FRAME) {
            throw AdbServiceException("implausible shell_v2 frame length: $length")
        }
        return Header(id, length)
    }

    /** Read one frame from [stream], or null at EOF. */
    fun readFrame(stream: AdbStream): Pair<Header, ByteArray>? {
        val headerBytes = ByteArray(HEADER_SIZE)
        var read = 0
        while (read < HEADER_SIZE) {
            val chunk = stream.read(HEADER_SIZE - read) ?: return null
            System.arraycopy(chunk, 0, headerBytes, read, chunk.size)
            read += chunk.size
        }
        val header = decodeHeader(headerBytes)
        val payload = if (header.length > 0) stream.readExact(header.length) else ByteArray(0)
        return header to payload
    }

    private const val MAX_FRAME = 8 * 1024 * 1024
}

/**
 * Outcome of a device command: what it printed, what it complained about, and
 * whether it actually worked.
 */
data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** The best available explanation of a failure, for logs and snackbars. */
    val errorText: String
        get() = stderr.trim().ifBlank { stdout.trim() }
            .ifBlank { "command failed with exit code $exitCode" }

    companion object {
        /** Exit code used when a device is too old to report one. */
        const val EXIT_UNKNOWN = -1
    }
}

/**
 * Parser for the `exec:` fallback used on devices without `shell_v2`.
 *
 * The command is wrapped so that stderr is merged into stdout and the status is
 * echoed on the last line: `(cmd) 2>&1; echo __CP_EXIT__$?`.
 */
object ExecFallback {

    const val MARKER = "__CP_EXIT__"

    fun wrap(command: String): String = "($command) 2>&1; echo $MARKER$?"

    fun parse(command: String, output: String): ShellResult {
        val normalised = output.replace("\r\n", "\n")
        val markerIndex = normalised.lastIndexOf(MARKER)
        if (markerIndex < 0) {
            // No marker: the shell died before echoing, so the status is unknown.
            return ShellResult(command, ShellResult.EXIT_UNKNOWN, normalised.trim(), "")
        }
        val body = normalised.substring(0, markerIndex).trimEnd('\n')
        val code = normalised.substring(markerIndex + MARKER.length)
            .trim()
            .takeWhile { it.isDigit() }
            .toIntOrNull()
            ?: ShellResult.EXIT_UNKNOWN
        // stderr was merged into stdout, so failures carry their text in stdout.
        return ShellResult(command, code, body, if (code == 0) "" else body)
    }
}
