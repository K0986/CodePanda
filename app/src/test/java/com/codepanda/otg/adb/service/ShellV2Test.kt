package com.codepanda.otg.adb.service

import com.codepanda.otg.adb.AdbProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The framing and status parsing that decide whether an operation is reported as
 * having worked. These used to be guessed from the presence of the word
 * "Success" in stdout, which is why failed `pm` commands looked successful.
 */
class ShellV2Test {

    @Test
    fun `frames are one id byte plus a little-endian length`() {
        val payload = "hello".toByteArray()
        val frame = ShellV2.encode(ShellV2.ID_STDOUT, payload)

        assertEquals(ShellV2.HEADER_SIZE + payload.size, frame.size)
        assertEquals(ShellV2.ID_STDOUT.toByte(), frame[0])
        val length = ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(payload.size, length)
        assertEquals("hello", String(frame, ShellV2.HEADER_SIZE, length))
    }

    @Test
    fun `headers round trip`() {
        val header = ShellV2.decodeHeader(ShellV2.encode(ShellV2.ID_STDERR, ByteArray(7)))
        assertEquals(ShellV2.ID_STDERR, header.id)
        assertEquals(7, header.length)
    }

    @Test
    fun `the service string asks for raw framed output`() {
        assertEquals("shell,v2,raw:pm list packages", ShellV2.service("pm list packages"))
    }

    @Test
    fun `an implausible frame length is rejected rather than allocated`() {
        val bogus = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        val failure = runCatching { ShellV2.decodeHeader(bogus) }.exceptionOrNull()
        assertTrue(failure is com.codepanda.otg.adb.AdbServiceException)
    }

    @Test
    fun `exec fallback wraps the command with a status marker`() {
        assertEquals(
            "(pm uninstall x) 2>&1; echo __CP_EXIT__$?",
            ExecFallback.wrap("pm uninstall x"),
        )
    }

    @Test
    fun `exec fallback reads the exit code and strips the marker`() {
        val result = ExecFallback.parse(
            command = "pm uninstall com.x",
            output = "Failure [DELETE_FAILED_INTERNAL_ERROR]\r\n__CP_EXIT__1\r\n",
        )

        assertEquals(1, result.exitCode)
        assertFalse(result.isSuccess)
        assertEquals("Failure [DELETE_FAILED_INTERNAL_ERROR]", result.stdout)
        assertTrue(result.errorText.contains("DELETE_FAILED_INTERNAL_ERROR"))
    }

    @Test
    fun `exec fallback recognises success`() {
        val result = ExecFallback.parse("pm enable x", "Package x new state: enabled\n__CP_EXIT__0\n")

        assertTrue(result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals("", result.stderr)
    }

    @Test
    fun `a missing marker means the status is unknown, not success`() {
        val result = ExecFallback.parse("pm clear x", "some output with no marker")

        assertEquals(ShellResult.EXIT_UNKNOWN, result.exitCode)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `shell_v2 support is read from the device banner`() {
        val banner = "device::ro.product.name=sdk;ro.product.model=Pixel;" +
            "features=cmd,stat_v2,shell_v2,abb_exec"
        val features = AdbProtocol.parseFeatures(banner)

        assertTrue(features.contains(ShellV2.FEATURE))
        assertTrue(features.contains("abb_exec"))
        assertTrue(AdbProtocol.parseFeatures("device::ro.product.name=x").isEmpty())
        assertTrue(AdbProtocol.parseFeatures("").isEmpty())
    }

    @Test
    fun `a failure explains itself from stderr, falling back to stdout`() {
        val stderrOnly = ShellResult("pm", 1, "", "Permission denial")
        assertEquals("Permission denial", stderrOnly.errorText)

        val stdoutOnly = ShellResult("pm", 1, "Failure [X]", "")
        assertEquals("Failure [X]", stdoutOnly.errorText)

        val silent = ShellResult("pm", 7, "", "")
        assertEquals("command failed with exit code 7", silent.errorText)
    }
}
