package com.codepanda.otg.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.codepanda.otg.adb.AdbTransport
import com.codepanda.otg.adb.AdbTransportException

/**
 * [AdbTransport] implementation over a USB OTG connection.
 *
 * A phone in "USB debugging" mode exposes an interface with two bulk endpoints
 * (one IN, one OUT). We claim that interface and shuttle raw ADB packets across
 * the endpoints with [UsbDeviceConnection.bulkTransfer].
 *
 * Two practical hazards are handled here:
 *  - **Chunking:** some kernels reject a single bulk transfer larger than a few
 *    KiB, so reads and writes are split into [CHUNK] sized pieces.
 *  - **Interruptible reads:** a blocking read is unblocked by releasing the
 *    interface / closing the connection on another thread; the in-flight
 *    transfer then returns -1 which we translate into a clean exception.
 */
class UsbAdbTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    override val name: String,
) : AdbTransport {

    @Volatile
    private var closed = false

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var sent = 0
        while (sent < length) {
            if (closed) throw AdbTransportException("USB transport closed")
            val toSend = minOf(CHUNK, length - sent)
            val transferred = connection.bulkTransfer(
                endpointOut,
                data,
                offset + sent,
                toSend,
                WRITE_TIMEOUT_MS,
            )
            if (transferred < 0) {
                throw AdbTransportException("USB write failed at byte $sent/$length")
            }
            sent += transferred
        }
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            if (closed) throw AdbTransportException("USB transport closed")
            val toRead = minOf(CHUNK, length - read)
            val transferred = connection.bulkTransfer(
                endpointIn,
                buffer,
                offset + read,
                toRead,
                READ_TIMEOUT_MS,
            )
            if (transferred < 0) {
                if (closed) throw AdbTransportException("USB transport closed")
                // Infinite timeout means -1 is a genuine I/O error, not a poll miss.
                throw AdbTransportException("USB read failed after $read/$length bytes")
            }
            read += transferred
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        /** Conservative per-transfer size that works across kernels/OEMs. */
        private const val CHUNK = 16 * 1024

        /** 0 == block until data is available (used by the dedicated reader). */
        private const val READ_TIMEOUT_MS = 0

        private const val WRITE_TIMEOUT_MS = 10_000
    }
}
