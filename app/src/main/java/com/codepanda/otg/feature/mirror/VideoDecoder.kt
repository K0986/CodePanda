package com.codepanda.otg.feature.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.codepanda.otg.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Decodes a raw H.264 Annex-B elementary stream coming from an [AdbStream]
 * (produced by `screenrecord ... -`) and renders it onto a [Surface] using the
 * device's hardware decoder.
 *
 * The flow is the classic MediaCodec loop:
 *  1. split the byte stream into NAL units (delimited by `00 00 00 01`);
 *  2. capture the SPS (type 7) and PPS (type 8) parameter sets and use them to
 *     configure the decoder;
 *  3. feed every subsequent NAL into an input buffer and release the decoded
 *     output straight to the Surface.
 */
class VideoDecoder(
    private val stream: AdbStream,
    private val surface: Surface,
    private val widthHint: Int,
    private val heightHint: Int,
    private val onError: (Throwable) -> Unit = {},
    private val onStopped: () -> Unit = {},
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "video-decoder").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { stream.close() }
        thread?.let { runCatching { it.join(1000) } }
        thread = null
    }

    private fun runLoop() {
        val reader = NalReader(stream)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running) {
                val nal = reader.next() ?: break
                val type = nal[0].toInt() and 0x1F
                when (type) {
                    NAL_SPS -> {
                        sps = nal
                        if (pps != null && codec == null) configure(sps!!, pps!!)
                    }
                    NAL_PPS -> {
                        pps = nal
                        if (sps != null && codec == null) configure(sps!!, pps)
                    }
                    else -> {
                        val mc = codec ?: continue // wait until configured
                        feed(mc, nal)
                        drain(mc, bufferInfo)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) {
                Log.w(TAG, "decoder loop ended: ${t.message}")
                onError(t)
            }
        } finally {
            releaseCodec()
            onStopped()
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
    }

    private fun feed(mc: MediaCodec, nal: ByteArray) {
        val index = mc.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val input = mc.getInputBuffer(index) ?: return
        input.clear()
        input.put(START_CODE)
        input.put(nal)
        mc.queueInputBuffer(index, 0, nal.size + START_CODE.size, computePts(), 0)
    }

    private fun drain(mc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            when (val index = mc.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) mc.releaseOutputBuffer(index, true)
            }
        }
    }

    private var frameIndex = 0L
    private fun computePts(): Long = (frameIndex++ * 1_000_000L) / 60L

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private fun withStartCode(nal: ByteArray): ByteArray = START_CODE + nal

    /** Splits an Annex-B byte stream into NAL units (start code stripped). */
    private class NalReader(private val stream: AdbStream) {
        private var buffer = ByteArray(0)

        fun next(): ByteArray? {
            // Ensure the buffer begins at the byte after a start code.
            var start = indexOfStartCode(0)
            while (start < 0) {
                if (!fill()) return null
                start = indexOfStartCode(0)
            }
            val nalStart = start + startCodeLength(start)
            var nextStart = indexOfStartCode(nalStart)
            while (nextStart < 0) {
                if (!fill()) {
                    // EOF: whatever remains is the final NAL.
                    if (nalStart >= buffer.size) return null
                    val nal = buffer.copyOfRange(nalStart, buffer.size)
                    buffer = ByteArray(0)
                    return nal.takeIf { it.isNotEmpty() }
                }
                nextStart = indexOfStartCode(nalStart)
            }
            val nal = buffer.copyOfRange(nalStart, nextStart)
            buffer = buffer.copyOfRange(nextStart, buffer.size)
            return if (nal.isEmpty()) next() else nal
        }

        private fun fill(): Boolean {
            val chunk = stream.read() ?: return false
            if (chunk.isEmpty()) return true
            val combined = ByteArrayOutputStream(buffer.size + chunk.size)
            combined.write(buffer)
            combined.write(chunk)
            buffer = combined.toByteArray()
            return true
        }

        private fun indexOfStartCode(from: Int): Int {
            var i = from
            while (i + 3 < buffer.size) {
                if (buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte()) {
                    if (buffer[i + 2] == 1.toByte()) return i
                    if (buffer[i + 2] == 0.toByte() && buffer[i + 3] == 1.toByte()) return i
                }
                i++
            }
            return -1
        }

        private fun startCodeLength(at: Int): Int =
            if (buffer[at + 2] == 1.toByte()) 3 else 4
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME = "video/avc"
        private const val TIMEOUT_US = 10_000L
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
