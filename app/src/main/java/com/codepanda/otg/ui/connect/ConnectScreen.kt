package com.codepanda.otg.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.core.session.ConnectionState
import com.codepanda.otg.ui.components.SectionCard
import com.codepanda.otg.ui.theme.PandaCyan
import com.codepanda.otg.ui.theme.PandaRed
import com.codepanda.otg.ui.theme.PandaTextMuted

/**
 * Shown whenever there is no live session. Lists attached OTG devices and drives
 * the permission + handshake, surfacing progress and errors inline.
 */
@Composable
fun ConnectScreen(
    state: ConnectionState,
    viewModel: ConnectViewModel = viewModel(),
) {
    LaunchedEffect(state) { viewModel.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Filled.Usb, null, tint = PandaCyan, modifier = Modifier.size(72.dp))
        Text(
            "CodePanda OTG",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Manage a phone connected over USB-OTG",
            style = MaterialTheme.typography.bodyMedium,
            color = PandaTextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        when (state) {
            is ConnectionState.Connecting -> ConnectingCard(state.deviceName)
            is ConnectionState.Failed -> FailedCard(state.message)
            else -> Unit
        }

        DeviceListCard(
            devices = viewModel.devices,
            onConnect = viewModel::connect,
            onRefresh = viewModel::refresh,
        )

        HelpCard()
    }
}

@Composable
private fun ConnectingCard(deviceName: String) {
    SectionCard(title = "Connecting") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = PandaCyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Negotiating ADB with $deviceName. Approve the USB-debugging prompt on the device if it appears.",
                Modifier.padding(start = 12.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FailedCard(message: String) {
    SectionCard(title = "Connection failed") {
        Text(message, color = PandaRed, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceListCard(
    devices: List<android.hardware.usb.UsbDevice>,
    onConnect: (android.hardware.usb.UsbDevice) -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard(
        title = "Detected devices",
        trailing = {
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                Text("  Scan")
            }
        },
    ) {
        if (devices.isEmpty()) {
            Text(
                "No compatible device found. Plug a phone into this device's USB-OTG port and enable USB debugging on it.",
                color = PandaTextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            devices.forEach { device ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.productName ?: device.deviceName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "VID ${device.vendorId} · PID ${device.productId}",
                            color = PandaTextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Button(onClick = { onConnect(device) }) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun HelpCard() {
    SectionCard(title = "Setup checklist") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "1. On the target phone: Settings → Developer options → enable USB debugging.",
                "2. Connect it to this device with a USB-OTG adapter/cable.",
                "3. Tap Connect, then approve the on-device prompt (choose \"Always allow\").",
            ).forEach {
                Text(it, color = PandaTextMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
