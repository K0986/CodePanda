package com.codepanda.otg.adb

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

/**
 * Drives [AdbConnection] against a scripted fake device. These cover the parts
 * of the protocol that are impossible to eyeball: how a packet is split on the
 * wire, when flow-control acknowledgements are emitted, and what happens to a
 * blocked reader when a stream goes away.
 */
class AdbConnectionTest {

    private lateinit var transport: FakeAdbTransport
    private lateinit var connection: AdbConnection

    @Before
    fun setUp() {
        transport = FakeAdbTransport()
        connection = AdbConnection(transport, crypto)
    }

    @After
    fun tearDown() {
        connection.close()
    }

    // ---- handshake ---------------------------------------------------------

    @Test
    fun `connect writes the header and the banner as separate transfers`() {
        handshake(deviceMaxPayload = 256 * 1024)

        // adbd reads a packet as a fixed 24-byte header plus a second read of
        // exactly data_length bytes, so the two must not be merged.
        assertEquals(AdbProtocol.HEADER_LENGTH, firstWriteSize)
        assertEquals(AdbProtocol.CONNECT_PAYLOAD.length + 1, secondWriteSize)
        assertTrue(connection.isConnected)
        assertEquals("device::ro.product.name=test", connection.deviceBanner)
    }

    @Test
    fun `payload size is negotiated down to what the device offers`() {
        handshake(deviceMaxPayload = 256 * 1024)
        assertEquals(256 * 1024, connection.maxPayload)
    }

    @Test
    fun `a device asking for more than we advertised is clamped`() {
        handshake(deviceMaxPayload = 8 * 1024 * 1024)
        assertEquals(AdbProtocol.MAX_PAYLOAD, connection.maxPayload)
    }

    @Test
    fun `a pre-large-payload device is held to the v1 packet size`() {
        handshake(deviceMaxPayload = 256 * 1024, deviceVersion = AdbProtocol.A_VERSION_MIN - 1)
        assertEquals(AdbProtocol.MAX_PAYLOAD_V1, connection.maxPayload)
    }

    // ---- streams -----------------------------------------------------------

    @Test
    fun `opening a stream sends OPEN and waits for the device's OKAY`() {
        handshake()
        val stream = openStream(service = "shell:ls")

        assertEquals(REMOTE_ID, stream.remoteId)
        assertTrue(stream.isOpen)
    }

    @Test
    fun `payloads are acknowledged only once a consumer has read them`() {
        handshake()
        val stream = openStream()

        transport.deviceSends(AdbMessage.write(REMOTE_ID, stream.localId, "one".toByteArray()))
        transport.deviceSends(AdbMessage.write(REMOTE_ID, stream.localId, "two".toByteArray()))
        transport.awaitReaderIdle()

        // Both packets have been dispatched, yet nothing is acked: that back
        // pressure is what stops a fast producer from filling our heap.
        assertEquals(0, transport.drainMessages().count { it.command == AdbProtocol.A_OKAY })

        assertArrayEquals("one".toByteArray(), stream.read())
        assertEquals(1, transport.drainMessages().count { it.command == AdbProtocol.A_OKAY })

        assertArrayEquals("two".toByteArray(), stream.read())
        assertEquals(1, transport.drainMessages().count { it.command == AdbProtocol.A_OKAY })
    }

    @Test
    fun `a partially consumed payload is acknowledged when it is drained`() {
        handshake()
        val stream = openStream()

        transport.deviceSends(AdbMessage.write(REMOTE_ID, stream.localId, "abcdef".toByteArray()))
        transport.awaitReaderIdle()

        assertArrayEquals("abc".toByteArray(), stream.read(max = 3))
        assertEquals(0, transport.drainMessages().count { it.command == AdbProtocol.A_OKAY })

        assertArrayEquals("def".toByteArray(), stream.read(max = 3))
        assertEquals(1, transport.drainMessages().count { it.command == AdbProtocol.A_OKAY })
    }

    @Test
    fun `closing a stream releases a reader blocked on it`() {
        handshake()
        val stream = openStream()

        var result: ByteArray? = byteArrayOf(1)
        val reader = thread { result = stream.read() }
        // Give the reader a moment to park inside read().
        Thread.sleep(100)

        stream.close()
        reader.join(2_000)

        // Before the fix this thread parked forever: the device's CLSE could no
        // longer be routed to an unregistered stream, so nothing woke it.
        assertFalse("reader thread is still blocked", reader.isAlive)
        assertNull(result)
    }

