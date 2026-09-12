package com.codepanda.otg.feature.files

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.AdbServiceException
import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.adb.service.AdbSyncClient
import com.codepanda.otg.adb.service.ShellResult
import com.codepanda.otg.adb.service.SyncProgress
import com.codepanda.otg.adb.service.SyncStat
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.session.AdbOps
import java.io.InputStream
import java.io.OutputStream

/**
 * A remote file manager. Directory listings and file transfers go through the
 * fast binary **sync** protocol; structural operations (delete, rename, mkdir)
 * are delegated to ordinary shell tools — but now with real exit codes, and
 * verified afterwards, because `rm` on Android happily returns 0 for a path it
 * was never allowed to touch.
 */
class FileRepository(
    private val connection: AdbConnection,
    private val shell: AdbShell,
    private val ops: AdbOps,
) {

    suspend fun list(path: String): List<FileItem> = ops.run("LIST $path") {
        val normalized = if (path.endsWith("/")) path else "$path/"
        AdbSyncClient.session(connection) { sync ->
            sync.list(path)
                .asSequence()
                .filter { it.name != "." && it.name != ".." }
                .map { entry ->
                    FileItem(
                        name = entry.name,
                        absolutePath = normalized + entry.name,
                        isDirectory = entry.isDirectory,
                        isSymlink = entry.isSymlink,
                        sizeBytes = entry.size.toLong() and 0xFFFFFFFFL,
                        mtimeSeconds = entry.mtimeSeconds.toLong() and 0xFFFFFFFFL,
                        mode = entry.mode,
                    )
                }
                .sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }
                        .thenBy { it.name.lowercase() },
                )
                .toList()
        }
    }

    suspend fun stat(path: String): SyncStat = ops.run("STAT $path") {
        AdbSyncClient.session(connection) { it.stat(path) }
    }

    /**
     * Download [remotePath] into [out].
     *
     * The size is stat'ed first so the UI can show a real percentage instead of
     * an indeterminate spinner, and the transfer runs under the long transfer
     * deadline rather than the default operation timeout.
     */
    suspend fun pull(
        remotePath: String,
        out: OutputStream,
        onProgress: (SyncProgress) -> Unit = {},
    ): Long {
        val total = runCatching { stat(remotePath) }.getOrNull()?.sizeBytes ?: 0L
        return ops.run("PULL $remotePath", timeoutMs = AdbOps.TRANSFER_TIMEOUT_MS) {
            AdbSyncClient.session(connection) { sync ->
                sync.pull(remotePath, out, total, onProgress)
            }
        }
    }

    suspend fun push(
        input: InputStream,
        remotePath: String,
        total: Long = 0,
        onProgress: (SyncProgress) -> Unit = {},
    ): Long = ops.run("PUSH $remotePath", timeoutMs = AdbOps.TRANSFER_TIMEOUT_MS) {
        AdbSyncClient.session(connection) { sync ->
            sync.push(input, remotePath, total, onProgress = onProgress)
        }
    }

    /**
     * Delete [path], then prove it is gone.
     *
     * `rm` is the classic silent failure on Android: on a path under another
     * app's data directory it can exit 0 having done nothing, and the old code
     * reported that as "Deleted". Now the result is verified with a `stat`.
     */
    suspend fun delete(path: String, recursive: Boolean): ShellResult {
        val flag = if (recursive) "-rf" else "-f"
        val result = ops.run("DELETE $path") { shell.run("rm $flag ${quote(path)}") }
        val stillThere = runCatching { stat(path).exists }.getOrDefault(false)
        return when {
            !stillThere -> result.copy(exitCode = 0)
            result.isSuccess -> {
                AppLog.w(TAG, "rm reported success but $path still exists")
                result.copy(
                    exitCode = 1,
                    stderr = "The device refused to delete this path (it still exists). " +
                        "It is most likely read-only or owned by another app.",
                )
            }
            else -> result
        }
    }

    suspend fun makeDirectory(path: String): ShellResult =
        ops.run("MKDIR $path") { shell.run("mkdir -p ${quote(path)}") }

    suspend fun rename(from: String, to: String): ShellResult =
        ops.run("MV $from -> $to") { shell.run("mv ${quote(from)} ${quote(to)}") }

    suspend fun copy(from: String, to: String): ShellResult =
        ops.run("CP $from -> $to") { shell.run("cp -r ${quote(from)} ${quote(to)}") }

    suspend fun readTextPreview(path: String, maxBytes: Int = 64 * 1024): String =
        ops.run("HEAD $path") {
            val result = shell.runBinary("head -c $maxBytes ${quote(path)}")
            if (!result.isSuccess && result.stdout.isEmpty()) {
                throw AdbServiceException(result.stderr.ifBlank { "could not read $path" })
            }
            String(result.stdout, Charsets.UTF_8)
        }

    /** Wrap a path in single quotes, escaping any embedded single quotes. */
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "FileRepository"
    }
}
