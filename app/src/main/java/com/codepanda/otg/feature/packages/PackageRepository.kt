package com.codepanda.otg.feature.packages

import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.adb.service.AdbSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Everything the app can do with the *packages* on the connected device: list
 * them, inspect them, toggle/force-stop/clear/uninstall them, pull their APKs,
 * and side-load new ones.
 *
 * All of this is expressed in terms of the `pm`/`cmd package`/`am` shell tools
 * that ship on every Android device — no root required for the common cases.
 */
class PackageRepository(
    private val connection: AdbConnection,
    private val shell: AdbShell,
) {

    suspend fun listPackages(): List<AppPackage> = withContext(Dispatchers.IO) {
        val pathByPkg = parsePackagePaths(shell.exec("pm list packages -f"))
        val systemPkgs = parsePackageNames(shell.exec("pm list packages -s"))
        val disabledPkgs = parsePackageNames(shell.exec("pm list packages -d"))

        pathByPkg.keys.sorted().map { pkg ->
            AppPackage(
                packageName = pkg,
                apkPath = pathByPkg[pkg],
                isSystem = pkg in systemPkgs,
                enabled = pkg !in disabledPkgs,
            )
        }
    }

    suspend fun getDetails(packageName: String): AppPackageDetails = withContext(Dispatchers.IO) {
        val dump = shell.exec("dumpsys package $packageName")
        val apkPaths = apkPathsFor(packageName)
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

    suspend fun setEnabled(packageName: String, enabled: Boolean): CommandResult =
        withContext(Dispatchers.IO) {
            val cmd = if (enabled) "pm enable $packageName"
            else "pm disable-user --user 0 $packageName"
            CommandResult.from(shell.exec(cmd))
        }

    suspend fun forceStop(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        // am force-stop is silent on success, so treat empty output as success.
        val out = shell.exec("am force-stop $packageName")
        if (out.isBlank()) CommandResult(true, "Force-stopped") else CommandResult.from(out)
    }

    suspend fun clearData(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        CommandResult.from(shell.exec("pm clear $packageName"))
    }

    suspend fun uninstall(packageName: String, keepData: Boolean = false): CommandResult =
        withContext(Dispatchers.IO) {
            val keep = if (keepData) "-k " else ""
            CommandResult.from(shell.exec("pm uninstall $keep$packageName"))
        }

    /** Remove a (bloatware) app for the current user without root. Reversible. */
    suspend fun uninstallForUser(packageName: String): CommandResult =
        withContext(Dispatchers.IO) {
            CommandResult.from(shell.exec("pm uninstall -k --user 0 $packageName"))
        }

    /** Re-install a previously user-uninstalled system app for user 0. */
    suspend fun reinstallExisting(packageName: String): CommandResult =
        withContext(Dispatchers.IO) {
            CommandResult.from(shell.exec("cmd package install-existing $packageName"))
        }

    suspend fun apkPathsFor(packageName: String): List<String> = withContext(Dispatchers.IO) {
        shell.exec("pm path $packageName")
            .lineSequence()
            .mapNotNull { it.trim().removePrefix("package:").ifBlank { null } }
            .toList()
    }

    /** Pull the base APK of [packageName] into [out]. */
    suspend fun extractApk(
        packageName: String,
        out: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val path = apkPathsFor(packageName).firstOrNull()
            ?: throw IllegalStateException("No APK path for $packageName")
        var total = 0L
        AdbSyncClient(connection).use { sync ->
            sync.pull(path, out) { total = it; onProgress(it) }
        }
        total
    }

    /**
     * Stream-install an APK read from [input] of known [size]. This mirrors
     * `adb install`: the bytes are piped straight into `cmd package install`'s
     * stdin, so nothing is staged on the device's storage first.
     */
    suspend fun installStreaming(
        input: InputStream,
        size: Long,
        onProgress: (Long) -> Unit = {},
    ): CommandResult = withContext(Dispatchers.IO) {
        val stream = connection.open("exec:cmd package install -r -S $size")
        try {
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                stream.write(if (read == buffer.size) buffer else buffer.copyOf(read))
                sent += read
                onProgress(sent)
            }
            CommandResult.from(stream.readAllText())
        } finally {
            stream.close()
        }
    }

    // ---- parsing helpers ---------------------------------------------------

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
}

/** Outcome of a `pm`/`am`-style command that reports Success/Failure textually. */
data class CommandResult(val success: Boolean, val message: String) {
    companion object {
        fun from(output: String): CommandResult {
            val trimmed = output.trim()
            val success = trimmed.contains("Success", ignoreCase = true) ||
                trimmed.isEmpty()
            return CommandResult(success, trimmed.ifBlank { "OK" })
        }
    }
}