    @Test
    fun `remote close surfaces as EOF and stays at EOF`() {
        handshake()
        val stream = openStream()

        transport.deviceSends(AdbMessage.write(REMOTE_ID, stream.localId, "tail".toByteArray()))
        transport.deviceSends(AdbMessage.close(REMOTE_ID, stream.localId))
        transport.awaitReaderIdle()

        assertArrayEquals("tail".toByteArray(), stream.read())
        assertNull(stream.read())
        assertNull(stream.read())
        assertTrue(stream.isClosed)
    }

    @Test
    fun `writes are split at the negotiated payload size and wait for each ack`() {
        handshake(deviceMaxPayload = AdbProtocol.MAX_PAYLOAD_V1)
        val stream = openStream()
        val data = ByteArray(AdbProtocol.MAX_PAYLOAD_V1 + 100) { it.toByte() }

        val writer = thread { stream.write(data) }

        val first = transport.nextMessage()
        assertEquals(AdbProtocol.A_WRTE, first.command)
        assertEquals(AdbProtocol.MAX_PAYLOAD_V1, first.payload.size)

        // The second chunk may only leave once the device acks the first one.
        transport.deviceSends(AdbMessage.okay(REMOTE_ID, stream.localId))
        val second = transport.nextMessage()
        assertEquals(100, second.payload.size)
        assertArrayEquals(data, first.payload + second.payload)

        writer.join(2_000)
        assertFalse(writer.isAlive)
    }

    // ---- framing errors ----------------------------------------------------

    @Test
    fun `an absurd payload length tears the connection down instead of crashing`() {
        handshake()

        // data_length is unsigned on the wire; 0xFFFFFFFF used to reach
        // ByteArray(-1) and take the reader thread out with an error.
        transport.deviceSendsRaw(
            corruptHeader(command = AdbProtocol.A_WRTE, dataLength = -1),
        )

        assertTrue(awaitDisconnect())
    }

    @Test
    fun `a header with a bad magic tears the connection down`() {
        handshake()
        val header = corruptHeader(command = AdbProtocol.A_WRTE, dataLength = 0)
        header[header.size - 1] = 0 // clobber the magic word
        transport.deviceSendsRaw(header)

        assertTrue(awaitDisconnect())
    }

    // ---- helpers -----------------------------------------------------------

    private var firstWriteSize = 0
    private var secondWriteSize = 0

    private fun handshake(
        deviceMaxPayload: Int = 256 * 1024,
        deviceVersion: Int = AdbProtocol.A_VERSION,
    ) {
        val device = thread {
            firstWriteSize = transport.nextWrite().size
            secondWriteSize = transport.nextWrite().size
            transport.deviceSends(
                AdbMessage(
                    command = AdbProtocol.A_CNXN,
                    arg0 = deviceVersion,
                    arg1 = deviceMaxPayload,
                    payload = "device::ro.product.name=test\u0000".toByteArray(),
                ),
            )
        }
        connection.connect(timeoutMs = 5_000)
        device.join(2_000)
    }

    private fun openStream(service: String = "shell:"): AdbStream {
        val device = thread {
            val open = transport.nextMessage()
            require(open.command == AdbProtocol.A_OPEN) { "expected OPEN, got $open" }
            transport.deviceSends(AdbMessage.okay(REMOTE_ID, open.arg0))
        }
        val stream = connection.open(service, timeoutMs = 5_000)
        device.join(2_000)
        return stream
    }

    private fun corruptHeader(command: Int, dataLength: Int): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(AdbProtocol.HEADER_LENGTH)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(REMOTE_ID)
        buffer.putInt(1)
        buffer.putInt(dataLength)
        buffer.putInt(0)
        buffer.putInt(command xor -0x1)
        return buffer.array()
    }

    private fun awaitDisconnect(timeoutMs: Long = 2_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!connection.isConnected) return true
            Thread.sleep(10)
        }
        return false
    }

    private companion object {
        const val REMOTE_ID = 77

        /** Generating an RSA key is slow, and no test depends on a fresh one. */
        val crypto: AdbCrypto by lazy {
            AdbCrypto.loadOrCreate(createTempDirectory("adbkeys").toFile())
        }
    }
}
