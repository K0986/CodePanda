package com.codepanda.otg.feature.deviceinfo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.components.InfoRow
import com.codepanda.otg.ui.components.LoadingBox
import com.codepanda.otg.ui.components.MessageBox
import com.codepanda.otg.ui.components.SectionCard

@Composable
fun DeviceInfoScreen(viewModel: DeviceInfoViewModel = viewModel()) {
    when (val state = viewModel.state) {
        is Async.Loading -> LoadingBox("Reading device properties…")
        is Async.Failure -> MessageBox(
            title = "Couldn't read device info",
            subtitle = state.message,
            icon = Icons.Filled.ErrorOutline,
            action = { TextButton(onClick = viewModel::load) { Text("Retry") } },
        )
        is Async.Success -> DeviceInfoContent(state.data)
    }
}

@Composable
private fun DeviceInfoContent(info: DeviceInfo) {
    var showHidden by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(info.sections, key = { it.title }) { section ->
            SectionCard(title = section.title) {
                Column {
                    section.entries.forEach { InfoRow(it.label, it.value) }
                }
            }
        }
        item {
            SectionCard(
                title = "All properties (getprop)",
                trailing = {
                    TextButton(onClick = { showHidden = !showHidden }) {
                        Text(if (showHidden) "Hide" else "Show hidden info")
                        Icon(Icons.Filled.ExpandMore, null)
                    }
                },
            ) {
                if (showHidden) {
                    Column {
                        info.allProperties.forEach { InfoRow(it.label, it.value) }
                    }
                } else {
                    Text(
                        "${info.allProperties.size} system properties available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Column(Modifier.height(24.dp)) {} }
    }
}
