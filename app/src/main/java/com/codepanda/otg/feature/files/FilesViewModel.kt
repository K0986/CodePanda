package com.codepanda.otg.feature.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.core.session.Transfer
import com.codepanda.otg.core.session.TransferDirection
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.SessionViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * The file manager screen's ViewModel.
 *
 * Deliberately thin: the current directory, its listing and the directory cache
 * all live in the session's [FileBrowser], so navigating away and back shows the
 * same folder instantly instead of re-listing it — and a listing in flight is
 * never cancelled by that navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModel : SessionViewModel() {

    val currentPath: StateFlow<String> = SessionManager.session
        .flatMapLatest { it?.browser?.path ?: flowOf(FileBrowser.START_PATH) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FileBrowser.START_PATH)

    val listing: StateFlow<Async<List<FileItem>>> = SessionManager.session
        .flatMapLatest { it?.browser?.listing ?: flowOf(Async.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Async.Idle)

    /** Transfers that concern files (downloads and uploads), newest first. */
    val transfers: StateFlow<List<Transfer>> = SessionManager.session
        .flatMapLatest { it?.transfers?.transfers ?: flowOf(emptyList()) }
        .map { list -> list.take(MAX_VISIBLE_TRANSFERS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Load the starting directory as soon as a device appears, once.
        viewModelScope.launch {
            SessionManager.session.collect { session ->
                session?.browser?.open(session.browser.path.value)
            }
        }
    }

    fun open(path: String) = session?.browser?.open(path) ?: Unit

    fun refresh() {
        session?.browser?.refresh()
    }

    fun navigateInto(item: FileItem) {
        if (item.isDirectory || item.isSymlink) open(item.absolutePath)
    }

    fun goUp() {
        session?.browser?.goUp()
    }

    fun cancelTransfer(id: Long) {
        session?.transfers?.cancel(id)
    }

    fun dismissTransfer(id: Long) {
        session?.transfers?.dismiss(id)
    }

    // ---- operations --------------------------------------------------------

    fun delete(item: FileItem) = operate("Delete ${item.name}") { session ->
        val result = session.files.delete(item.absolutePath, recursive = item.isDirectory)
        session.browser.invalidate()
        session.browser.refresh()
        describe("Deleting ${item.name}", result)
    }

    fun makeDirectory(name: String) = operate("Create folder") { session ->
        val path = joinPath(session.browser.path.value, name)
        val result = session.files.makeDirectory(path)
        session.browser.invalidate()
        session.browser.refresh()
        describe("Creating $name", result)
    }

    fun rename(item: FileItem, newName: String) = operate("Rename ${item.name}") { session ->
        val target = joinPath(session.browser.path.value, newName)
        val result = session.files.rename(item.absolutePath, target)
        session.browser.invalidate()
        session.browser.refresh()
        describe("Renaming to $newName", result)
    }

    /**
     * Download a file from the device.
     *
     * Registered with the session's transfer manager, which is what gives it a
     * real progress bar, a cancel button, a log entry, and immunity to the user
     * switching tabs while a large file is in flight.
     */
    fun download(context: Context, item: FileItem) {
        val active = session ?: run {
            message = "Not connected to a device"
            return
        }
        val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val outFile = File(dir, item.name)

        active.transfers.start(
            label = item.name,
            direction = TransferDirection.DOWNLOAD,
            total = item.sizeBytes,
            destination = outFile.absolutePath,
        ) { report ->
            FileOutputStream(outFile).use { out ->
                active.files.pull(item.absolutePath, out) { progress ->
                    report(progress.transferred, progress.total)
                }
            }
        }
        message = "Downloading ${item.name}…"
        AppLog.i(TAG, "download queued: ${item.absolutePath} -> ${outFile.absolutePath}")
    }

    /** Upload a document from this phone into the current directory. */
    fun upload(context: Context, uri: Uri) {
        val active = session ?: run {
            message = "Not connected to a device"
            return
        }
        val name = queryName(context, uri) ?: "upload.bin"
        val size = querySize(context, uri)
        val remotePath = joinPath(active.browser.path.value, name)

        active.transfers.start(
            label = name,
            direction = TransferDirection.UPLOAD,
            total = size,
            destination = remotePath,
        ) { report ->
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected file")
            input.use {
                active.files.push(it, remotePath, size) { progress ->
                    report(progress.transferred, progress.total)
                }
            }
            active.browser.invalidate()
            active.browser.refresh()
        }
        message = "Uploading $name…"
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

    private fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getLong(index)
        }
        return 0
    }

    private companion object {
        const val TAG = "FilesViewModel"
        const val MAX_VISIBLE_TRANSFERS = 4
    }
}
