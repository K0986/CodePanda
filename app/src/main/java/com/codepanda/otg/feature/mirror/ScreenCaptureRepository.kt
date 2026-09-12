package com.codepanda.otg.feature.mirror

import com.codepanda.otg.adb.AdbServiceException
import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.adb.service.StreamingCommand
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
import com.codepanda.otg.core.session.AdbOps

/**
 * Screenshot capture and live screen streaming for the connected device.
 *
 * Two capabilities, both built on stock Android tools — no root, no external
 * binaries required:
 *
 *  - **Screenshot:** `screencap -p` writes a PNG to stdout.
 *  - **Live stream:** `screenrecord --output-format=h264 -` emits a raw H.264
 *    Annex-B elementary stream on stdout, which [VideoDecoder] feeds into a
 *    hardware `MediaCodec` and renders to a Surface — the same idea scrcpy uses,
 *    minus the custom server.
 *
 * Both now run through [AdbShell], so a command that fails reports its exit
 * status and stderr rather than returning zero bytes and leaving the UI to
 * invent an explanation.
 */
class ScreenCaptureRepository(
    private val shell: AdbShell,
    private val ops: AdbOps,
) {

    /** Grab a single screenshot and return the PNG bytes. */
    suspend fun screenshotPng(): ByteArray = ops.run("screencap", timeoutMs = 60_000) {
        val result = shell.runBinary("screencap -p")
        if (result.stdout.isEmpty()) {
            throw AdbServiceException(
                result.stderr.ifBlank {
                    "screencap produced no data (exit ${result.exitCode})"
                },
            )
        }
        if (!isPng(result.stdout)) {
            // A PTY or a chatty shell can prepend noise; fail loudly rather than
            // handing the UI bytes it cannot decode.
            AppLog.w(
                TAG,
                "screencap output does not start with a PNG signature " +
                    "(${LogFormat.bytes(result.stdout.size.toLong())})",
            )
        }
        AppLog.i(TAG, "screenshot captured: ${LogFormat.bytes(result.stdout.size.toLong())}")
        result.stdout
    }

    /** The device's physical resolution, used as a decoder size hint. */
    suspend fun displaySize(): Pair<Int, Int> = ops.run("wm size") {
        val result = shell.run("wm size")
        val match = Regex("""(\d+)x(\d+)""").find(result.stdout)
        if (match != null) {
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        } else {
            AppLog.w(TAG, "could not parse 'wm size' output; assuming 1080x1920: ${result.errorText}")
            1080 to 1920
        }
    }

    /**
     * Start a live H.264 stream of the device screen.
     *
     * The caller owns the returned [StreamingCommand] and must close it to stop
     * `screenrecord` on the device.
     *
     * @param width/height output resolution; omit for the device's native size
     * @param bitRateBps target H.264 bitrate
     * @param timeLimitSeconds `screenrecord` caps sessions (≤180s); we restart
     */
    suspend fun openVideoStream(
        width: Int? = null,
        height: Int? = null,
        bitRateBps: Int = DEFAULT_BITRATE,
        timeLimitSeconds: Int = MAX_SESSION_SECONDS,
    ): StreamingCommand {
        val command = buildString {
            append("screenrecord")
            append(" --output-format=h264")
            if (width != null && height != null) {
                append(" --size ").append(width).append('x').append(height)
            }
            append(" --bit-rate ").append(bitRateBps)
            append(" --time-limit ").append(timeLimitSeconds.coerceIn(1, MAX_SESSION_SECONDS))
            append(" -")
        }
        return ops.run("open mirror stream") { shell.openStreaming(command) }
    }

    /** Compute a downscaled WxH that bounds the longest edge to [maxEdge]. */
    suspend fun scaledSize(maxEdge: Int = 1280): Pair<Int, Int> {
        val (w, h) = displaySize()
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return even(w) to even(h)
        val scale = maxEdge.toDouble() / longest
        return even((w * scale).toInt()) to even((h * scale).toInt())
    }

    // H.264 encoders require even dimensions.
    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    private companion object {
        const val TAG = "ScreenCapture"

        /**
         * 6 Mbps at 1280 px is a good trade for USB 2.0 OTG: high enough to look
         * sharp, low enough that the bulk endpoint and our decoder keep up.
         */
        const val DEFAULT_BITRATE = 6_000_000

        /** `screenrecord` refuses anything above 180s. */
        const val MAX_SESSION_SECONDS = 180
    }
}
