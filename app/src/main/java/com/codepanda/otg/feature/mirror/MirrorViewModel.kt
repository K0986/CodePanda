package com.codepanda.otg.feature.mirror

import android.content.Context
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.SessionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Screenshot and live-mirror screen.
 *
 * Mirroring is a long-lived pipeline (`screenrecord` → USB stream → MediaCodec →
 * Surface) with three awkward properties: `screenrecord` stops itself after at
 * most 180 seconds, the Surface comes and goes with the view, and failures are
 * reported on stderr. All three are handled here:
 *
 *  - the session (not the ViewModel) owns the coroutine, so a recomposition
 *    cannot kill a running stream;
 *  - a stream that ends on its own is restarted, with backoff and a bounded
 *    number of consecutive failures so a device that simply refuses the command
 *    reports an error instead of looping forever;
 *  - the reason a session ended is surfaced to the UI and written to the log.
 */
class MirrorViewModel : SessionViewModel() {

    var screenshot by mutableStateOf<Async<ImageBitmap>?>(null)
        private set

    var mirroring by mutableStateOf(false)
        private set

    /** What the mirror is doing right now, shown under the video surface. */
    var mirrorStatus by mutableStateOf<String?>(null)
        private set

    private var lastScreenshotBytes: ByteArray? = null
    private var decoder: VideoDecoder? = null
    private var surface: Surface? = null
    private var mirrorJob: Job? = null
    private var consecutiveFailures = 0

    fun captureScreenshot() {
        val active = session ?: run {
            screenshot = Async.Failure("Not connected to a device")
            return
        }
        screenshot = Async.Loading
        active.scope.launch {
            screenshot = try {
                val bytes = active.capture.screenshotPng()
                lastScreenshotBytes = bytes
                val bitmap = withContext(Dispatchers.Default) { decodeBounded(bytes) }
                    ?: throw IllegalStateException("The screenshot could not be decoded")
                Async.Success(bitmap.asImageBitmap())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.e(TAG, "screenshot failed", t)
                Async.Failure(t.message ?: "Screenshot failed")
            }
        }
    }

    fun saveScreenshot(context: Context) {
        val bytes = lastScreenshotBytes ?: run {
            message = "Capture a screenshot first"
            return
        }
        val active = session ?: run {
            message = "Not connected to a device"
            return
        }
        active.scope.launch {
            message = try {
                val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                val file = File(dir, "screen-${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { FileOutputStream(file).use { it.write(bytes) } }
                AppLog.i(TAG, "screenshot saved to ${file.absolutePath} (${LogFormat.bytes(bytes.size.toLong())})")
                "Saved to ${file.absolutePath}"
            } catch (t: Throwable) {
                AppLog.e(TAG, "saving screenshot failed", t)
                t.message ?: "Save failed"
            }
        }
    }

    /** Begin (or resume) live mirroring onto [target]. */
    fun startMirror(target: Surface) {
        surface = target
        if (mirrorJob?.isActive == true) return
        consecutiveFailures = 0
        runMirrorLoop()
    }

    fun stopMirror() {
        mirrorJob?.cancel()
        mirrorJob = null
        mirroring = false
        mirrorStatus = null
        val current = decoder ?: return
        decoder = null
        // stop() joins the decoder thread, so never run it on the main thread.
        session?.scope?.launch(Dispatchers.IO) { current.stop() }
            ?: Thread { current.stop() }.start()
    }

    /**
     * One coroutine drives every mirror session in sequence: start, wait for it
     * to end, decide whether to restart.
     */
    private fun runMirrorLoop() {
        val active = session ?: run {
            mirrorStatus = "Not connected to a device"
            return
        }
        mirroring = true
        mirrorJob = active.scope.launch {
            while (mirroring) {
                val target = surface
                if (target == null || !target.isValid) {
                    AppLog.i(TAG, "mirror stopping: surface is gone")
                    break
                }

                mirrorStatus = "Starting screen stream…"
                val ended = try {
                    runOneSession(active, target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.e(TAG, "mirror session could not start", t)
                    mirrorStatus = t.message ?: "Could not start mirroring"
                    consecutiveFailures++
                    null
                }

                if (!mirroring) break

                if (ended != null && ended.renderedFrames > 0) {
                    consecutiveFailures = 0
                    AppLog.i(TAG, "mirror session ended normally (${ended.reason}); restarting")
                    mirrorStatus = "Reconnecting the stream…"
                } else {
                    consecutiveFailures++
                    val detail = ended?.reason ?: mirrorStatus
                    AppLog.w(
                        TAG,
                        "mirror session produced no frames (attempt $consecutiveFailures): $detail",
                    )
                    if (consecutiveFailures >= MAX_FAILURES) {
                        mirroring = false
                        mirrorStatus = detail?.takeIf { it.isNotBlank() }
                            ?: "The device would not start screenrecord. Check the log for details."
                        message = mirrorStatus
                        break
                    }
                }
                delay(RESTART_DELAY_MS * consecutiveFailures.coerceAtLeast(1))
            }
            mirroring = false
        }
    }

    private data class SessionEnd(val reason: String, val renderedFrames: Long)

    /** Runs a single `screenrecord` session and suspends until it ends. */
    private suspend fun runOneSession(
        active: com.codepanda.otg.core.session.DeviceSession,
        target: Surface,
    ): SessionEnd {
        val (width, height) = active.capture.scaledSize(maxEdge = MAX_EDGE)
        val command = active.capture.openVideoStream(width = width, height = height)

        val completion = kotlinx.coroutines.CompletableDeferred<SessionEnd>()
        var frames = 0L

        val videoDecoder = VideoDecoder(
            command = command,
            surface = target,
            widthHint = width,
            heightHint = height,
            onError = { t -> mirrorStatus = t.message ?: "Stream error" },
            onFirstFrame = {
                frames++
                mirrorStatus = "Live · ${width}x$height"
            },
            onStopped = { reason ->
                if (!completion.isCompleted) completion.complete(SessionEnd(reason, frames))
            },
        )
        decoder = videoDecoder
        videoDecoder.start()

        return try {
            completion.await()
        } finally {
            // Cancellation (user left, disconnect) must stop the device encoder.
            withContext(kotlinx.coroutines.NonCancellable) {
                withContext(Dispatchers.IO) { videoDecoder.stop() }
            }
            if (decoder === videoDecoder) decoder = null
        }
    }

    /**
     * Decode a screenshot at a sane size.
     *
     * A modern phone screenshot is 1440x3200; as ARGB_8888 that is ~18 MB, and
     * this app may be decoding video at the same time. Preview quality only
     * needs a couple of megapixels, so the bitmap is subsampled.
     */
    private fun decodeBounded(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (
            bounds.outWidth / sample * (bounds.outHeight / sample) > MAX_PREVIEW_PIXELS &&
            sample < 8
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    override fun onCleared() {
        mirroring = false
        mirrorJob?.cancel()
        decoder?.let { current ->
            decoder = null
            Thread { current.stop() }.start()
        }
        surface = null
    }

    private companion object {
        const val TAG = "MirrorViewModel"
        const val MAX_EDGE = 1280
        const val MAX_FAILURES = 3
        const val RESTART_DELAY_MS = 700L
        const val MAX_PREVIEW_PIXELS = 2_500_000
    }
}
