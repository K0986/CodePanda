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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.components.ConfirmDialog
import com.codepanda.otg.ui.components.LoadingBox
import com.codepanda.otg.ui.components.MessageBox
import com.codepanda.otg.ui.components.TransferRow
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

    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()

    val pushPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.upload(context, uri) }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Files: $currentPath", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { pushPicker.launch("*/*") }) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload")
                    }
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (listing) {
                Async.Idle -> {}
                Async.Loading -> LoadingBox()
                is Async.Failure -> {
                    MessageBox(
                        title = "Error",
                        message = (listing as Async.Failure).message,
                        onDismiss = { viewModel.refresh() },
                    )
                }
                is Async.Success -> {
                    val items = (listing as Async.Success<List<FileItem>>).value
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items) { item ->
                            FileItemRow(
                                item = item,
                                onOpen = { viewModel.navigateInto(item) },
                                onDownload = { viewModel.download(context, item) },
                                onRename = { renameTarget = item },
                                onDelete = { deleteTarget = item },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        CreateFolderDialog(
            onConfirm = { name ->
                viewModel.makeDirectory(name)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    if (renameTarget != null) {
        RenameFolderDialog(
            item = renameTarget!!,
            onConfirm = { newName ->
                viewModel.rename(renameTarget!!, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (deleteTarget != null) {
        DeleteDialog(
            item = deleteTarget!!,
            onConfirm = {
                viewModel.delete(deleteTarget!!)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun FileItemRow(
    item: FileItem,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val icon = when {
        item.accessError != null -> Icons.Default.Lock  // ✨ NEW: Lock icon for inaccessible items
        item.isDirectory -> Icons.Default.Folder
        else -> Icons.Default.Description
    }

    val iconTint = when {
        item.accessError != null -> MaterialTheme.colorScheme.error  // ✨ NEW: Red for errors
        item.isDirectory -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = item.isAccessible && (item.isDirectory || item.isSymlink),
                onClick = onOpen,
            )
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                )

                // ✨ NEW: Show error message for inaccessible items
                if (item.accessError != null) {
                    Text(
                        item.accessError!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // ✨ NEW: Show symlink target if present
                else if (item.isSymlink && item.symlinkTarget != null) {
                    Text(
                        "→ ${item.symlinkTarget}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PandaTextMuted,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Show file size for regular files
                else if (!item.isDirectory) {
                    Text(
                        formatFileSize(item.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = PandaTextMuted,
                    )
                }
            }

            if (item.isAccessible) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Properties") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                // Show properties dialog
                            },
                        )
                        if (!item.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDownload()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Folder") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(folderName) },
                enabled = folderName.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RenameFolderDialog(
    item: FileItem,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(item.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newName) },
                enabled = newName.isNotBlank() && newName != item.name,
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    item: FileItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete") },
        text = { Text("Delete ${item.name}?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
