package com.codepanda.otg.feature.mirror

import android.content.Context
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.ui.Async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MirrorViewModel : ViewModel() {

    var screenshot by mutableStateOf<Async<ImageBitmap>?>(null)
        private set
    var mirroring by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)

    private var lastScreenshotBytes: ByteArray? = null
    private var decoder: VideoDecoder? = null
    private var surface: Surface? = null

    private val repo get() = SessionManager.session?.capture

    fun consumeStatus() { status = null }

    fun captureScreenshot() {
        val repo = repo ?: run { screenshot = Async.Failure("Not connected"); return }
        screenshot = Async.Loading
        viewModelScope.launch {
            screenshot = try {
                val bytes = repo.screenshotPng()
                lastScreenshotBytes = bytes
                val bitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } ?: throw IllegalStateException("Could not decode screenshot")
                Async.Success(bitmap.asImageBitmap())
            } catch (t: Throwable) {
                Async.Failure(t.message ?: "Screenshot failed")
            }
        }
    }

    fun saveScreenshot(context: Context) {
        val bytes = lastScreenshotBytes ?: run { status = "Capture a screenshot first"; return }
        viewModelScope.launch {
            status = try {
                val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                val file = File(dir, "screen-${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { FileOutputStream(file).use { it.write(bytes) } }
                "Saved to ${file.absolutePath}"
            } catch (t: Throwable) {
                t.message ?: "Save failed"
            }
        }
    }

    /** Begin (or resume) live mirroring onto [surface]. */
    fun startMirror(surface: Surface) {
        this.surface = surface
        if (mirroring) return
        mirroring = true
        startInternal(surface)
    }

    private fun startInternal(surface: Surface) {
        val repo = repo ?: run { status = "Not connected"; mirroring = false; return }
        viewModelScope.launch {
            try {
                val (w, h) = repo.scaledSize(maxEdge = 1280)
                // open() blocks on the ADB handshake, so keep it off the main thread.
                val stream = withContext(Dispatchers.IO) {
                    repo.openVideoStream(width = w, height = h)
                }
                decoder = VideoDecoder(
                    stream = stream,
                    surface = surface,
                    widthHint = w,
                    heightHint = h,
                    // Both callbacks fire on the decoder thread; Compose state
                    // must only be touched from the main thread.
                    onError = { t -> postToMain { status = "Stream error: ${t.message}" } },
                    onStopped = { postToMain { onStreamStopped() } },
                ).also { it.start() }
            } catch (t: Throwable) {
                status = t.message ?: "Could not start mirroring"
                mirroring = false
            }
        }
    }

    private fun postToMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    /**
     * `screenrecord` stops itself at the time limit. If the user still wants to
     * mirror, transparently start a fresh session on the same surface.
     */
    private fun onStreamStopped() {
        val surface = surface
        if (mirroring && surface != null && surface.isValid) {
            startInternal(surface)
        } else {
            mirroring = false
        }
    }

    fun stopMirror() {
        mirroring = false
        val current = decoder ?: return
        decoder = null
        // stop() joins the decoder thread, so never run it on the main thread.
        viewModelScope.launch(Dispatchers.IO) { current.stop() }
    }

    override fun onCleared() {
        mirroring = false
        // viewModelScope is already cancelled by now, so tear down inline.
        decoder?.stop()
        decoder = null
        surface = null
    }
}
