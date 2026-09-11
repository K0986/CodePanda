package com.codepanda.otg.adb

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An in-memory [AdbTransport] that lets a test play the role of `adbd`.
 *
 * Inbound bytes are a byte stream, exactly like a USB endpoint, so the
 * connection's reader thread blocks on [readFully] until the "device" supplies
 * more. Outbound traffic is recorded one entry per [write] call, which is what
 * lets tests assert *how* a packet was split on the wire.
 */
class FakeAdbTransport : AdbTransport {

    override val name: String = "fake-device"

    private val writes = LinkedBlockingQueue<ByteArray>()
    private val lock = Object()
    private val inbound = ArrayDeque<Byte>()
    private var waitingReaders = 0
    private var closed = false

    // ---- AdbTransport ------------------------------------------------------

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (closed) throw AdbTransportException("closed")
        writes.put(data.copyOfRange(offset, offset + length))
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            synchronized(lock) {
                while (inbound.isEmpty() && !closed) {
                    waitingReaders++
                    lock.notifyAll()
                    lock.wait(1_000)
                    waitingReaders--
                }
                if (closed && inbound.isEmpty()) throw AdbTransportException("closed")
                while (read < length && inbound.isNotEmpty()) {
                    buffer[offset + read] = inbound.removeFirst()
                    read++
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    // ---- Test control ------------------------------------------------------

    /** Queue [message] as if the device had sent it. */
    fun deviceSends(message: AdbMessage) {
        deviceSendsRaw(message.headerBytes())
        if (message.payload.isNotEmpty()) deviceSendsRaw(message.payload)
    }

    fun deviceSendsRaw(bytes: ByteArray) {
        synchronized(lock) {
            bytes.forEach { inbound.addLast(it) }
            lock.notifyAll()
        }
    }

    /** The next buffer the host handed to the transport, one per write call. */
    fun nextWrite(timeoutMs: Long = 2_000): ByteArray =
        writes.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("host wrote nothing within ${timeoutMs}ms")

    fun nextMessage(timeoutMs: Long = 2_000): AdbMessage {
        val header = nextWrite(timeoutMs)
        require(header.size == AdbProtocol.HEADER_LENGTH) {
            "expected a ${AdbProtocol.HEADER_LENGTH}-byte header, got ${header.size} bytes"
        }
        val parsed = parseHeader(header)
        val payload = if (parsed.length > 0) nextWrite(timeoutMs) else AdbMessage.EMPTY
        return AdbMessage(parsed.command, parsed.arg0, parsed.arg1, payload)
    }

    /** Drain and return every packet written so far, without blocking. */
    fun drainMessages(): List<AdbMessage> {
        val result = mutableListOf<AdbMessage>()
        while (writes.isNotEmpty()) result += nextMessage(timeoutMs = 100)
        return result
    }

    /**
     * Block until the reader thread has consumed every queued byte and parked
     * waiting for more. Reaching that state means the last packet we queued has
     * already been dispatched, which keeps tests free of arbitrary sleeps.
     */
    fun awaitReaderIdle(timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (inbound.isNotEmpty() || waitingReaders == 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw AssertionError("reader thread never went idle")
                lock.wait(remaining)
            }
        }
    }

    private data class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int)

    private fun parseHeader(bytes: ByteArray): Header {
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return Header(buffer.int, buffer.int, buffer.int, buffer.int)
    }
}
