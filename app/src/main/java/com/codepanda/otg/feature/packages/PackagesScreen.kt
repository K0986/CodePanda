package com.codepanda.otg.feature.packages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.components.InfoRow
import com.codepanda.otg.ui.components.LoadingBox
import com.codepanda.otg.ui.components.MessageBox
import com.codepanda.otg.ui.components.MonogramAvatar
import com.codepanda.otg.ui.theme.PandaCyan
import com.codepanda.otg.ui.theme.PandaGreen
import com.codepanda.otg.ui.theme.PandaRed
import com.codepanda.otg.ui.theme.PandaTextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(viewModel: PackagesViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val installPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.install(context, uri) }

    val packages by viewModel.packages.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { installPicker.launch("application/vnd.android.package-archive") },
                containerColor = PandaCyan,
            ) {
                Icon(Icons.Filled.Download, contentDescription = "Install APK")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterRow(viewModel)
            SearchField(viewModel)

            transfers.forEach { transfer ->
                com.codepanda.otg.ui.components.TransferRow(
                    transfer = transfer,
                    onCancel = { id -> com.codepanda.otg.core.session.SessionManager.current?.transfers?.cancel(id) },
                    onDismiss = { id -> com.codepanda.otg.core.session.SessionManager.current?.transfers?.dismiss(id) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            when (val state = packages) {
                is Async.Idle, is Async.Loading -> LoadingBox("Enumerating packages…")
                is Async.Failure -> MessageBox(
                    title = "Couldn't list packages",
                    subtitle = state.message,
                    action = { Button(onClick = viewModel::refresh) { Text("Retry") } },
                )
                is Async.Success -> {
                    val list = viewModel.filtered()
                    if (list.isEmpty()) {
                        MessageBox("No packages match", "Try a different filter or search term.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list, key = { it.packageName }) { pkg ->
                                PackageRow(pkg, viewModel, context)
                            }
                        }
                    }
                }
            }
        }
    }

    val details = viewModel.details
    if (details != null) {
        ModalBottomSheet(onDismissRequest = viewModel::closeDetails) {
            when (details) {
                is Async.Idle, is Async.Loading ->
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator(color = PandaCyan)
                    }
                is Async.Failure -> Text(
                    details.message,
                    Modifier.padding(24.dp),
                    color = PandaRed,
                )
                is Async.Success -> DetailsSheet(details.data)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(viewModel: PackagesViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PackageFilter.entries.forEach { f ->
            FilterChip(
                selected = viewModel.filter == f,
                onClick = { viewModel.updateFilter(f) },
                label = { Text(f.label) },
            )
        }
    }
}

@Composable
private fun SearchField(viewModel: PackagesViewModel) {
    OutlinedTextField(
        value = viewModel.query,
        onValueChange = viewModel::updateQuery,
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        placeholder = { Text("Filter by package name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PackageRow(
    pkg: AppPackage,
    viewModel: PackagesViewModel,
    context: android.content.Context,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(pkg.packageName, pkg.displayName.take(1))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pkg.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!pkg.enabled) {
                    Text(
                        "  ·  disabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = PandaRed,
                    )
                }
            }
            Text(
                pkg.packageName,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = PandaTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { viewModel.extractApk(context, pkg) }) {
            Icon(Icons.Filled.Download, "Extract APK", tint = PandaCyan)
        }
        IconButton(onClick = { viewModel.uninstall(pkg) }) {
            Icon(Icons.Filled.Delete, "Uninstall", tint = PandaRed)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Details") },
                    onClick = { menuOpen = false; viewModel.openDetails(pkg) },
                )
                DropdownMenuItem(
                    text = { Text(if (pkg.enabled) "Disable" else "Enable") },
                    onClick = { menuOpen = false; viewModel.setEnabled(pkg, !pkg.enabled) },
                )
                DropdownMenuItem(
                    text = { Text("Force stop") },
                    onClick = { menuOpen = false; viewModel.forceStop(pkg) },
                )
                DropdownMenuItem(
                    text = { Text("Clear data") },
                    onClick = { menuOpen = false; viewModel.clearData(pkg) },
                )
            }
        }
    }
}

@Composable
private fun DetailsSheet(details: AppPackageDetails) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Android, null, tint = PandaGreen, modifier = Modifier.size(36.dp))
            Text(
                details.packageName,
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.padding(top = 12.dp)) {
            details.versionName?.let { InfoRow("Version name", it) }
            details.versionCode?.let { InfoRow("Version code", it) }
            details.minSdk?.let { InfoRow("Min SDK", it) }
            details.targetSdk?.let { InfoRow("Target SDK", it) }
            details.installerPackage?.let { InfoRow("Installer", it) }
            details.firstInstall?.let { InfoRow("First install", it) }
            details.lastUpdate?.let { InfoRow("Last update", it) }
            details.dataDir?.let { InfoRow("Data dir", it) }
            details.apkPaths.forEachIndexed { i, path -> InfoRow("APK ${i + 1}", path) }
            InfoRow("Permissions", "${details.requestedPermissions.size} requested")
        }
    }
}
