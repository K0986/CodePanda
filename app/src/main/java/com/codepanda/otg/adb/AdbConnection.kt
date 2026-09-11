package com.codepanda.otg.adb

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the ADB protocol over an [AdbTransport]: performs the connect +
 * authentication handshake, then multiplexes any number of [AdbStream]s.
 *
 * A single dedicated reader thread owns all inbound traffic and fans messages
 * out to the relevant stream. Outbound writes from arbitrary threads are
 * serialised behind [writeLock]. This "one reader, many writers" design is what
 * lets the file manager, shell and screen mirror all share one USB cable at once.
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val crypto: AdbCrypto,
    /**
     * Invoked at most once, from the reader thread, when an established
     * connection dies for a reason other than our own [close] — the target
     * rebooted, adbd restarted, the user revoked the debugging authorisation.
     * Without this the session state would stay "connected" while every
     * subsequent operation failed with a cryptic low-level error.
     */
    private val onClosed: (Throwable?) -> Unit = {},
) {
    @Volatile
    var maxPayload: Int = 256 * 1024
        private set

    @Volatile
    var deviceBanner: String = ""
        private set

    val transportName: String get() = transport.name

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextStreamId = AtomicInteger(1)
    private val writeLock = Any()

    private val connectedLatch = CountDownLatch(1)
    @Volatile private var connectError: Throwable? = null
    @Volatile private var connected = false
    @Volatile private var running = false
    @Volatile private var closedByUs = false
    private var authAttempts = 0

    private lateinit var readerThread: Thread

    /**
     * Perform the CNXN/AUTH handshake. Blocks until the device accepts us or the
     * timeout elapses. If the device has never seen our key it will show the
     * "Allow USB debugging" dialog, so allow a generous timeout.
     */
    @Throws(AdbException::class)
    fun connect(timeoutMs: Long = 30_000) {
        running = true
        readerThread = Thread({ readLoop() }, "adb-reader-${transport.name}").apply {
            isDaemon = true
            start()
        }
        sendMessage(AdbMessage.connect())

        if (!connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw AdbAuthException("Timed out connecting to ${transport.name}. Confirm the USB-debugging prompt on the device.")
        }
        connectError?.let {
            close()
            throw if (it is AdbException) it else AdbAuthException(it.message ?: "authentication failed")
        }
    }

    val isConnected: Boolean get() = connected

    /** Open a remote service (`shell:…`, `sync:`, `localabstract:…`, etc.). */
    @Throws(AdbException::class)
    fun open(service: String, timeoutMs: Long = 15_000): AdbStream {
        check(connected) { "not connected" }
        val id = nextStreamId.getAndIncrement()
        val stream = AdbStream(id, this)
        streams[id] = stream
        sendMessage(AdbMessage.open(id, service))
        if (!stream.awaitOpen(timeoutMs)) {
            streams.remove(id)
            throw AdbServiceException("device refused to open service: $service")
        }
        return stream
    }

    internal fun sendMessage(message: AdbMessage) {
        val bytes = message.toBytes()
        synchronized(writeLock) {
            transport.write(bytes)
        }
    }

    internal fun unregister(localId: Int) {
        streams.remove(localId)
    }

    fun close() {
        closedByUs = true
        running = false
        connected = false
        streams.values.forEach { runCatching { it.onRemoteClose() } }
        streams.clear()
        runCatching { transport.close() }
        if (connectedLatch.count > 0) connectedLatch.countDown()
    }

    // ---- Reader thread -----------------------------------------------------

    private fun readLoop() {
        val header = ByteArray(AdbProtocol.HEADER_LENGTH)
        try {
            while (running) {
                transport.readFully(header, 0, header.size)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val command = buffer.int
                val arg0 = buffer.int
                val arg1 = buffer.int
                val dataLength = buffer.int
                buffer.int // data checksum (ignored; adbd doesn't verify ours either on new versions)
                val magic = buffer.int

                if (magic != (command xor -0x1)) {
                    throw AdbTransportException("corrupt header: magic mismatch")
                }
                if (dataLength < 0 || dataLength > AdbProtocol.MAX_PAYLOAD) {
                    throw AdbTransportException("corrupt header: payload length $dataLength")
                }

                val payload = if (dataLength > 0) {
                    ByteArray(dataLength).also { transport.readFully(it, 0, dataLength) }
                } else {
                    AdbMessage.EMPTY
                }

                dispatch(AdbMessage(command, arg0, arg1, payload))
            }
        } catch (t: Throwable) {
            if (running) {
                Log.w(TAG, "reader loop terminated: ${t.message}")
                connectError = connectError ?: t
            }
        } finally {
            val wasConnected = connected
            connected = false
            if (connectedLatch.count > 0) connectedLatch.countDown()
            streams.values.forEach { runCatching { it.onRemoteClose() } }
            // Only report an unexpected death: a failure during the handshake is
            // already surfaced by connect() throwing, and our own close() is not
            // news to anyone.
            if (wasConnected && !closedByUs) {
                runCatching { onClosed(connectError) }
            }
        }
    }

    private fun dispatch(message: AdbMessage) {
        when (message.command) {
            AdbProtocol.A_CNXN -> handleConnect(message)
            AdbProtocol.A_AUTH -> handleAuth(message)
            AdbProtocol.A_OKAY -> {
                val stream = streams[message.arg1] ?: return
                if (!stream.isOpened) stream.onOpened(message.arg0) else stream.onReady()
            }
            AdbProtocol.A_WRTE -> {
                val stream = streams[message.arg1]
                if (stream != null) {
                    // Flow control: the OKAY is sent by the stream once a reader
                    // takes the payload, which throttles the device to the speed
                    // of our consumer instead of buffering without limit.
                    stream.onPayload(message.payload)
                } else {
                    // No such stream — tell the device to stop.
                    sendMessage(AdbMessage.close(message.arg1, message.arg0))
                }
            }
            AdbProtocol.A_CLSE -> {
                streams.remove(message.arg1)?.onRemoteClose()
            }
            AdbProtocol.A_STLS -> {
                connectError = AdbAuthException("Device requires ADB-over-TLS, which is not supported over USB by this client.")
                if (connectedLatch.count > 0) connectedLatch.countDown()
            }
        }
    }

    private fun handleConnect(message: AdbMessage) {
        maxPayload = if (message.arg1 in 1..(1024 * 1024)) message.arg1 else maxPayload
        deviceBanner = String(message.payload, Charsets.UTF_8).trimEnd('\u0000')
        connected = true
        connectedLatch.countDown()
    }

    private fun handleAuth(message: AdbMessage) {
        if (message.arg0 != AdbProtocol.ADB_AUTH_TOKEN) return
        authAttempts++
        if (authAttempts == 1) {
            // First challenge: prove we hold a key the device may already trust.
            val signature = crypto.signToken(message.payload)
            sendMessage(AdbMessage.authSignature(signature))
        } else {
            // Device didn't recognise the signature — offer our public key so the
            // user can approve it via the on-device dialog.
            sendMessage(AdbMessage.authPublicKey(crypto.adbPublicKey()))
        }
    }

    companion object {
        private const val TAG = "AdbConnection"
    }
}
