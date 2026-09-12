package com.codepanda.otg.feature.packages

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class PackagesViewModel : SessionViewModel() {

    /** The package list, cached in the session so tab switches are free. */
    val packages: StateFlow<Async<List<AppPackage>>> = SessionManager.session
        .flatMapLatest { it?.packageStore?.state ?: flowOf(Async.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Async.Idle)

    val transfers: StateFlow<List<Transfer>> = SessionManager.session
        .flatMapLatest { it?.transfers?.transfers ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var filter by mutableStateOf(PackageFilter.USER)
        private set
    var query by mutableStateOf("")
        private set

    /** Details shown in the inspector sheet, or null when closed. */
    var details by mutableStateOf<Async<AppPackageDetails>?>(null)
        private set

    init {
        viewModelScope.launch {
            SessionManager.session.collect { it?.packageStore?.ensureLoaded() }
        }
    }

    fun refresh() {
        session?.packageStore?.refresh()
    }

    fun updateFilter(value: PackageFilter) {
        filter = value
    }

    fun updateQuery(value: String) {
        query = value
    }

    fun filtered(): List<AppPackage> {
        val list = packages.value.dataOrNull ?: return emptyList()
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
        val active = session ?: run {
            message = "Not connected to a device"
            return
        }
        details = Async.Loading
        active.scope.launch {
            details = try {
                Async.Success(active.packages.getDetails(pkg.packageName))
            } catch (t: Throwable) {
                Async.Failure(t.message ?: "Failed to load details")
            }
        }
    }

    fun closeDetails() {
        details = null
    }

    // ---- actions -----------------------------------------------------------

    fun setEnabled(pkg: AppPackage, enabled: Boolean) =
        operate("${if (enabled) "Enable" else "Disable"} ${pkg.packageName}") { session ->
            val result = session.packages.setEnabled(pkg.packageName, enabled)
            if (result.isSuccess) {
                // Patch the cached row instead of re-reading 600 packages.
                session.packageStore.update { list ->
                    list.map { if (it.packageName == pkg.packageName) it.copy(enabled = enabled) else it }
                }
            }
            describe(if (enabled) "Enabling ${pkg.packageName}" else "Disabling ${pkg.packageName}", result)
        }

    fun forceStop(pkg: AppPackage) = operate("Force-stop ${pkg.packageName}") { session ->
        describe("Force-stopping ${pkg.packageName}", session.packages.forceStop(pkg.packageName))
    }

    fun clearData(pkg: AppPackage) = operate("Clear data of ${pkg.packageName}") { session ->
        describe("Clearing data of ${pkg.packageName}", session.packages.clearData(pkg.packageName))
    }

    fun uninstall(pkg: AppPackage) = operate("Uninstall ${pkg.packageName}") { session ->
        val result = session.packages.uninstall(pkg.packageName)
        if (result.isSuccess) session.packageStore.refresh()
        describe("Uninstalling ${pkg.packageName}", result)
    }

    /** Save the APK of [pkg] to this phone, with progress. */
    fun extractApk(context: Context, pkg: AppPackage) {
        val active = session ?: run {
            message = "Not connected to a device"
            return
        }
        val dir = File(context.getExternalFilesDir(null), "extracted-apks").apply { mkdirs() }
        val outFile = File(dir, "${pkg.packageName}.apk")

        active.transfers.start(
            label = "${pkg.packageName}.apk",
            direction = TransferDirection.DOWNLOAD,
            total = 0,
            destination = outFile.absolutePath,
        ) { report ->
            FileOutputStream(outFile).use { out ->
                active.packages.extractApk(pkg.packageName, out) { progress ->
                    report(progress.transferred, progress.total)
                }
            }
        }
        message = "Extracting ${pkg.packageName}…"
    }

    /** Side-load an APK picked on this phone, with progress. */
    fun install(context: Context, uri: Uri) {
        val active = session ?: run {
            message = "Not connected to a device"
            return
        }
        val size = querySize(context, uri)
        if (size <= 0) {
            message = "Could not determine the APK's size. Copy it to local storage first, then retry."
            return
        }
        val name = queryName(context, uri) ?: "app.apk"

        active.transfers.start(
            label = "Install $name",
            direction = TransferDirection.UPLOAD,
            total = size,
        ) { report ->
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                active.packages.installStreaming(input, size) { sent, total -> report(sent, total) }
            } ?: error("Could not open the selected file")

            if (!result.isSuccess) error(result.errorText)
            active.packageStore.refresh()
        }
        message = "Installing $name…"
    }

    private fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getLong(index)
        }
        return -1L
    }

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }
}
