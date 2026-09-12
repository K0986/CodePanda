package com.codepanda.otg.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.ui.graphics.vector.ImageVector

/** The primary tabs, mirroring the feature set of the app. */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Device("device", "Device", Icons.Filled.Memory),
    Apps("apps", "Apps", Icons.Filled.Apps),
    Files("files", "Files", Icons.Filled.Folder),
    Debloat("debloat", "Debloat", Icons.Filled.DeleteSweep),
    Mirror("mirror", "Mirror", Icons.Filled.ScreenShare),
    Logs("logs", "Logs", Icons.AutoMirrored.Filled.Article),
    ;

    companion object {
        val START = Device
    }
}
