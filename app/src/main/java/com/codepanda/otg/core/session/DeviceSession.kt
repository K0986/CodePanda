package com.codepanda.otg.core.session

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.feature.bloatware.BloatwareRepository
import com.codepanda.otg.feature.deviceinfo.DeviceInfoRepository
import com.codepanda.otg.feature.files.FileBrowser
import com.codepanda.otg.feature.files.FileRepository
import com.codepanda.otg.feature.mirror.ScreenCaptureRepository
import com.codepanda.otg.feature.packages.PackageRepository
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Everything derived from one live [AdbConnection]: the shell, the per-feature
 * repositories, the cached feature state, and the transfer queue.
 *
 * The session — not a screen — owns the work. A ViewModel that dies because the
 * user tapped another tab must not be able to cancel a listing, abandon a stream
 * or lose a download, so all of that hangs off [scope] and is serialised through
 * [ops]. Tearing the session down is the *only* thing that stops work, and it
 * stops all of it.
 */
class DeviceSession(
    val connection: AdbConnection,
    val deviceName: String,
) {
    /** Session lifetime scope: cancelled exactly once, in [close]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("adb-session"))

    val ops = AdbOps()
    val shell = AdbShell(connection)
    val transfers = TransferManager(scope)

    val packages = PackageRepository(connection, shell, ops)
    val files = FileRepository(connection, shell, ops)
    val deviceInfo = DeviceInfoRepository(shell, ops)
    val bloatware = BloatwareRepository(shell, ops)
    val capture = ScreenCaptureRepository(shell, ops)

    // ---- Cached, session-scoped feature state ------------------------------

    val packageStore = FeatureStore("packages", scope) { packages.listPackages() }
    val bloatwareStore = FeatureStore("system apps", scope) { bloatware.list() }
    val deviceInfoStore = FeatureStore("device info", scope) { deviceInfo.load() }
    val browser = FileBrowser(scope, files)

    @Volatile
    private var closed = false

    fun close(reason: String = "session closed") {
        if (closed) return
        closed = true
        AppLog.i(TAG, "Closing session for $deviceName: $reason")
        transfers.cancelAll(reason)
        runCatching { connection.close(reason) }
        scope.cancel(reason)
    }

    private companion object {
        const val TAG = "DeviceSession"
    }
}
