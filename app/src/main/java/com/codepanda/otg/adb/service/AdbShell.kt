package com.codepanda.otg.adb.service

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.AdbStream

/**
 * Thin convenience wrapper for running commands on the connected device.
 *
 * Two flavours exist for a reason:
 *  - `shell:` runs the command inside a PTY. Great for interactive output, but
 *    the PTY rewrites `\n` to `\r\n` and mangles binary data.
 *  - `exec:` gives a raw stdout pipe with no PTY. We use it whenever we parse
 *    output or move binary blobs (screenshots, APK bytes, …).
 */
class AdbShell(private val connection: AdbConnection) {

    /** Run [command] via `exec:` and return stdout as bytes (binary safe). */
    fun execBytes(command: String): ByteArray {
        val stream = connection.open("exec:$command")
        return try {
            stream.readAll()
        } finally {
            stream.close()
        }
    }

    /** Run [command] via `exec:` and return stdout as UTF-8 text. */
    fun exec(command: String): String = String(execBytes(command), Charsets.UTF_8)

    /** Run [command] inside a PTY and return the combined output. */
    fun shell(command: String): String {
        val stream = connection.open("shell:$command")
        return try {
            stream.readAllText()
        } finally {
            stream.close()
        }
    }

    /** Open a long-lived interactive shell stream (e.g. `logcat`, an idle shell). */
    fun openInteractive(command: String = ""): AdbStream =
        connection.open(if (command.isEmpty()) "shell:" else "shell:$command")
}
