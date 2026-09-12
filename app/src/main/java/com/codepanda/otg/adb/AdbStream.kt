package com.codepanda.otg.adb

import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A single logical ADB stream, multiplexed over the shared [AdbConnection].
 *
 * Each stream is identified by a pair of ids: our [localId] and the device's
 * [remoteId]. ADB uses a strict one-packet-at-a-time flow-control scheme — after
 * sending a `WRTE` we must wait for the peer's `OKAY` before sending the next.
 * That handshake is modelled with the [writeReady] semaphore.
 *
 * Two properties matter for a UI that the user can navigate away from at any
 * moment:
 *
 *  - **Every blocking call is bounded.** A read or write that makes no progress
 *    within its timeout throws [AdbTimeoutException] instead of parking a thread
 *    forever. A stalled stream used to be indistinguishable from a slow one, and
 *    a parked thread still holds a stream open, which is what wedges adbd.
 *  - **Every blocking call is interruptible.** The queue and semaphore both
 *    throw `InterruptedException`, so wrapping calls in `runInterruptible` makes
 *    coroutine cancellation actually cancel the I/O.
 */
class AdbStream internal constructor(
    val localId: Int,
    val service: String,
    private val connection: AdbConnection,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) {
    @Volatile
    var remoteId: Int = 0
        internal set

    @Volatile
    private var opened = false

    @Volatile
    private var closed = false

    private val openLatch = CountDownLatch(1)

    /**
     * Inbound payloads. Bounded on purpose: ADB permits a single unacknowledged
     * `WRTE` per stream, so a handful of slots is always enough, and a bound
     * means a fast producer can never grow the heap without limit.
     */
    private val incoming = ArrayBlockingQueue<ByteArray>(INCOMING_CAPACITY)
    private val writeReady = Semaphore(0)

    private val bytesRead = AtomicLong(0)
    private val bytesWritten = AtomicLong(0)

    // Leftover bytes from a chunk that a reader only partially consumed.
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    val isOpen: Boolean get() = opened && !closed
    val isClosed: Boolean get() = closed
    val totalBytesRead: Long get() = bytesRead.get()
    val totalBytesWritten: Long get() = bytesWritten.get()

    // ---- Called by the reader thread --------------------------------------

    /** First `OKAY` after our `OPEN`: the stream is live and we may write once. */
    internal fun onOpened(remoteStreamId: Int) {
        remoteId = remoteStreamId
        opened = true
        writeReady.release()
        openLatch.countDown()
    }

    internal val isOpened: Boolean get() = opened

    /** A subsequent `OKAY`: the device acknowledged our last `WRTE`. */
    internal fun onReady() {
        writeReady.release()
    }

    internal fun onPayload(data: ByteArray) {
        if (closed) return
        bytesRead.addAndGet(data.size.toLong())
        // The queue is bounded, but because we only ack a payload once a reader
        // has taken it, the device cannot be more than INCOMING_CAPACITY packets
        // ahead of us; offer() therefore does not drop data in practice. If it
        // ever did, silence would corrupt the stream, so it is logged loudly.
        if (!incoming.offer(data)) {
            AppLog.e(TAG, "stream $localId ($service) dropped ${data.size} B: inbound queue full")
        }
    }

    internal fun onRemoteClose() {
        closed = true
        incoming.offer(EOF)
        writeReady.release() // unblock a stuck writer
        openLatch.countDown() // unblock awaitOpen (which will see !isOpen)
    }

    /** Block until the device acknowledges the open, or the attempt fails. */
    internal fun awaitOpen(timeoutMs: Long): Boolean {
        openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return opened && !closed
    }

    // ---- Public API --------------------------------------------------------

    /** Send [data] to the device, honouring flow control and max payload size. */
    fun write(data: ByteArray) {
        var offset = 0
        val max = connection.maxPayload
        while (offset < data.size) {
            if (closed) throw AdbServiceException("stream $localId ($service) is closed")
            val len = minOf(max, data.size - offset)
            if (!writeReady.tryAcquire(WRITE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw AdbTimeoutException(
                    "timed out after ${WRITE_ACK_TIMEOUT_MS}ms waiting for a write ack on " +
                        "stream $localId ($service)",
                )
            }
            if (closed) throw AdbServiceException("stream $localId ($service) is closed")
            val chunk = if (offset == 0 && len == data.size) data
            else data.copyOfRange(offset, offset + len)
            connection.sendMessage(AdbMessage.write(localId, remoteId, chunk))
            bytesWritten.addAndGet(len.toLong())
            offset += len
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * Read up to [max] bytes. Returns `null` once the device has closed the
     * stream and all buffered data has been drained (i.e. EOF).
     *
     * Handing a chunk to the caller is also what releases the device to send the
     * next one: see [ackConsumed].
     *
     * @throws AdbTimeoutException if nothing arrives within the read timeout.
     */
    fun read(max: Int = Int.MAX_VALUE): ByteArray? {
        val chunk = pending ?: run {
            val next = incoming.poll(readTimeoutMs, TimeUnit.MILLISECONDS)
                ?: throw AdbTimeoutException(
                    "no data for ${readTimeoutMs}ms on stream $localId ($service)",
                )
            if (next === EOF) {
                // Re-arm EOF so repeated reads keep returning null.
                incoming.offer(EOF)
                return null
            }
            next
        }
        val available = chunk.size - pendingOffset
        return if (available <= max) {
            val result = if (pendingOffset == 0) chunk
            else chunk.copyOfRange(pendingOffset, chunk.size)
            pending = null
            pendingOffset = 0
            ackConsumed()
            result
        } else {
            val slice = chunk.copyOfRange(pendingOffset, pendingOffset + max)
            pending = chunk
            pendingOffset += max
            slice
        }
    }

    /** Fully populate [buffer]`[offset, offset+length)`, throwing on early EOF. */
    fun readExact(buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val chunk = read(length - read)
                ?: throw AdbServiceException(
                    "unexpected EOF on stream $localId ($service) after $read/$length bytes",
                )
            System.arraycopy(chunk, 0, buffer, offset + read, chunk.size)
            read += chunk.size
        }
    }

    fun readExact(length: Int): ByteArray {
        val buffer = ByteArray(length)
        readExact(buffer, 0, length)
        return buffer
    }

    /**
     * Read everything until EOF and return it as one array.
     *
     * [limitBytes] is a safety valve: a stray `dumpsys` or a `cat` of the wrong
     * file could otherwise return hundreds of megabytes into the heap of a phone
     * that is also decoding video.
     */
    fun readAll(limitBytes: Int = DEFAULT_READ_ALL_LIMIT): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val chunk = read() ?: break
            out.write(chunk)
            if (out.size() > limitBytes) {
                AppLog.w(
                    TAG,
                    "stream $localId ($service) exceeded ${LogFormat.bytes(limitBytes.toLong())}; " +
                        "truncating output",
                )
                break
            }
        }
        return out.toByteArray()
    }

    fun readAllText(limitBytes: Int = DEFAULT_READ_ALL_LIMIT): String =
        String(readAll(limitBytes), Charsets.UTF_8)

    /** Politely close the stream from our side. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        // Wake any reader blocked in poll(); without this the consumer thread
        // (e.g. the video decoder) would wait out its whole timeout, because the
        // device's CLSE reply can no longer be routed to this stream once it is
        // unregistered.
        incoming.offer(EOF)
        writeReady.release()
        runCatching { connection.sendMessage(AdbMessage.close(localId, remoteId)) }
        connection.unregister(localId)
        AppLog.v(
            TAG,
            "closed stream $localId ($service): read ${LogFormat.bytes(bytesRead.get())}, " +
                "wrote ${LogFormat.bytes(bytesWritten.get())}",
        )
    }

    /**
     * Tell the device it may send the next `WRTE` on this stream.
     *
     * ADB allows only one unacknowledged `WRTE` per stream, so deferring this
     * `OKAY` until the consumer has actually taken the payload is what bounds
     * our memory use: acking on arrival instead lets a fast producer (a
     * `screenrecord` feed, a file pull) queue up without limit.
     */
    private fun ackConsumed() {
        if (closed) return
        runCatching { connection.sendMessage(AdbMessage.okay(localId, remoteId)) }
    }

    companion object {
        private const val TAG = "AdbStream"
        private val EOF = ByteArray(0)
        private const val WRITE_ACK_TIMEOUT_MS = 20_000L
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        private const val INCOMING_CAPACITY = 8
        private const val DEFAULT_READ_ALL_LIMIT = 16 * 1024 * 1024
    }
}
