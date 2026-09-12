package com.codepanda.otg.feature.packages

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.adb.service.AdbSyncClient
import com.codepanda.otg.adb.service.ExecFallback
import com.codepanda.otg.adb.service.ShellResult
import com.codepanda.otg.adb.service.SyncProgress
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.session.AdbOps
import java.io.InputStream
import java.io.OutputStream

/**
 * Everything the app can do with the *packages* on the connected device: list
 * them, inspect them, toggle/force-stop/clear/uninstall them, pull their APKs,
 * and side-load new ones.
 *
 * All of this is expressed in terms of the `pm`/`cmd package`/`am` shell tools
 * that ship on every Android device — no root required for the common cases.
 *
 * Every mutating call returns the device's real [ShellResult]. That is the fix
 * for "app operations don't work": `pm` reports its refusals on **stderr** with
 * a non-zero exit status, and the old `exec:`-only path threw both away, so a
 * failed uninstall was indistinguishable from a successful one.
 */
class PackageRepository(
    private val connection: AdbConnection,
    private val shell: AdbShell,
    private val ops: AdbOps,
) {

    suspend fun listPackages(): List<AppPackage> = ops.run("list packages", timeoutMs = 90_000) {
        val pathByPkg = parsePackagePaths(shell.exec("pm list packages -f"))
        val systemPkgs = parsePackageNames(shell.exec("pm list packages -s"))
        val disabledPkgs = parsePackageNames(shell.exec("pm list packages -d"))

        if (pathByPkg.isEmpty()) {
            AppLog.w(TAG, "pm list packages returned nothing; is this device really adbd-enabled?")
        }

        pathByPkg.keys.sorted().map { pkg ->
            AppPackage(
                packageName = pkg,
                apkPath = pathByPkg[pkg],
                isSystem = pkg in systemPkgs,
                enabled = pkg !in disabledPkgs,
            )
        }
    }

    suspend fun getDetails(packageName: String): AppPackageDetails =
        ops.run("details $packageName") {
            val dump = shell.exec("dumpsys package $packageName")
            val apkPaths = parseApkPaths(shell.exec("pm path $packageName"))
            AppPackageDetails(
                packageName = packageName,
                versionName = firstMatch(dump, Regex("""versionName=(\S+)""")),
                versionCode = firstMatch(dump, Regex("""versionCode=(\d+)""")),
                minSdk = firstMatch(dump, Regex("""minSdk=(\d+)""")),
                targetSdk = firstMatch(dump, Regex("""targetSdk=(\d+)""")),
                installerPackage = firstMatch(dump, Regex("""installerPackageName=(\S+)""")),
                firstInstall = firstMatch(dump, Regex("""firstInstallTime=(.+)""")),
                lastUpdate = firstMatch(dump, Regex("""lastUpdateTime=(.+)""")),
                dataDir = firstMatch(dump, Regex("""dataDir=(\S+)""")),
                apkPaths = apkPaths,
                requestedPermissions = parseRequestedPermissions(dump),
            )
        }

    suspend fun setEnabled(packageName: String, enabled: Boolean): ShellResult =
        ops.run("${if (enabled) "enable" else "disable"} $packageName") {
            val command = if (enabled) {
                "pm enable --user 0 $packageName"
            } else {
                "pm disable-user --user 0 $packageName"
            }
            shell.run(command)
        }

    suspend fun forceStop(packageName: String): ShellResult =
        ops.run("force-stop $packageName") { shell.run("am force-stop $packageName") }

    suspend fun clearData(packageName: String): ShellResult =
        ops.run("clear $packageName") { shell.run("pm clear $packageName") }

    suspend fun uninstall(packageName: String, keepData: Boolean = false): ShellResult =
        ops.run("uninstall $packageName") {
            val keep = if (keepData) "-k " else ""
            shell.run("pm uninstall $keep$packageName")
        }

    /** Remove a (bloatware) app for the current user without root. Reversible. */
    suspend fun uninstallForUser(packageName: String): ShellResult =
        ops.run("uninstall --user 0 $packageName") {
            shell.run("pm uninstall -k --user 0 $packageName")
        }

    /** Re-install a previously user-uninstalled system app for user 0. */
    suspend fun reinstallExisting(packageName: String): ShellResult =
        ops.run("install-existing $packageName") {
            shell.run("cmd package install-existing $packageName")
        }

    suspend fun apkPathsFor(packageName: String): List<String> =
        ops.run("pm path $packageName") { parseApkPaths(shell.exec("pm path $packageName")) }

    /** Size of the base APK, so an extraction can show real progress. */
    suspend fun apkSize(packageName: String): Long {
        val path = apkPathsFor(packageName).firstOrNull() ?: return 0
        return ops.run("stat $path") {
            AdbSyncClient.session(connection) { it.stat(path).sizeBytes }
        }
    }

    /** Pull the base APK of [packageName] into [out]. */
    suspend fun extractApk(
        packageName: String,
        out: OutputStream,
        onProgress: (SyncProgress) -> Unit = {},
    ): Long {
        val path = apkPathsFor(packageName).firstOrNull()
            ?: throw IllegalStateException("No APK path for $packageName")
        val total = ops.run("stat $path") {
            AdbSyncClient.session(connection) { it.stat(path).sizeBytes }
        }
        return ops.run("extract $packageName", timeoutMs = AdbOps.TRANSFER_TIMEOUT_MS) {
            AdbSyncClient.session(connection) { sync -> sync.pull(path, out, total, onProgress) }
        }
    }

    /**
     * Stream-install an APK read from [input] of known [size]. This mirrors
     * `adb install`: the bytes are piped straight into `cmd package install`'s
     * stdin, so nothing is staged on the device's storage first.
     *
     * [size] must be the exact byte count — `cmd package install -S` reads
     * precisely that many bytes and would otherwise hang waiting for more.
     */
    suspend fun installStreaming(
        input: InputStream,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): ShellResult {
        require(size > 0) { "APK size must be known to stream-install (got $size)" }
        val base = "cmd package install -r -S $size"
        val command = if (shell.supportsShellV2) base else ExecFallback.wrap(base)

        return ops.run("install ($size bytes)", timeoutMs = AdbOps.TRANSFER_TIMEOUT_MS) {
            shell.openStreaming(command).use { running ->
                val buffer = ByteArray(CHUNK)
                var sent = 0L
                while (sent < size) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    running.writeStdin(buffer, read)
                    sent += read
                    onProgress(sent, size)
                }
                running.closeStdin()

                if (sent != size) {
                    return@run ShellResult(
                        command = base,
                        exitCode = 1,
                        stdout = "",
                        stderr = "Aborted: expected $size bytes but the source provided $sent",
                    )
                }

                val output = running.drainStdoutText()
                if (shell.supportsShellV2) {
                    ShellResult(
                        command = base,
                        exitCode = running.exitCode ?: ShellResult.EXIT_UNKNOWN,
                        stdout = output,
                        stderr = running.stderrText,
                    )
                } else {
                    // On the fallback path the status arrives as a trailing marker.
                    ExecFallback.parse(base, output).let { parsed ->
                        if (parsed.exitCode == ShellResult.EXIT_UNKNOWN &&
                            parsed.stdout.contains("Success", ignoreCase = true)
                        ) {
                            parsed.copy(exitCode = 0)
                        } else {
                            parsed
                        }
                    }
                }
            }
        }
    }

    // ---- parsing helpers ---------------------------------------------------

    private fun parseApkPaths(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()

    private fun parsePackagePaths(output: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("package:")) return@forEach
            val body = line.removePrefix("package:")
            val eq = body.lastIndexOf('=')
            if (eq > 0) {
                val path = body.substring(0, eq)
                val pkg = body.substring(eq + 1)
                map[pkg] = path
            }
        }
        return map
    }

    private fun parsePackageNames(output: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toSet()

    private fun parseRequestedPermissions(dump: String): List<String> {
        val section = dump.substringAfter("requested permissions:", "")
        if (section.isBlank()) return emptyList()
        return section.lineSequence()
            .map { it.trim() }
            .takeWhile { it.startsWith("android.") || it.contains('.') && !it.endsWith(":") }
            .filter { it.startsWith("android.") || it.contains("permission") }
            .distinct()
            .take(200)
            .toList()
    }

    private fun firstMatch(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)?.trim()

    private companion object {
        const val TAG = "PackageRepository"
        const val CHUNK = 64 * 1024
    }
}
