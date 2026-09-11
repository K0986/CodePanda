package com.codepanda.otg.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A single ADB packet: a 24-byte little-endian header plus an optional payload.
 */
data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    /** Serialise header + payload into a single contiguous buffer for transport. */
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer
            .allocate(AdbProtocol.HEADER_LENGTH + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(payload.size)
        buffer.putInt(AdbProtocol.payloadChecksum(payload))
        buffer.putInt(command xor -0x1) // magic = command ^ 0xffffffff
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbMessage) return false
        return command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "AdbMessage(${AdbProtocol.commandName(command)}, arg0=$arg0, arg1=$arg1, len=${payload.size})"

    companion object {
        val EMPTY = ByteArray(0)

        fun connect(): AdbMessage = AdbMessage(
            command = AdbProtocol.A_CNXN,
            arg0 = AdbProtocol.A_VERSION,
            arg1 = AdbProtocol.MAX_PAYLOAD,
            payload = (AdbProtocol.CONNECT_PAYLOAD + "\u0000").toByteArray(Charsets.UTF_8),
        )

        fun authSignature(signature: ByteArray): AdbMessage = AdbMessage(
            command = AdbProtocol.A_AUTH,
            arg0 = AdbProtocol.ADB_AUTH_SIGNATURE,
            arg1 = 0,
            payload = signature,
        )

        fun authPublicKey(publicKey: ByteArray): AdbMessage = AdbMessage(
            command = AdbProtocol.A_AUTH,
            arg0 = AdbProtocol.ADB_AUTH_RSAPUBLICKEY,
            arg1 = 0,
            // adbd expects a null-terminated key blob.
            payload = publicKey + 0.toByte(),
        )

        fun open(localId: Int, service: String): AdbMessage = AdbMessage(
            command = AdbProtocol.A_OPEN,
            arg0 = localId,
            arg1 = 0,
            // The service destination is null-terminated on the wire.
            payload = (service + "\u0000").toByteArray(Charsets.UTF_8),
        )

        fun okay(localId: Int, remoteId: Int): AdbMessage =
            AdbMessage(AdbProtocol.A_OKAY, localId, remoteId)

        fun write(localId: Int, remoteId: Int, data: ByteArray): AdbMessage =
            AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, data)

        fun close(localId: Int, remoteId: Int): AdbMessage =
            AdbMessage(AdbProtocol.A_CLSE, localId, remoteId)
    }
}
