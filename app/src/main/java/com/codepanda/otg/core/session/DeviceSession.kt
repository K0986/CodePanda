package com.codepanda.otg.core.session

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.adb.service.AdbSyncClient
import com.codepanda.otg.feature.bloatware.BloatwareRepository
import com.codepanda.otg.feature.deviceinfo.DeviceInfoRepository
import com.codepanda.otg.feature.files.FileRepository
import com.codepanda.otg.feature.mirror.ScreenCaptureRepository
import com.codepanda.otg.feature.packages.PackageRepository

/**
 * Owns everything derived from one live [AdbConnection]: the shell, and the
 * per-feature repositories. Creating a session is cheap — the repositories are
 * lazy — but tearing it down closes the underlying USB connection.
 */
class DeviceSession(val connection: AdbConnection) {

    val shell: AdbShell = AdbShell(connection)

    /** Open a *fresh* sync channel. Callers own it and must close it. */
    fun openSync(): AdbSyncClient = AdbSyncClient(connection)

    val packages: PackageRepository by lazy { PackageRepository(connection, shell) }
    val files: FileRepository by lazy { FileRepository(connection, shell) }
    val deviceInfo: DeviceInfoRepository by lazy { DeviceInfoRepository(shell) }
    val bloatware: BloatwareRepository by lazy { BloatwareRepository(shell) }
    val capture: ScreenCaptureRepository by lazy { ScreenCaptureRepository(connection, shell) }

    fun close() {
        connection.close()
    }
}
