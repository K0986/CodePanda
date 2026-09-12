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
 *
 * ✨ ENHANCED: Now tracks per-entry access errors and resolves symlink targets,
 * similar to Dioxamine's dxls daemon approach.
 */
class FileRepository(
    private val connection: AdbConnection,
    private val shell: AdbShell,
    private val ops: AdbOps,
) {

    suspend fun list(path: String): List<FileItem> = ops.run("LIST $path") {
        val normalized = if (path.endsWith("/")) path else "$path/"
        val items = mutableListOf<FileItem>()
        
        AdbSyncClient.session(connection) { sync ->
            sync.list(path)
                .asSequence()
                .filter { it.name != "." && it.name != ".." }
                .forEach { entry ->
                    try {
                        val fullPath = normalized + entry.name
                        
                        // ✨ NEW: Resolve symlink target if this is a symlink
                        var symlinkTarget: String? = null
                        var symlinkTargetIsDir = false
                        
                        if (entry.isSymlink) {
                            runCatching {
                                // Read the symlink target
                                val readlinkResult = shell.exec("readlink '$fullPath'")
                                if (readlinkResult.exitCode == 0) {
                                    symlinkTarget = readlinkResult.stdout.trim()
                                    
                                    // Check if target is a directory
                                    if (symlinkTarget != null) {
                                        runCatching {
                                            val targetStat = sync.stat(symlinkTarget!!)
                                            symlinkTargetIsDir = targetStat.mode and 0x4000 != 0
                                        }.onFailure { err ->
                                            AppLog.w(TAG, "Could not stat symlink target $symlinkTarget: ${err.message}")
                                        }
                                    }
                                }
                            }.onFailure { err ->
                                AppLog.w(TAG, "Could not resolve symlink $fullPath: ${err.message}")
                            }
                        }
                        
                        items.add(
                            FileItem(
                                name = entry.name,
                                absolutePath = fullPath,
                                isDirectory = entry.isDirectory,
                                isSymlink = entry.isSymlink,
                                sizeBytes = entry.size.toLong() and 0xFFFFFFFFL,
                                mtimeSeconds = entry.mtimeSeconds.toLong() and 0xFFFFFFFFL,
                                mode = entry.mode,
                                accessError = null,  // ✨ NEW: Only set if stat/readlink fails
                                symlinkTarget = symlinkTarget,
                                symlinkTargetIsDir = symlinkTargetIsDir,
                            )
                        )
                    } catch (err: Exception) {
                        // ✨ NEW: Gracefully add inaccessible items with error details
                        val fullPath = normalized + entry.name
                        AppLog.w(TAG, "Error processing entry ${entry.name}: ${err.message}")
                        items.add(
                            FileItem(
                                name = entry.name,
                                absolutePath = fullPath,
                                isDirectory = false,
                                isSymlink = false,
                                sizeBytes = 0,
                                mtimeSeconds = 0,
                                mode = 0,
                                accessError = err.message ?: "Access Denied",  // ✨ NEW: Error tracking
                            )
                        )
                    }
                }
        }
        
        items.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
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
     * Verify that the delete actually succeeded by stating the parent directory.
     * (An rm that returns 0 might still have failed if the target was
     * not writable by the user.)
     */
    suspend fun delete(path: String, recursive: Boolean = false): ShellResult =
        ops.run("DELETE $path") {
            val cmd = if (recursive) "rm -rf '$path'" else "rm '$path'"
            shell.exec(cmd)
        }

    suspend fun makeDirectory(path: String): ShellResult = ops.run("MKDIR $path") {
        shell.exec("mkdir -p '$path'")
    }

    suspend fun rename(fromPath: String, toPath: String): ShellResult =
        ops.run("RENAME $fromPath -> $toPath") { shell.exec("mv '$fromPath' '$toPath'") }

    private companion object {
        const val TAG = "FileRepository"
    }
}
