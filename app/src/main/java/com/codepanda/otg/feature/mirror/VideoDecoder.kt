package com.codepanda.otg.feature.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.codepanda.otg.adb.service.StreamingCommand
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
import java.nio.ByteBuffer

/**
 * Decodes the raw H.264 Annex-B stream produced by `screenrecord ... -` and
 * renders it onto a [Surface] with the device's hardware decoder.
 *
 * The loop is the classic MediaCodec one:
 *  1. split the byte stream into NAL units (see [AnnexBSplitter]);
 *  2. capture the SPS (type 7) and PPS (type 8) parameter sets and configure the
 *     decoder from them;
 *  3. feed every subsequent NAL into an input buffer and release the decoded
 *     output straight to the Surface.
 *
 * What changed relative to the first implementation, and why mirroring now
 * actually starts:
 *  - the stream is read through a [StreamingCommand], so `screenrecord`'s
 *    **stderr** is captured; a device that refuses the command (unsupported
 *    flag, secure display, encoder busy) now says so instead of showing black;
 *  - NAL framing no longer reallocates the whole buffer per chunk;
 *  - the decoder reports throughput, so a stalled stream is visible in the log;
 *  - failures carry the reason out to the UI via [onError].
 */
class VideoDecoder(
    private val command: StreamingCommand,
    private val surface: Surface,
    private val widthHint: Int,
    private val heightHint: Int,
    private val onError: (Throwable) -> Unit = {},
    private val onStopped: (reason: String) -> Unit = {},
    private val onFirstFrame: () -> Unit = {},
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var frameIndex = 0L
    private var bytesIn = 0L
    private var framesRendered = 0L
    private var firstFrameReported = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "video-decoder").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun stop() {
        if (!running && thread == null) return
        running = false
        // Closing the stream is what unblocks the decoder thread's pending read.
        runCatching { command.close() }
        thread?.let { runCatching { it.join(STOP_JOIN_MS) } }
        thread = null
    }

    private fun runLoop() {
        val splitter = AnnexBSplitter()
        val bufferInfo = MediaCodec.BufferInfo()
        var reason = "stream ended"
        var lastStats = System.currentTimeMillis()

        try {
            AppLog.i(TAG, "decoder started (${widthHint}x$heightHint)")
            while (running) {
                val chunk = command.readStdout()
                if (chunk == null) {
                    // EOF. Flush whatever the splitter still holds, then stop.
                    splitter.drain()?.let { handleNal(it, bufferInfo) }
                    reason = command.stderrText.takeIf { it.isNotBlank() }
                        ?.let { "screenrecord ended: ${LogFormat.preview(it, 160)}" }
                        ?: "stream ended"
                    break
                }
                bytesIn += chunk.size
                splitter.append(chunk)

                while (running) {
                    val nal = splitter.next() ?: break
                    handleNal(nal, bufferInfo)
                }

                val now = System.currentTimeMillis()
                if (now - lastStats >= STATS_INTERVAL_MS) {
                    val seconds = (now - lastStats) / 1000.0
                    AppLog.d(
                        TAG,
                        "mirror: ${LogFormat.bytes(bytesIn)} in, $framesRendered frames rendered, " +
                            "%.1f fps, %s/s buffered=%d".format(
                                framesRendered / seconds,
                                LogFormat.bytes((bytesIn / seconds).toLong()),
                                splitter.buffered,
                            ),
                    )
                    framesRendered = 0
                    bytesIn = 0
                    lastStats = now
                }
            }
        } catch (t: Throwable) {
            if (running) {
                val stderr = command.stderrText.trim()
                val message = if (stderr.isNotEmpty()) "${t.message} — device said: $stderr" else t.message
                AppLog.e(TAG, "decoder loop failed: $message", t)
                reason = message ?: "decoder failed"
                onError(t)
            } else {
                reason = "stopped"
            }
        } finally {
            releaseCodec()
            if (splitter.resyncCount > 0) {
                AppLog.w(TAG, "decoder resynchronised ${splitter.resyncCount} time(s)")
            }
            AppLog.i(TAG, "decoder finished: $reason")
            onStopped(reason)
        }
    }

    private fun handleNal(nal: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
        if (nal.isEmpty()) return
        when (nal[0].toInt() and 0x1F) {
            NAL_SPS -> {
                // A different SPS means the geometry changed (rotation, or a new
                // screenrecord session): the running decoder is configured for
                // the old size and would render garbage, so rebuild it.
                if (codec != null && !nal.contentEquals(sps)) releaseCodec()
                sps = nal
                pps?.let { if (codec == null) configure(nal, it) }
            }
            NAL_PPS -> {
                pps = nal
                sps?.let { if (codec == null) configure(it, nal) }
            }
            else -> {
                val mc = codec ?: return // wait until configured
                feed(mc, nal)
                drain(mc, bufferInfo)
            }
        }
    }

    private fun configure(sps: ByteArray, pps: ByteArray) {
        val format = MediaFormat.createVideoFormat(MIME, widthHint, heightHint).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
            setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
        }
        codec = MediaCodec.createDecoderByType(MIME).apply {
            configure(format, surface, null, 0)
            start()
        }
        AppLog.i(TAG, "decoder configured from SPS/PPS (${widthHint}x$heightHint)")
    }

    /**
     * Queue one NAL unit. If every input buffer is busy we drain output to make
     * room and try again: silently discarding the unit leaves the decoder with a
     * hole in the bitstream and produces smeared, artefact-ridden frames.
     */
    private fun feed(mc: MediaCodec, nal: ByteArray) {
        val info = MediaCodec.BufferInfo()
        var index = mc.dequeueInputBuffer(TIMEOUT_US)
        while (running && index < 0) {
            drain(mc, info)
            index = mc.dequeueInputBuffer(TIMEOUT_US)
        }
        if (index < 0) return
        val input = mc.getInputBuffer(index) ?: return
        val size = nal.size + START_CODE.size
        if (input.capacity() < size) {
            AppLog.w(TAG, "NAL of $size B exceeds input buffer of ${input.capacity()} B; skipping")
            mc.queueInputBuffer(index, 0, 0, ptsFor(nal), 0)
            return
        }
        input.clear()
        input.put(START_CODE)
        input.put(nal)
        mc.queueInputBuffer(index, 0, size, ptsFor(nal), 0)
    }

    private fun drain(mc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            when (val index = mc.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    AppLog.d(TAG, "decoder output format: ${mc.outputFormat}")
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    mc.releaseOutputBuffer(index, true)
                    framesRendered++
                    if (!firstFrameReported) {
                        firstFrameReported = true
                        AppLog.i(TAG, "first frame rendered")
                        onFirstFrame()
                    }
                }
            }
        }
    }

    /**
     * Presentation timestamp for [nal]. Only coded picture NALs (types 1–5)
     * advance the clock — counting parameter sets and SEI as frames would run
     * the timeline ahead of the actual video.
     */
    private fun ptsFor(nal: ByteArray): Long {
        if ((nal[0].toInt() and 0x1F) in NAL_VCL_RANGE) frameIndex++
        return (frameIndex * 1_000_000L) / ASSUMED_FPS
    }

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private fun withStartCode(nal: ByteArray): ByteArray = START_CODE + nal

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME = "video/avc"
        private const val TIMEOUT_US = 10_000L
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private val NAL_VCL_RANGE = 1..5
        private const val ASSUMED_FPS = 60L
        private const val STATS_INTERVAL_MS = 5_000L
        private const val STOP_JOIN_MS = 1_500L
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
