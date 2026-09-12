package com.codepanda.otg.adb

import java.io.Closeable

/**
 * A bidirectional, reliable byte pipe to `adbd`. The ADB protocol is transport
 * agnostic — it runs over USB bulk endpoints here, but the same [AdbConnection]
 * could just as easily drive a TCP socket, which is why this is an interface.
 */
interface AdbTransport : Closeable {
    /** Write [length] bytes from [data] starting at [offset]. Blocks until sent. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size)

    /** Fully read exactly [length] bytes into [buffer] starting at [offset]. */
    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    /** Human readable identifier for logs / UI (e.g. USB serial). */
    val name: String
}

/** Base type for every recoverable ADB failure. */
open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the device rejects or never completes authentication. */
class AdbAuthException(message: String) : AdbException(message)

/** Thrown when a remote service (shell/sync/…) fails to open or reports FAIL. */
class AdbServiceException(message: String) : AdbException(message)

/** Thrown when the transport dies unexpectedly (cable pulled, device rebooted). */
class AdbTransportException(message: String, cause: Throwable? = null) :
    AdbException(message, cause)

/**
 * Thrown when a stream makes no progress within its budget.
 *
 * Distinct from the other failures because it is the one the UI must never
 * swallow: a timeout is the difference between "this is slow" and "this will
 * never finish", and turning it into a visible error is what stops a screen
 * spinning forever.
 */
class AdbTimeoutException(message: String) : AdbException(message)
