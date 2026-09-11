package com.codepanda.otg.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CodePandaColors = darkColorScheme(
    primary = PandaCyan,
    onPrimary = PandaNavyDark,
    primaryContainer = PandaCyanDim,
    onPrimaryContainer = PandaText,
    secondary = PandaCyan,
    onSecondary = PandaNavyDark,
    background = PandaNavy,
    onBackground = PandaText,
    surface = PandaNavySurface,
    onSurface = PandaText,
    surfaceVariant = PandaNavyElevated,
    onSurfaceVariant = PandaTextMuted,
    error = PandaRed,
    onError = PandaNavyDark,
    outline = PandaTextMuted,
)

@Composable
fun CodePandaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The app is always dark — it's a hacker-console aesthetic by design.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = PandaNavy.toArgb()
            window.navigationBarColor = PandaNavy.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = CodePandaColors,
        typography = CodePandaTypography,
        content = content,
    )
}
