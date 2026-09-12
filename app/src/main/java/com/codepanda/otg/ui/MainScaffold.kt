package com.codepanda.otg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.codepanda.otg.core.AppGraph
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.feature.logs.LogsScreen
import com.codepanda.otg.ui.components.TransferBanner
import com.codepanda.otg.ui.navigation.Destination
import com.codepanda.otg.feature.bloatware.BloatwareScreen
import com.codepanda.otg.feature.deviceinfo.DeviceInfoScreen
import com.codepanda.otg.feature.files.FilesScreen
import com.codepanda.otg.feature.mirror.MirrorScreen
import com.codepanda.otg.feature.packages.PackagesScreen
import com.codepanda.otg.ui.theme.PandaCyan
import kotlinx.coroutines.flow.MutableStateFlow
import com.codepanda.otg.ui.theme.PandaNavyDark
import com.codepanda.otg.ui.theme.PandaTextMuted

/**
 * The main tabbed shell shown while a device is connected: a top bar naming the
 * device (with a disconnect button) and a five-tab bottom navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(deviceName: String) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // Transfers belong to the session, so their progress follows the user from
    // tab to tab instead of vanishing with the screen that started them.
    val session by SessionManager.session.collectAsStateWithLifecycle()
    val transfers by (session?.transfers?.transfers ?: MutableStateFlow(emptyList()))
        .collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        deviceName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = PandaCyan,
                    )
                },
                actions = {
                    IconButton(onClick = { AppGraph.usbManager.disconnect() }) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = "Disconnect",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PandaNavyDark,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = PandaNavyDark) {
                Destination.entries.forEach { dest ->
                    val selected = currentRoute?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PandaNavyDark,
                            selectedTextColor = PandaCyan,
                            indicatorColor = PandaCyan,
                            unselectedIconColor = PandaTextMuted,
                            unselectedTextColor = PandaTextMuted,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            TransferBanner(
                transfers = transfers.filter { !it.isFinished },
                onCancel = { id -> session?.transfers?.cancel(id) },
                onDismiss = { id -> session?.transfers?.dismiss(id) },
            )
            NavHost(
                navController = navController,
                startDestination = Destination.START.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Destination.Device.route) { DeviceInfoScreen() }
                composable(Destination.Apps.route) { PackagesScreen() }
                composable(Destination.Files.route) { FilesScreen() }
                composable(Destination.Debloat.route) { BloatwareScreen() }
                composable(Destination.Mirror.route) { MirrorScreen() }
                composable(Destination.Logs.route) { LogsScreen() }
            }
        }
    }
}
