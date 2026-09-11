package com.codepanda.otg.adb.service

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.AdbServiceException
import com.codepanda.otg.adb.AdbStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A single directory entry returned by the sync LIST command. */
data class SyncDirEntry(
    val name: String,
    val mode: Int,
    val size: Int,
    val mtimeSeconds: Int,
) {
    val isDirectory: Boolean get() = mode and S_IFMT == S_IFDIR
    val isRegularFile: Boolean get() = mode and S_IFMT == S_IFREG
    val isSymlink: Boolean get() = mode and S_IFMT == S_IFLNK

    companion object {
        const val S_IFMT = 0x0000F000
        const val S_IFDIR = 0x00004000
        const val S_IFREG = 0x00008000
        const val S_IFLNK = 0x0000A000
    }
}

/** Result of the sync STAT command. `mode == 0` means "does not exist". */
data class SyncStat(val mode: Int, val size: Int, val mtimeSeconds: Int) {
    val exists: Boolean get() = mode != 0
    val isDirectory: Boolean get() = mode and SyncDirEntry.S_IFMT == SyncDirEntry.S_IFDIR
}

/**
 * Implements the ADB **sync** sub-protocol used for file transfer. It rides on a
 * single `sync:` stream and speaks a compact request/response language of
 * four-byte tags (`LIST`, `STAT`, `RECV`, `SEND`, `DATA`, `DONE`, `OKAY`, `FAIL`).
 *
 * This is precisely the machinery behind `adb push` and `adb pull`.
 */
class AdbSyncClient(connection: AdbConnection) : AutoCloseable {

    private val stream: AdbStream = connection.open("sync:")

    /** List the immediate children of directory [path]. */
    fun list(path: String): List<SyncDirEntry> {
        sendRequest(ID_LIST, path)
        val entries = mutableListOf<SyncDirEntry>()
        while (true) {
            val tag = readTag()
            when (tag) {
                ID_DENT -> {
                    val mode = readInt()
                    val size = readInt()
                    val time = readInt()
                    val nameLen = readInt()
                    val name = String(stream.readExact(nameLen), Charsets.UTF_8)
                    entries += SyncDirEntry(name, mode, size, time)
                }
                ID_DONE -> {
                    stream.readExact(DONE_TRAILER) // mode/size/time trailer, ignored
                    break
                }
                ID_FAIL -> throw AdbServiceException("LIST failed: ${readFailMessage()}")
                else -> throw AdbServiceException("unexpected sync tag: $tag")
            }
        }
        return entries
    }

    /** Stat a single path. Returns [SyncStat] with `exists=false` if missing. */
    fun stat(path: String): SyncStat {
        sendRequest(ID_STAT, path)
        val tag = readTag()
        if (tag != ID_STAT) throw AdbServiceException("unexpected sync tag for STAT: $tag")
        val mode = readInt()
        val size = readInt()
        val time = readInt()
        return SyncStat(mode, size, time)
    }

    /** Pull remote [path] into [out], reporting bytes transferred via [onProgress]. */
    fun pull(path: String, out: OutputStream, onProgress: (Long) -> Unit = {}) {
        sendRequest(ID_RECV, path)
        var total = 0L
        while (true) {
            when (val tag = readTag()) {
                ID_DATA -> {
                    val len = readInt()
                    val chunk = stream.readExact(len)
                    out.write(chunk)
                    total += len
                    onProgress(total)
                }
                ID_DONE -> {
                    stream.readExact(4) // mtime trailer
                    break
                }
                ID_FAIL -> throw AdbServiceException("RECV failed: ${readFailMessage()}")
                else -> throw AdbServiceException("unexpected sync tag: $tag")
            }
        }
        out.flush()
    }

    /**
     * Push [input] to remote [remotePath]. [mode] is the octal st_mode used for
     * the created file (default `0100644` = regular file, rw-r--r--).
     */
    fun push(
        input: InputStream,
        remotePath: String,
        mode: Int = 0x81A4, // 0100644
        mtimeSeconds: Int = (System.currentTimeMillis() / 1000).toInt(),
        onProgress: (Long) -> Unit = {},
    ) {
        // SEND argument is "path,mode".
        sendRequest(ID_SEND, "$remotePath,$mode")
        val buffer = ByteArray(SYNC_DATA_MAX)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            sendDataChunk(buffer, read)
            total += read
            onProgress(total)
        }
        // DONE carries the modification time in the "length" slot.
        stream.write(tagWithLength(ID_DONE, mtimeSeconds))

        when (val tag = readTag()) {
            ID_OKAY -> readInt() // trailing length, ignored
            ID_FAIL -> throw AdbServiceException("SEND failed: ${readFailMessage()}")
            else -> throw AdbServiceException("unexpected sync tag after SEND: $tag")
        }
    }

    override fun close() {
        runCatching {
            // "QUIT" gracefully ends the sync session.
            stream.write(tagWithLength(ID_QUIT, 0))
        }
        stream.close()
    }

    // ---- wire helpers ------------------------------------------------------

    private fun sendRequest(tag: String, arg: String) {
        val argBytes = arg.toByteArray(Charsets.UTF_8)
        val header = tagWithLength(tag, argBytes.size)
        stream.write(header + argBytes)
    }

    private fun sendDataChunk(data: ByteArray, length: Int) {
        stream.write(tagWithLength(ID_DATA, length))
        stream.write(if (length == data.size) data else data.copyOfRange(0, length))
    }

    private fun tagWithLength(tag: String, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(tag.toByteArray(Charsets.US_ASCII))
        buffer.putInt(length)
        return buffer.array()
    }

    private fun readTag(): String = String(stream.readExact(4), Charsets.US_ASCII)

    private fun readInt(): Int =
        ByteBuffer.wrap(stream.readExact(4)).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readFailMessage(): String {
        val len = readInt()
        return String(stream.readExact(len), Charsets.UTF_8)
    }

    companion object {
        private const val ID_LIST = "LIST"
        private const val ID_DENT = "DENT"
        private const val ID_STAT = "STAT"
        private const val ID_RECV = "RECV"
        private const val ID_SEND = "SEND"
        private const val ID_DATA = "DATA"
        private const val ID_DONE = "DONE"
        private const val ID_OKAY = "OKAY"
        private const val ID_FAIL = "FAIL"
        private const val ID_QUIT = "QUIT"

        private const val SYNC_DATA_MAX = 64 * 1024
        private const val DONE_TRAILER = 16
    }
}
