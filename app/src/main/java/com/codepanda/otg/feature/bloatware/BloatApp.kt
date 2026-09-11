package com.codepanda.otg.feature.bloatware

/** Confidence that removing a package is safe. */
enum class BloatSafety { SAFE, EXPERT, UNSAFE, UNKNOWN }

/** A system/bloatware candidate on the connected device. */
data class BloatApp(
    val packageName: String,
    val enabled: Boolean,
    val removedForUser: Boolean,
    val knownName: String?,
    val description: String?,
    val safety: BloatSafety,
) {
    val displayName: String
        get() = knownName ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    val isKnownBloat: Boolean get() = knownName != null
}
