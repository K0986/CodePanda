package com.codepanda.otg.adb

import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
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
) {
    @Volatile
    var maxPayload: Int = 256 * 1024
        private set

    @Volatile
    var deviceBanner: String = ""
        private set

    /**
     * Features the device advertised in its connect banner, e.g. `shell_v2`.
     * Capability is negotiated, never assumed: the same APK runs against phones
     * from Android 7 to 16.
     */
    @Volatile
    var features: Set<String> = emptySet()
        private set

    val transportName: String get() = transport.name

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextStreamId = AtomicInteger(1)
    private val writeLock = Any()

    private val connectedLatch = CountDownLatch(1)
    @Volatile private var connectError: Throwable? = null
    @Volatile private var connected = false
    @Volatile private var running = false
    @Volatile private var closeReason: String? = null
    private var authAttempts = 0

    private lateinit var readerThread: Thread

    /**
     * Invoked exactly once when the connection dies for a reason other than an
     * explicit [close] — a pulled cable, a device reboot, a protocol error.
     *
     * The session layer uses this to tear down cleanly and tell the user, rather
     * than leaving every screen spinning against a dead cable.
     */
    @Volatile
    var onDisconnected: ((Throwable?) -> Unit)? = null

    /** Number of streams currently open. Surfaced in logs to catch leaks. */
    val openStreamCount: Int get() = streams.size

    /**
     * Perform the CNXN/AUTH handshake. Blocks until the device accepts us or the
     * timeout elapses. If the device has never seen our key it will show the
     * "Allow USB debugging" dialog, so allow a generous timeout.
     */
    @Throws(AdbException::class)
    fun connect(timeoutMs: Long = 30_000) {
        AppLog.i(TAG, "Connecting to ${transport.name} (timeout ${timeoutMs}ms)")
        running = true
        readerThread = Thread({ readLoop() }, "adb-reader-${transport.name}").apply {
            isDaemon = true
            start()
        }
        sendMessage(AdbMessage.connect())

        if (!connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            AppLog.e(TAG, "Handshake with ${transport.name} timed out after ${timeoutMs}ms")
            close("handshake timeout")
            throw AdbAuthException(
                "Timed out connecting to ${transport.name}. Confirm the USB-debugging prompt on the device.",
            )
        }
        connectError?.let {
            AppLog.e(TAG, "Handshake with ${transport.name} failed", it)
            close("handshake failure")
            throw if (it is AdbException) it else AdbAuthException(it.message ?: "authentication failed")
        }
        AppLog.i(
            TAG,
            "Connected to ${transport.name}: maxPayload=${LogFormat.bytes(maxPayload.toLong())}, " +
                "banner=${LogFormat.preview(deviceBanner, 120)}",
        )
    }

    val isConnected: Boolean get() = connected

    /** Open a remote service (`shell:…`, `sync:`, `localabstract:…`, etc.). */
    @Throws(AdbException::class)
    fun open(service: String, timeoutMs: Long = OPEN_TIMEOUT_MS): AdbStream {
        if (!connected) {
            throw AdbTransportException(
                closeReason?.let { "not connected ($it)" } ?: "not connected to a device",
            )
        }
        if (streams.size >= MAX_CONCURRENT_STREAMS) {
            AppLog.e(
                TAG,
                "Refusing to open '$service': ${streams.size} streams already open " +
                    "(${streams.values.joinToString { it.service }})",
            )
            throw AdbServiceException("too many concurrent ADB streams open (${streams.size})")
        }

        val id = nextStreamId.getAndIncrement()
        val stream = AdbStream(id, service, this)
        streams[id] = stream
        AppLog.v(TAG, "OPEN stream $id -> $service")
        sendMessage(AdbMessage.open(id, service))
        if (!stream.awaitOpen(timeoutMs)) {
            streams.remove(id)
            AppLog.e(TAG, "Device refused or did not answer OPEN for '$service' in ${timeoutMs}ms")
            throw AdbServiceException("device refused to open service: $service")
        }
        return stream
    }

    /**
     * Open [service], hand the stream to [block], and close it no matter how
     * [block] ends — including cancellation.
     *
     * This is the single most important invariant in the app. An abandoned
     * stream is not merely a leak: adbd stops servicing *every* stream on the
     * connection once it is blocked writing to one nobody is reading, so one
     * forgotten stream silently freezes the whole UI.
     */
    fun <T> withStream(
        service: String,
        timeoutMs: Long = OPEN_TIMEOUT_MS,
        block: (AdbStream) -> T,
    ): T {
        val stream = open(service, timeoutMs)
        return try {
            block(stream)
        } finally {
            runCatching { stream.close() }
        }
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

    fun close(reason: String = "closed by app") {
        if (!running && !connected) return
        closeReason = reason
        AppLog.i(TAG, "Closing connection to ${transport.name}: $reason")
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
        var failure: Throwable? = null
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
                failure = t
                AppLog.e(TAG, "Reader loop for ${transport.name} terminated", t)
                connectError = connectError ?: t
            }
        } finally {
            val wasConnected = connected
            connected = false
            if (connectedLatch.count > 0) connectedLatch.countDown()
            streams.values.forEach { runCatching { it.onRemoteClose() } }
            streams.clear()
            if (wasConnected && running) {
                // Unexpected death (not an explicit close): tell the session.
                running = false
                runCatching { onDisconnected?.invoke(failure) }
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
                    // No such stream — tell the device to stop. This happens
                    // normally in the window between our CLSE and the device's.
                    sendMessage(AdbMessage.close(message.arg1, message.arg0))
                }
            }
            AdbProtocol.A_CLSE -> {
                streams.remove(message.arg1)?.onRemoteClose()
            }
            AdbProtocol.A_STLS -> {
                AppLog.e(TAG, "Device requested ADB-over-TLS, which this client cannot do over USB")
                connectError = AdbAuthException(
                    "Device requires ADB-over-TLS, which is not supported over USB by this client.",
                )
                if (connectedLatch.count > 0) connectedLatch.countDown()
            }
        }
    }

    private fun handleConnect(message: AdbMessage) {
        maxPayload = if (message.arg1 in 1..(1024 * 1024)) message.arg1 else maxPayload
        deviceBanner = String(message.payload, Charsets.UTF_8).trimEnd('\u0000')
        features = AdbProtocol.parseFeatures(deviceBanner)
        connected = true
        connectedLatch.countDown()
    }

    private fun handleAuth(message: AdbMessage) {
        if (message.arg0 != AdbProtocol.ADB_AUTH_TOKEN) return
        authAttempts++
        if (authAttempts == 1) {
            // First challenge: prove we hold a key the device may already trust.
            AppLog.d(TAG, "AUTH token received; replying with a signature")
            val signature = crypto.signToken(message.payload)
            sendMessage(AdbMessage.authSignature(signature))
        } else {
            // Device didn't recognise the signature — offer our public key so the
            // user can approve it via the on-device dialog.
            AppLog.i(TAG, "Signature rejected; sending public key (expect a prompt on the device)")
            sendMessage(AdbMessage.authPublicKey(crypto.adbPublicKey()))
        }
    }

    companion object {
        private const val TAG = "AdbConnection"
        private const val OPEN_TIMEOUT_MS = 15_000L

        /**
         * A sane ceiling. Normal use needs three or four streams; hitting this
         * means something is leaking, and failing loudly beats wedging quietly.
         */
        private const val MAX_CONCURRENT_STREAMS = 24
    }
}
