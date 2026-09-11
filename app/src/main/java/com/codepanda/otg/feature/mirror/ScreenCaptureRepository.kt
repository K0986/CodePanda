package com.codepanda.otg.feature.mirror

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.AdbStream
import com.codepanda.otg.adb.service.AdbShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screenshot capture and live screen streaming for the connected device.
 *
 * Two capabilities, both built on stock Android tools — no root, no external
 * binaries required:
 *
 *  - **Screenshot:** `screencap -p` writes a PNG to stdout. Run over `exec:`
 *    (no PTY) the bytes arrive intact and we hand them straight to the UI.
 *  - **Live stream:** `screenrecord --output-format=h264 -` emits a raw H.264
 *    Annex-B elementary stream on stdout, which [VideoDecoder] feeds into a
 *    hardware [android.media.MediaCodec] and renders to a Surface — the same
 *    idea scrcpy uses, minus the custom server.
 */
class ScreenCaptureRepository(
    private val connection: AdbConnection,
    private val shell: AdbShell,
) {

    /** Grab a single screenshot and return the PNG bytes. */
    suspend fun screenshotPng(): ByteArray = withContext(Dispatchers.IO) {
        shell.execBytes("screencap -p")
    }

    /** The device's physical resolution, used as a decoder size hint. */
    suspend fun displaySize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val out = shell.exec("wm size")
        val match = Regex("""(\d+)x(\d+)""").find(out)
        if (match != null) {
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        } else {
            1080 to 1920
        }
    }

    /**
     * Open a live H.264 stream of the device screen. The caller owns the returned
     * [AdbStream] and must close it to stop `screenrecord` on the device.
     *
     * @param width/height explicit output resolution; omit to use native size
     * @param bitRateBps target H.264 bitrate
     * @param timeLimitSeconds `screenrecord` caps sessions (≤180s); we restart.
     */
    fun openVideoStream(
        width: Int? = null,
        height: Int? = null,
        bitRateBps: Int = 8_000_000,
        timeLimitSeconds: Int = 180,
    ): AdbStream {
        val command = buildString {
            append("screenrecord")
            append(" --output-format=h264")
            if (width != null && height != null) {
                append(" --size ").append(width).append('x').append(height)
            }
            append(" --bit-rate ").append(bitRateBps)
            append(" --time-limit ").append(timeLimitSeconds)
            append(" -")
        }
        return connection.open("exec:$command")
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
}
