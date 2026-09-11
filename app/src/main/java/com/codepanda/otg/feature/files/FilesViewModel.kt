package com.codepanda.otg.feature.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.ui.Async
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class FilesViewModel : ViewModel() {

    var currentPath by mutableStateOf(START_PATH)
        private set
    var state by mutableStateOf<Async<List<FileItem>>>(Async.Loading)
        private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    private val repo get() = SessionManager.session?.files

    init {
        open(START_PATH)
    }

    fun open(path: String) {
        val repo = repo ?: run { state = Async.Failure("Not connected"); return }
        currentPath = path
        viewModelScope.launch {
            state = Async.Loading
            state = try {
                Async.Success(repo.list(path))
            } catch (t: Throwable) {
                Async.Failure(t.message ?: "Failed to list $path")
            }
        }
    }

    fun refresh() = open(currentPath)

    fun navigateInto(item: FileItem) {
        if (item.isDirectory || item.isSymlink) open(item.absolutePath)
    }

    fun goUp() {
        if (currentPath == "/") return
        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
        open(if (parent.isEmpty()) "/" else parent)
    }

    fun consumeMessage() { message = null }

    fun delete(item: FileItem) = runAction {
        // rm/mkdir/mv are silent on success, so any output is the device's error.
        val output = repo?.delete(item.absolutePath, recursive = item.isDirectory)
        if (output.isNullOrBlank()) "Deleted ${item.name}" else output
    }

    fun makeDirectory(name: String) = runAction {
        val path = joinPath(currentPath, name)
        val output = repo?.makeDirectory(path)
        if (output.isNullOrBlank()) "Created $name" else output
    }

    fun rename(item: FileItem, newName: String) = runAction {
        val target = joinPath(currentPath, newName)
        val output = repo?.rename(item.absolutePath, target)
        if (output.isNullOrBlank()) "Renamed to $newName" else output
    }

    fun pull(context: Context, item: FileItem) = runAction {
        val repo = repo ?: return@runAction "No session"
        val dir = File(context.getExternalFilesDir(null), "pulled").apply { mkdirs() }
        val outFile = File(dir, item.name)
        FileOutputStream(outFile).use { repo.pull(item.absolutePath, it) }
        "Pulled to ${outFile.absolutePath}"
    }

    fun push(context: Context, uri: Uri) = runAction {
        val repo = repo ?: return@runAction "No session"
        val name = queryName(context, uri) ?: "upload.bin"
        val remotePath = joinPath(currentPath, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            repo.push(input, remotePath)
        } ?: return@runAction "Could not open selected file"
        "Pushed $name to $currentPath"
    }

    private fun runAction(block: suspend () -> String?) {
        viewModelScope.launch {
            busy = true
            message = try {
                block() ?: "No session"
            } catch (t: Throwable) {
                t.message ?: "Operation failed"
            } finally {
                busy = false
            }
            refresh()
        }
    }

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    companion object {
        private const val START_PATH = "/sdcard"
    }
}
