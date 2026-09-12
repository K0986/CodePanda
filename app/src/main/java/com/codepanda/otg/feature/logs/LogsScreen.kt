package com.codepanda.otg.feature.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepanda.otg.core.log.LogEntry
import com.codepanda.otg.core.log.LogFormat
import com.codepanda.otg.core.log.LogLevel
import com.codepanda.otg.ui.components.MessageBox
import com.codepanda.otg.ui.theme.PandaCyan
import com.codepanda.otg.ui.theme.PandaRed
import com.codepanda.otg.ui.theme.PandaTextMuted

/**
 * Live view of the app's own log, with a filter, a share button and the on-disk
 * path of the file that holds the full history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    val context = LocalContext.current
    val all by viewModel.entries.collectAsStateWithLifecycle()
    val visible = viewModel.visible(all)
    val listState = rememberLazyListState()

    LaunchedEffect(visible.size, viewModel.autoScroll) {
        if (viewModel.autoScroll && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("logs_screen"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = viewModel.minLevel == level,
                        onClick = { viewModel.setLevel(level) },
                        label = { Text(level.label.toString()) },
                    )
                }
                IconButton(onClick = viewModel::toggleAutoScroll) {
                    Icon(
                        Icons.Filled.VerticalAlignBottom,
                        contentDescription = "Follow new lines",
                        tint = if (viewModel.autoScroll) PandaCyan else PandaTextMuted,
                    )
                }
                IconButton(onClick = { viewModel.share(context) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share log", tint = PandaCyan)
                }
                IconButton(onClick = viewModel::clear) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear log", tint = PandaRed)
                }
            }

            OutlinedTextField(
                value = viewModel.query,
                onValueChange = viewModel::updateQuery,
                label = { Text("Filter") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )

            Text(
                "${visible.size} of ${all.size} lines in memory · file ${viewModel.logSize()} · ${viewModel.logLocation()}",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = PandaTextMuted,
                style = MaterialTheme.typography.labelSmall,
            )

            if (visible.isEmpty()) {
                MessageBox(
                    title = "Nothing logged yet",
                    subtitle = "Connect a device; the handshake, every command and every transfer " +
                        "will show up here and in the log file.",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                ) {
                    items(visible, key = { "${it.timeMs}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> PandaRed
        LogLevel.WARN -> Color(0xFFFBBF24)
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        else -> PandaTextMuted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "${LogFormat.timestamp(entry.timeMs).substring(11)} ${entry.level.label}/${entry.tag}",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Text(
            text = entry.message,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        entry.error?.let { error ->
            Text(
                text = LogFormat.stackTrace(error),
                color = PandaRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
    }
    androidx.compose.foundation.layout.Spacer(Modifier.size(3.dp))
}
