package com.codepanda.otg.feature.bloatware

import com.codepanda.otg.adb.service.AdbShell
import com.codepanda.otg.adb.service.ShellResult
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.core.session.AdbOps

/**
 * Debloating without root. On modern Android you cannot truly *delete* a
 * preinstalled system app without root, but you can:
 *
 *  - **disable** it for the current user (`pm disable-user`), which stops it
 *    running and hides it, or
 *  - **uninstall it for user 0** (`pm uninstall --user 0`), which removes the
 *    per-user copy while keeping the factory image, so a factory reset (or
 *    `install-existing`) brings it back.
 *
 * Both are fully reversible, which is why this screen leans on them.
 */
class BloatwareRepository(
    private val shell: AdbShell,
    private val ops: AdbOps,
) {

    suspend fun list(): List<BloatApp> = ops.run("list system apps", timeoutMs = 90_000) {
        val installed = names(shell.exec("pm list packages -s"))
        val disabled = names(shell.exec("pm list packages -d"))
        // `-u` includes packages uninstalled for the user but still on the image.
        val allIncludingUninstalled = names(shell.exec("pm list packages -s -u"))
        val removedForUser = allIncludingUninstalled - installed

        AppLog.i(
            TAG,
            "system apps: ${installed.size} installed, ${disabled.size} disabled, " +
                "${removedForUser.size} removed for user 0",
        )

        (installed + removedForUser).distinct().sorted().map { pkg ->
            val known = CATALOG[pkg] ?: CATALOG.entries.firstOrNull { pkg.startsWith(it.key) }?.value
            BloatApp(
                packageName = pkg,
                enabled = pkg !in disabled && pkg !in removedForUser,
                removedForUser = pkg in removedForUser,
                knownName = known?.name,
                description = known?.description,
                safety = known?.safety ?: BloatSafety.UNKNOWN,
            )
        }
    }

    suspend fun disableForUser(pkg: String): ShellResult =
        ops.run("disable-user $pkg") { shell.run("pm disable-user --user 0 $pkg") }

    suspend fun enable(pkg: String): ShellResult =
        ops.run("enable $pkg") { shell.run("pm enable --user 0 $pkg") }

    suspend fun uninstallForUser(pkg: String): ShellResult =
        ops.run("uninstall --user 0 $pkg") { shell.run("pm uninstall -k --user 0 $pkg") }

    suspend fun reinstall(pkg: String): ShellResult =
        ops.run("install-existing $pkg") { shell.run("cmd package install-existing $pkg") }

    private fun names(output: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toSet()

    private data class Known(val name: String, val description: String, val safety: BloatSafety)

    companion object {
        private const val TAG = "BloatwareRepository"

        /**
         * A small, opinionated catalog of common bloatware. Prefix matches let a
         * whole vendor family (e.g. `com.facebook.`) be flagged at once.
         */
        private val CATALOG: Map<String, Known> = linkedMapOf(
            "com.facebook.appmanager" to Known("Facebook App Manager", "Silent Facebook updater preinstalled by OEMs.", BloatSafety.SAFE),
            "com.facebook.services" to Known("Facebook Services", "Background Facebook integration service.", BloatSafety.SAFE),
            "com.facebook.system" to Known("Facebook Installer", "Preinstalled Facebook stub.", BloatSafety.SAFE),
            "com.facebook.katana" to Known("Facebook", "Facebook social app.", BloatSafety.SAFE),
            "com.google.android.apps.tachyon" to Known("Google Meet (Duo)", "Video calling app.", BloatSafety.SAFE),
            "com.google.android.googlequicksearchbox" to Known("Google App", "Search + Assistant. Disabling removes 'Hey Google'.", BloatSafety.EXPERT),
            "com.google.android.apps.subscriptions.red" to Known("Google One", "Cloud storage subscription app.", BloatSafety.SAFE),
            "com.android.chrome" to Known("Chrome", "Default browser. Safe if you use another browser.", BloatSafety.EXPERT),
            "com.google.android.youtube" to Known("YouTube", "Video app.", BloatSafety.SAFE),
            "com.google.android.apps.docs" to Known("Google Drive", "Cloud storage app.", BloatSafety.SAFE),
            "com.google.android.gm" to Known("Gmail", "Email client.", BloatSafety.EXPERT),
            "com.samsung.android.bixby" to Known("Bixby", "Samsung voice assistant.", BloatSafety.SAFE),
            "com.samsung.android.game" to Known("Samsung Game Tools", "Game launcher/optimizer.", BloatSafety.SAFE),
            "com.miui.analytics" to Known("MIUI Analytics", "Xiaomi telemetry.", BloatSafety.SAFE),
            "com.miui.msa.global" to Known("MIUI System Ads (MSA)", "Xiaomi ad framework.", BloatSafety.SAFE),
            "com.amazon" to Known("Amazon (preinstalled)", "Preinstalled Amazon apps.", BloatSafety.SAFE),
            "com.netflix.partner" to Known("Netflix Installer", "Netflix preinstall stub.", BloatSafety.SAFE),
            "com.android.phone" to Known("Phone / Telephony", "Core telephony — do NOT remove.", BloatSafety.UNSAFE),
            "com.android.systemui" to Known("System UI", "Status bar & navigation — do NOT remove.", BloatSafety.UNSAFE),
            "com.google.android.gms" to Known("Google Play Services", "Core dependency for most apps — do NOT remove.", BloatSafety.UNSAFE),
            "com.android.vending" to Known("Google Play Store", "App store — removing breaks updates.", BloatSafety.UNSAFE),
            "com.android.settings" to Known("Settings", "The system Settings app — do NOT remove.", BloatSafety.UNSAFE),
            "com.android.providers." to Known("System content provider", "Backs settings, media, contacts and telephony storage — removing bricks the device.", BloatSafety.UNSAFE),
            "com.android.server.telecom" to Known("Telecom", "Call routing — removing breaks calling.", BloatSafety.UNSAFE),
            "com.android.permissioncontroller" to Known("Permission Controller", "Grants and revokes app permissions — do NOT remove.", BloatSafety.UNSAFE),
            "com.android.externalstorage" to Known("External Storage", "Provides access to shared storage — do NOT remove.", BloatSafety.UNSAFE),
            "com.google.android.inputmethod.latin" to Known("Gboard", "Default keyboard. Only remove if another keyboard is installed and selected.", BloatSafety.EXPERT),
        )
    }
}
