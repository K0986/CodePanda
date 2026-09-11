package com.codepanda.otg.adb

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A single logical ADB stream, multiplexed over the shared [AdbConnection].
 *
 * Each stream is identified by a pair of ids: our [localId] and the device's
 * [remoteId]. ADB uses a strict one-packet-at-a-time flow-control scheme — after
 * sending a `WRTE` we must wait for the peer's `OKAY` before sending the next.
 * That handshake is modelled with the [writeReady] semaphore.
 *
 * Incoming payloads are pushed onto [incoming] by the connection's reader thread
 * and consumed by callers through the blocking read helpers.
 */
class AdbStream internal constructor(
    val localId: Int,
    private val connection: AdbConnection,
) {
    @Volatile
    var remoteId: Int = 0
        internal set

    @Volatile
    private var opened = false

    @Volatile
    private var closed = false

    private val openLatch = CountDownLatch(1)
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val writeReady = Semaphore(0)

    // Leftover bytes from a chunk that a reader only partially consumed.
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    val isOpen: Boolean get() = opened && !closed
    val isClosed: Boolean get() = closed

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
        if (!closed) incoming.offer(data)
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
            if (closed) throw AdbServiceException("stream $localId closed")
            val len = minOf(max, data.size - offset)
            if (!writeReady.tryAcquire(WRITE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw AdbServiceException("timed out waiting for write ack on stream $localId")
            }
            if (closed) throw AdbServiceException("stream $localId closed")
            val chunk = if (offset == 0 && len == data.size) data
            else data.copyOfRange(offset, offset + len)
            connection.sendMessage(AdbMessage.write(localId, remoteId, chunk))
            offset += len
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * Read up to [max] bytes. Returns `null` once the device has closed the
     * stream and all buffered data has been drained (i.e. EOF).
     */
    fun read(max: Int = Int.MAX_VALUE): ByteArray? {
        val chunk = pending ?: run {
            val next = incoming.take()
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
                ?: throw AdbServiceException("unexpected EOF on stream $localId")
            System.arraycopy(chunk, 0, buffer, offset + read, chunk.size)
            read += chunk.size
        }
    }

    fun readExact(length: Int): ByteArray {
        val buffer = ByteArray(length)
        readExact(buffer, 0, length)
        return buffer
    }

    /** Read everything until EOF and return it as one array. */
    fun readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val chunk = read() ?: break
            out.write(chunk)
        }
        return out.toByteArray()
    }

    fun readAllText(): String = String(readAll(), Charsets.UTF_8)

    /** Politely close the stream from our side. */
    fun close() {
        if (closed) return
        closed = true
        runCatching { connection.sendMessage(AdbMessage.close(localId, remoteId)) }
        connection.unregister(localId)
    }

    companion object {
        private val EOF = ByteArray(0)
        private const val WRITE_ACK_TIMEOUT_MS = 30_000L
    }
}
