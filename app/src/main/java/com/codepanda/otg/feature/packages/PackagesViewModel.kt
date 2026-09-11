package com.codepanda.otg.feature.packages

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

class PackagesViewModel : ViewModel() {

    var state by mutableStateOf<Async<List<AppPackage>>>(Async.Loading)
        private set
    var filter by mutableStateOf(PackageFilter.USER)
        private set
    var query by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    /** Details shown in the inspector sheet, or null when closed. */
    var details by mutableStateOf<Async<AppPackageDetails>?>(null)
        private set

    private val repo get() = SessionManager.session?.packages

    init {
        load()
    }

    fun load() {
        val repo = repo ?: run { state = Async.Failure("Not connected"); return }
        viewModelScope.launch {
            state = Async.Loading
            state = try {
                Async.Success(repo.listPackages())
            } catch (t: Throwable) {
                Async.Failure(t.message ?: "Failed to list packages")
            }
        }
    }

    fun updateFilter(value: PackageFilter) { filter = value }
    fun updateQuery(value: String) { query = value }
    fun consumeMessage() { message = null }

    fun filtered(): List<AppPackage> {
        val list = (state as? Async.Success)?.data ?: return emptyList()
        return list.filter { pkg ->
            val matchesFilter = when (filter) {
                PackageFilter.ALL -> true
                PackageFilter.USER -> !pkg.isSystem
                PackageFilter.SYSTEM -> pkg.isSystem
                PackageFilter.DISABLED -> !pkg.enabled
            }
            val matchesQuery = query.isBlank() ||
                pkg.packageName.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    fun openDetails(pkg: AppPackage) {
        val repo = repo ?: return
        details = Async.Loading
        viewModelScope.launch {
            details = try {
                Async.Success(repo.getDetails(pkg.packageName))
            } catch (t: Throwable) {
                Async.Failure(t.message ?: "Failed to load details")
            }
        }
    }

    fun closeDetails() { details = null }

    // ---- actions -----------------------------------------------------------

    fun setEnabled(pkg: AppPackage, enabled: Boolean) = runAction {
        repo?.setEnabled(pkg.packageName, enabled)?.message ?: "No session"
    }

    fun forceStop(pkg: AppPackage) = runAction {
        repo?.forceStop(pkg.packageName)?.message ?: "No session"
    }

    fun clearData(pkg: AppPackage) = runAction {
        repo?.clearData(pkg.packageName)?.message ?: "No session"
    }

    fun uninstall(pkg: AppPackage) = runAction {
        repo?.uninstall(pkg.packageName)?.message ?: "No session"
    }

    fun extractApk(context: Context, pkg: AppPackage) = runAction {
        val repo = repo ?: return@runAction "No session"
        val dir = File(context.getExternalFilesDir(null), "extracted-apks").apply { mkdirs() }
        val outFile = File(dir, "${pkg.packageName}.apk")
        FileOutputStream(outFile).use { repo.extractApk(pkg.packageName, it) }
        "Saved APK to ${outFile.absolutePath}"
    }

    fun install(context: Context, uri: Uri) = runAction {
        val repo = repo ?: return@runAction "No session"
        val resolver = context.contentResolver
        val size = querySize(context, uri)
        // -S streaming needs an exact length; without it the install would hang.
        if (size <= 0) {
            return@runAction "Could not determine the APK's size. Save it to local storage first, then retry."
        }
        resolver.openInputStream(uri)?.use { input ->
            repo.installStreaming(input, size).message
        } ?: "Could not open selected file"
    }

    private fun runAction(block: suspend () -> String) {
        viewModelScope.launch {
            busy = true
            message = try {
                block()
            } catch (t: Throwable) {
                t.message ?: "Operation failed"
            } finally {
                busy = false
            }
            load()
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getLong(index)
        }
        return -1L
    }
}
