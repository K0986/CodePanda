package com.codepanda.otg.feature.bloatware

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.components.ConfirmDialog
import com.codepanda.otg.ui.components.LoadingBox
import com.codepanda.otg.ui.components.MessageBox
import com.codepanda.otg.ui.components.MonogramAvatar
import com.codepanda.otg.ui.theme.PandaAmber
import com.codepanda.otg.ui.theme.PandaGreen
import com.codepanda.otg.ui.theme.PandaRed
import com.codepanda.otg.ui.theme.PandaTextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloatwareScreen(viewModel: BloatwareViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var uninstallTarget by remember { mutableStateOf<BloatApp?>(null) }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = viewModel.query,
                    onValueChange = viewModel::updateQuery,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                FilterChip(
                    selected = viewModel.knownOnly,
                    onClick = viewModel::toggleKnownOnly,
                    label = { Text("Known bloat only") },
                )
            }

            when (val state = viewModel.state) {
                is Async.Loading -> LoadingBox("Scanning system apps…")
                is Async.Failure -> MessageBox("Couldn't scan", state.message)
                is Async.Success -> {
                    val list = viewModel.filtered()
                    if (list.isEmpty()) {
                        MessageBox("Nothing here", "No apps match the current filter.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list, key = { it.packageName }) { app ->
                                BloatRow(
                                    app = app,
                                    onDisable = { viewModel.disable(app) },
                                    onEnable = { viewModel.enable(app) },
                                    onUninstall = { uninstallTarget = app },
                                    onReinstall = { viewModel.reinstall(app) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uninstallTarget?.let { app ->
        val reversibility =
            "This uninstalls the app for the current user (user 0). It is reversible: \"Reinstall\" or a factory reset restores it."
        ConfirmDialog(
            title = when (app.safety) {
                BloatSafety.UNSAFE -> "Do not remove ${app.displayName}"
                BloatSafety.UNKNOWN -> "Remove ${app.displayName}?"
                else -> "Remove ${app.displayName}?"
            },
            message = when (app.safety) {
                BloatSafety.UNSAFE ->
                    "${app.displayName} is a core system component. Removing it can leave the device unable to boot, " +
                        "place calls or open Settings. $reversibility"
                BloatSafety.UNKNOWN ->
                    "This package is not in the safety catalog, so its role is unknown — it may be required by the " +
                        "system or by your carrier. $reversibility"
                else -> reversibility
            },
            confirmLabel = if (app.safety == BloatSafety.UNSAFE) "Remove anyway" else "Remove",
            onConfirm = { uninstallTarget = null; viewModel.uninstallForUser(app) },
            onDismiss = { uninstallTarget = null },
        )
    }
}

@Composable
private fun BloatRow(
    app: BloatApp,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onUninstall: () -> Unit,
    onReinstall: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(app.packageName, app.displayName.take(1))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SafetyBadge(app.safety)
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = PandaTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val status = when {
                app.removedForUser -> "removed for user"
                !app.enabled -> "disabled"
                else -> null
            }
            if (status != null) {
                Text(status, style = MaterialTheme.typography.labelSmall, color = PandaRed)
            }
            app.description?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = PandaTextMuted, maxLines = 2)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, "Actions", tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (app.enabled && !app.removedForUser) {
                    DropdownMenuItem(text = { Text("Disable") }, onClick = { menuOpen = false; onDisable() })
                } else {
                    DropdownMenuItem(text = { Text("Enable") }, onClick = { menuOpen = false; onEnable() })
                }
                if (app.removedForUser) {
                    DropdownMenuItem(text = { Text("Reinstall") }, onClick = { menuOpen = false; onReinstall() })
                } else {
                    DropdownMenuItem(text = { Text("Uninstall (user 0)") }, onClick = { menuOpen = false; onUninstall() })
                }
            }
        }
    }
}

@Composable
private fun SafetyBadge(safety: BloatSafety) {
    val (label, color) = when (safety) {
        BloatSafety.SAFE -> "safe" to PandaGreen
        BloatSafety.EXPERT -> "expert" to PandaAmber
        BloatSafety.UNSAFE -> "unsafe" to PandaRed
        BloatSafety.UNKNOWN -> return
    }
    Box(
        Modifier
            .padding(start = 8.dp)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
