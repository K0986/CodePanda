package com.codepanda.otg.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire-format tests for the ADB packet header. These are the bytes adbd parses,
 * so an accidental change here breaks every connection.
 */
class AdbMessageTest {

    private fun header(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes, 0, AdbProtocol.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `header is 24 little-endian bytes followed by the payload`() {
        val payload = byteArrayOf(1, 2, 3)
        val bytes = AdbMessage(AdbProtocol.A_WRTE, 7, 9, payload).toBytes()

        assertEquals(AdbProtocol.HEADER_LENGTH + payload.size, bytes.size)
        val h = header(bytes)
        assertEquals(AdbProtocol.A_WRTE, h.int)
        assertEquals(7, h.int)
        assertEquals(9, h.int)
        assertEquals(payload.size, h.int)
        assertEquals(6, h.int) // checksum = 1 + 2 + 3
        assertEquals(AdbProtocol.A_WRTE xor -0x1, h.int)
        assertArrayEquals(payload, bytes.copyOfRange(AdbProtocol.HEADER_LENGTH, bytes.size))
    }

    @Test
    fun `magic is the command complement for every command`() {
        listOf(
            AdbProtocol.A_CNXN, AdbProtocol.A_AUTH, AdbProtocol.A_OPEN,
            AdbProtocol.A_OKAY, AdbProtocol.A_CLSE, AdbProtocol.A_WRTE,
        ).forEach { command ->
            val h = header(AdbMessage(command, 0, 0).toBytes())
            repeat(5) { h.int }
            assertEquals("magic for ${AdbProtocol.commandName(command)}", command xor -0x1, h.int)
        }
    }

    @Test
    fun `checksum is the unsigned sum of payload bytes`() {
        // 0xFF must count as 255, not -1.
        assertEquals(255 * 4, AdbProtocol.payloadChecksum(ByteArray(4) { 0xFF.toByte() }))
        assertEquals(0, AdbProtocol.payloadChecksum(ByteArray(0)))
    }

    @Test
    fun `service names and connect banner are null-terminated`() {
        val open = AdbMessage.open(localId = 3, service = "shell:ls")
        val openPayload = String(open.payload, Charsets.UTF_8)
        assertEquals("shell:ls\u0000", openPayload)
        assertEquals(3, open.arg0)

        val connect = AdbMessage.connect()
        assertEquals(0.toByte(), connect.payload.last())
        assertEquals(AdbProtocol.A_VERSION, connect.arg0)
        assertEquals(AdbProtocol.MAX_PAYLOAD, connect.arg1)
    }

    @Test
    fun `public key payload is null-terminated as adbd expects`() {
        val message = AdbMessage.authPublicKey("abc".toByteArray())
        assertEquals(AdbProtocol.ADB_AUTH_RSAPUBLICKEY, message.arg0)
        assertArrayEquals("abc".toByteArray() + 0.toByte(), message.payload)
    }

    @Test
    fun `equals and hashCode compare payload contents not identity`() {
        val a = AdbMessage(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(9, 9))
        val b = AdbMessage(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(9, 9))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
