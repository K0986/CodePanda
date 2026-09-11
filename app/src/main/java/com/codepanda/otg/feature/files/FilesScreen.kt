package com.codepanda.otg.feature.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.components.ConfirmDialog
import com.codepanda.otg.ui.components.LoadingBox
import com.codepanda.otg.ui.components.MessageBox
import com.codepanda.otg.ui.theme.PandaCyan
import com.codepanda.otg.ui.theme.PandaRed
import com.codepanda.otg.ui.theme.PandaTextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }

    val pushPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.push(context, uri) }

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
                onClick = { pushPicker.launch("*/*") },
                containerColor = PandaCyan,
            ) { Icon(Icons.Filled.Upload, contentDescription = "Push file") }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PathBar(
                path = viewModel.currentPath,
                onUp = viewModel::goUp,
                onNewFolder = { showNewFolder = true },
            )
            when (val state = viewModel.state) {
                is Async.Loading -> LoadingBox("Listing ${viewModel.currentPath}…")
                is Async.Failure -> MessageBox("Couldn't open folder", state.message)
                is Async.Success -> {
                    if (state.data.isEmpty()) {
                        MessageBox("Empty folder", "Nothing to show here.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.data, key = { it.absolutePath }) { item ->
                                FileRow(
                                    item = item,
                                    onOpen = { viewModel.navigateInto(item) },
                                    onPull = { viewModel.pull(context, item) },
                                    onDelete = { deleteTarget = item },
                                    onRename = { renameTarget = item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            onConfirm = { name -> showNewFolder = false; viewModel.makeDirectory(name) },
            onDismiss = { showNewFolder = false },
        )
    }
    renameTarget?.let { target ->
        TextPromptDialog(
            title = "Rename",
            label = "New name",
            initial = target.name,
            onConfirm = { name -> renameTarget = null; viewModel.rename(target, name) },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete ${target.name}?",
            message = if (target.isDirectory) "This will remove the folder and all its contents."
            else "This file will be permanently deleted from the device.",
            confirmLabel = "Delete",
            onConfirm = { deleteTarget = null; viewModel.delete(target) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun PathBar(path: String, onUp: () -> Unit, onNewFolder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp) {
            Icon(Icons.Filled.ArrowUpward, "Up", tint = PandaCyan)
        }
        Text(
            path,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onNewFolder) {
            Icon(Icons.Filled.CreateNewFolder, "New folder", tint = PandaCyan)
        }
    }
}

@Composable
private fun FileRow(
    item: FileItem,
    onOpen: () -> Unit,
    onPull: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (item.isDirectory) PandaCyan else PandaTextMuted,
            modifier = Modifier.size(34.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(item.permissions)
                    if (!item.isDirectory) append("   ${formatSize(item.sizeBytes)}")
                    append("   ${formatDate(item.mtimeSeconds)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = PandaTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.isDirectory) {
            IconButton(onClick = onPull) {
                Icon(Icons.Filled.Download, "Pull", tint = PandaCyan)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = PandaRed) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun formatDate(seconds: Long): String {
    if (seconds <= 0) return ""
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(seconds * 1000))
}
