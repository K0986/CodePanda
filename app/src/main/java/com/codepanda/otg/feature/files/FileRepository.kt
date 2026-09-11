package com.codepanda.otg.feature.files

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.adb.service.AdbSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * A remote file manager. Directory listings and file transfers go through the
 * fast binary **sync** protocol; structural operations (delete, rename, mkdir)
 * are delegated to ordinary shell tools.
 */
class FileRepository(
    private val connection: AdbConnection,
    private val shell: AdbShell,
) {

    suspend fun list(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        val normalized = if (path.endsWith("/")) path else "$path/"
        AdbSyncClient(connection).use { sync ->
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
                .sortedWith(compareByDescending<FileItem> { it.isDirectory }
                    .thenBy { it.name.lowercase() })
                .toList()
        }
    }

    suspend fun pull(
        remotePath: String,
        out: OutputStream,
        onProgress: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        AdbSyncClient(connection).use { it.pull(remotePath, out, onProgress) }
    }

    suspend fun push(
        input: InputStream,
        remotePath: String,
        onProgress: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        AdbSyncClient(connection).use { it.push(input, remotePath, onProgress = onProgress) }
    }

    suspend fun delete(path: String, recursive: Boolean): String = withContext(Dispatchers.IO) {
        val flag = if (recursive) "-rf" else "-f"
        shell.exec("rm $flag ${quote(path)}").trim()
    }

    suspend fun makeDirectory(path: String): String = withContext(Dispatchers.IO) {
        shell.exec("mkdir -p ${quote(path)}").trim()
    }

    suspend fun rename(from: String, to: String): String = withContext(Dispatchers.IO) {
        shell.exec("mv ${quote(from)} ${quote(to)}").trim()
    }

    suspend fun copy(from: String, to: String): String = withContext(Dispatchers.IO) {
        shell.exec("cp -r ${quote(from)} ${quote(to)}").trim()
    }

    suspend fun readTextPreview(path: String, maxBytes: Int = 64 * 1024): String =
        withContext(Dispatchers.IO) {
            shell.exec("head -c $maxBytes ${quote(path)}")
        }

    /** Wrap a path in single quotes, escaping any embedded single quotes. */
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
