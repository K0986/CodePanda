package com.codepanda.otg.feature.mirror

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.components.LoadingBox
import com.codepanda.otg.ui.components.MessageBox
import com.codepanda.otg.ui.theme.PandaCyan
import com.codepanda.otg.ui.theme.PandaNavyDark
import com.codepanda.otg.ui.theme.PandaRed
import com.codepanda.otg.ui.theme.PandaTextMuted

private enum class MirrorMode { SCREENSHOT, LIVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorScreen(viewModel: MirrorViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var mode by remember { mutableStateOf(MirrorMode.SCREENSHOT) }

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
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = mode == MirrorMode.SCREENSHOT,
                    onClick = { mode = MirrorMode.SCREENSHOT },
                    label = { Text("Screenshot") },
                )
                FilterChip(
                    selected = mode == MirrorMode.LIVE,
                    onClick = { mode = MirrorMode.LIVE },
                    label = { Text("Live mirror") },
                )
            }

            when (mode) {
                MirrorMode.SCREENSHOT -> ScreenshotPane(viewModel, context)
                MirrorMode.LIVE -> LivePane(viewModel)
            }
        }
    }
}

@Composable
private fun ScreenshotPane(viewModel: MirrorViewModel, context: android.content.Context) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = viewModel::captureScreenshot) {
                Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp))
                Text("  Capture")
            }
            OutlinedButton(onClick = { viewModel.saveScreenshot(context) }) {
                Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                Text("  Save")
            }
        }
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            when (val shot = viewModel.screenshot) {
                null -> MessageBox(
                    title = "No screenshot yet",
                    subtitle = "Tap Capture to grab the device screen.",
                    icon = Icons.Filled.CameraAlt,
                )
                is Async.Idle, is Async.Loading -> LoadingBox("Capturing…")
                is Async.Failure -> MessageBox("Capture failed", shot.message)
                is Async.Success -> Image(
                    bitmap = shot.data,
                    contentDescription = "Device screenshot",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LivePane(viewModel: MirrorViewModel) {
    DisposableEffect(Unit) {
        onDispose { viewModel.stopMirror() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (viewModel.mirroring) {
                OutlinedButton(onClick = viewModel::stopMirror) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                    Text("  Stop")
                }
                Text("Live", color = PandaCyan, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Filled.PlayArrow, null, tint = PandaTextMuted)
                Text(
                    "Mirroring starts automatically once the view is ready.",
                    color = PandaTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        viewModel.mirrorStatus?.let { status ->
            Text(
                status,
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = if (viewModel.mirroring) PandaCyan else PandaRed,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Box(
            Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        setBackgroundColor(PandaNavyDark.toArgb())
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.startMirror(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.stopMirror()
                            }
                        })
                    }
                },
            )
        }
        Text(
            "Tip: live mirroring uses the device's screenrecord encoder (H.264). Sessions auto-restart every few minutes due to the platform time limit.",
            Modifier.padding(12.dp),
            color = PandaTextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
