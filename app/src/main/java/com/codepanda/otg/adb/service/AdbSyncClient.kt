package com.codepanda.otg.adb.service

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.AdbServiceException
import com.codepanda.otg.adb.AdbStream
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.log.LogFormat
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
    val sizeBytes: Long get() = size.toLong() and 0xFFFFFFFFL
}

/** Progress of a transfer, reported often enough to drive a progress bar. */
data class SyncProgress(
    val transferred: Long,
    val total: Long,
    val elapsedMs: Long,
) {
    /** 0..1, or null when the total is unknown (a stream of unknown length). */
    val fraction: Float? get() = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else null
    val bytesPerSecond: Long get() = if (elapsedMs > 0) transferred * 1000 / elapsedMs else 0
}

/**
 * Implements the ADB **sync** sub-protocol used for file transfer. It rides on a
 * single `sync:` stream and speaks a compact request/response language of
 * four-byte tags (`LIST`, `STAT`, `RECV`, `SEND`, `DATA`, `DONE`, `OKAY`, `FAIL`).
 *
 * This is precisely the machinery behind `adb push` and `adb pull`.
 *
 * Instances are always created through [session], which ties the underlying
 * stream's lifetime to the block: an abandoned `sync:` stream blocks adbd for
 * every other feature, so it must never outlive its caller — not even when that
 * caller is cancelled because the user switched tabs.
 */
class AdbSyncClient private constructor(private val stream: AdbStream) {

    /** List the immediate children of directory [path]. */
    fun list(path: String): List<SyncDirEntry> {
        sendRequest(ID_LIST, path)
        val entries = mutableListOf<SyncDirEntry>()
        while (true) {
            when (val tag = readTag()) {
                ID_DENT -> {
                    val mode = readInt()
                    val size = readInt()
                    val time = readInt()
                    val nameLen = readInt()
                    if (nameLen < 0 || nameLen > MAX_NAME) {
                        throw AdbServiceException("implausible directory entry name length: $nameLen")
                    }
                    val name = String(stream.readExact(nameLen), Charsets.UTF_8)
                    entries += SyncDirEntry(name, mode, size, time)
                    if (entries.size > MAX_ENTRIES) {
                        AppLog.w(TAG, "LIST $path truncated at $MAX_ENTRIES entries")
                        break
                    }
                }
                ID_DONE -> {
                    stream.readExact(DONE_TRAILER) // mode/size/time trailer, ignored
                    break
                }
                ID_FAIL -> throw AdbServiceException("LIST $path failed: ${readFailMessage()}")
                else -> throw AdbServiceException("unexpected sync tag: $tag")
            }
        }
        AppLog.d(TAG, "LIST $path -> ${entries.size} entries")
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

    /**
     * Pull remote [path] into [out].
     *
     * [total] (usually from [stat]) lets callers show a real percentage instead
     * of an indeterminate spinner; pass 0 when it is genuinely unknown.
     */
    fun pull(
        path: String,
        out: OutputStream,
        total: Long = 0,
        onProgress: (SyncProgress) -> Unit = {},
    ): Long {
        val started = System.currentTimeMillis()
        AppLog.i(TAG, "RECV $path (${if (total > 0) LogFormat.bytes(total) else "size unknown"})")
        sendRequest(ID_RECV, path)
        var transferred = 0L
        var lastReport = 0L
        while (true) {
            when (val tag = readTag()) {
                ID_DATA -> {
                    val len = readInt()
                    if (len < 0 || len > MAX_CHUNK) {
                        throw AdbServiceException("implausible DATA length: $len")
                    }
                    var remaining = len
                    while (remaining > 0) {
                        val chunk = stream.read(remaining)
                            ?: throw AdbServiceException("stream ended mid-file at $transferred bytes")
                        out.write(chunk)
                        transferred += chunk.size
                        remaining -= chunk.size
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now
                        onProgress(SyncProgress(transferred, total, now - started))
                    }
                }
                ID_DONE -> {
                    stream.readExact(4) // mtime trailer
                    break
                }
                ID_FAIL -> throw AdbServiceException("RECV $path failed: ${readFailMessage()}")
                else -> throw AdbServiceException("unexpected sync tag: $tag")
            }
        }
        out.flush()
        val elapsed = System.currentTimeMillis() - started
        onProgress(SyncProgress(transferred, maxOf(total, transferred), elapsed))
        AppLog.i(
            TAG,
            "RECV $path done: ${LogFormat.bytes(transferred)} in ${elapsed}ms " +
                "(${LogFormat.bytes(if (elapsed > 0) transferred * 1000 / elapsed else 0)}/s)",
        )
        return transferred
    }

    /**
     * Push [input] to remote [remotePath]. [mode] is the octal st_mode used for
     * the created file (default `0100644` = regular file, rw-r--r--).
     */
    fun push(
        input: InputStream,
        remotePath: String,
        total: Long = 0,
        mode: Int = 0x81A4, // 0100644
        mtimeSeconds: Int = (System.currentTimeMillis() / 1000).toInt(),
        onProgress: (SyncProgress) -> Unit = {},
    ): Long {
        val started = System.currentTimeMillis()
        AppLog.i(TAG, "SEND $remotePath (${if (total > 0) LogFormat.bytes(total) else "size unknown"})")
        // SEND argument is "path,mode".
        sendRequest(ID_SEND, "$remotePath,$mode")
        val buffer = ByteArray(SYNC_DATA_MAX)
        var transferred = 0L
        var lastReport = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            sendDataChunk(buffer, read)
            transferred += read
            val now = System.currentTimeMillis()
            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                lastReport = now
                onProgress(SyncProgress(transferred, total, now - started))
            }
        }
        // DONE carries the modification time in the "length" slot.
        stream.write(tagWithLength(ID_DONE, mtimeSeconds))

        when (val tag = readTag()) {
            ID_OKAY -> readInt() // trailing length, ignored
            ID_FAIL -> throw AdbServiceException("SEND $remotePath failed: ${readFailMessage()}")
            else -> throw AdbServiceException("unexpected sync tag after SEND: $tag")
        }
        val elapsed = System.currentTimeMillis() - started
        onProgress(SyncProgress(transferred, maxOf(total, transferred), elapsed))
        AppLog.i(TAG, "SEND $remotePath done: ${LogFormat.bytes(transferred)} in ${elapsed}ms")
        return transferred
    }

    private fun quit() {
        runCatching {
            // "QUIT" gracefully ends the sync session.
            stream.write(tagWithLength(ID_QUIT, 0))
        }
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
        if (len < 0 || len > MAX_NAME) return "(unreadable error of $len bytes)"
        return String(stream.readExact(len), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "AdbSync"

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
        private const val MAX_NAME = 4096
        private const val MAX_CHUNK = 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val PROGRESS_INTERVAL_MS = 150L

        /**
         * Run [block] against a fresh `sync:` session, closing the stream on
         * every exit path (success, failure, or coroutine cancellation).
         */
        fun <T> session(connection: AdbConnection, block: (AdbSyncClient) -> T): T =
            connection.withStream("sync:") { stream ->
                val client = AdbSyncClient(stream)
                try {
                    block(client)
                } finally {
                    client.quit()
                }
            }
    }
}
