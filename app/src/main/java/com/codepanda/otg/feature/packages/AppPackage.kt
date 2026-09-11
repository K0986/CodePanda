package com.codepanda.otg.feature.packages

/** A package installed on the connected device, as seen from `pm list packages`. */
data class AppPackage(
    val packageName: String,
    val apkPath: String?,
    val isSystem: Boolean,
    val enabled: Boolean,
) {
    /** Best-effort friendly name when no label is available over ADB. */
    val displayName: String
        get() = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

/** Rich details for a single package, parsed from `dumpsys package`. */
data class AppPackageDetails(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val installerPackage: String?,
    val firstInstall: String?,
    val lastUpdate: String?,
    val dataDir: String?,
    val apkPaths: List<String>,
    val requestedPermissions: List<String>,
)

/** Filter applied to the package list in the UI. */
enum class PackageFilter(val label: String) {
    ALL("All"),
    USER("User"),
    SYSTEM("System"),
    DISABLED("Disabled"),
}
