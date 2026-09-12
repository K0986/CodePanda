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
 *
 * ✨ ENHANCED: Exposes cache statistics for diagnostic logging.
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
    
    // ✨ NEW: Get cache statistics for diagnostics
    fun getCacheStats(): Pair<Int, Int>? = session?.browser?.getCacheStats()

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
        val newPath = joinPath(session.browser.path.value, newName)
        val result = session.files.rename(item.absolutePath, newPath)
        session.browser.invalidate()
        session.browser.refresh()
        describe("Renaming ${item.name} → $newName", result)
    }

    private fun describe(action: String, result: com.codepanda.otg.adb.service.ShellResult): String {
        return if (result.exitCode == 0) {
            "$action succeeded"
        } else {
            val stderr = result.stderr.trim()
            "$action failed: ${if (stderr.isNotEmpty()) stderr else "exit ${result.exitCode}"}"
        }
    }

    private fun joinPath(parent: String, child: String): String {
        return if (parent == "/") "/$child" else "$parent/$child"
    }

    private companion object {
        const val MAX_VISIBLE_TRANSFERS = 3
    }
}
