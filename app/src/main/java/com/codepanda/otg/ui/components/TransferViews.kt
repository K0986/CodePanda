package com.codepanda.otg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codepanda.otg.core.session.Transfer
import com.codepanda.otg.core.session.TransferDirection
import com.codepanda.otg.core.session.TransferState
import com.codepanda.otg.ui.theme.PandaCyan
import com.codepanda.otg.ui.theme.PandaRed
import com.codepanda.otg.ui.theme.PandaTextMuted

/**
 * One transfer: what it is, how far along, how fast, and what went wrong.
 *
 * A determinate bar needs a total, which is why the repository stats a file
 * before pulling it; when the size genuinely is not known the bar falls back to
 * indeterminate rather than lying about progress.
 */
@Composable
fun TransferRow(
    transfer: Transfer,
    onCancel: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                transfer.state == TransferState.FAILED -> Icons.Filled.ErrorOutline
                transfer.state == TransferState.SUCCESS -> Icons.Filled.TaskAlt
                transfer.direction == TransferDirection.DOWNLOAD -> Icons.Filled.Download
                else -> Icons.Filled.Upload
            },
            contentDescription = null,
            tint = when (transfer.state) {
                TransferState.FAILED -> PandaRed
                TransferState.SUCCESS -> PandaCyan
                else -> PandaTextMuted
            },
            modifier = Modifier.size(22.dp),
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                transfer.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (transfer.state == TransferState.RUNNING) {
                val fraction = transfer.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = PandaCyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        color = PandaCyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }

            Text(
                text = when (transfer.state) {
                    TransferState.RUNNING ->
                        transfer.fraction?.let { "${(it * 100).toInt()}% · ${transfer.summary}" }
                            ?: transfer.summary
                    TransferState.SUCCESS ->
                        "Saved ${transfer.summary}" +
                            (transfer.destination?.let { "\n$it" } ?: "")
                    TransferState.FAILED -> transfer.error ?: "Failed"
                    TransferState.CANCELLED -> "Cancelled at ${transfer.summary}"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (transfer.state == TransferState.FAILED) PandaRed else PandaTextMuted,
            )
        }

        if (transfer.state == TransferState.RUNNING) {
            IconButton(onClick = { onCancel(transfer.id) }) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel transfer", tint = PandaRed)
            }
        } else {
            IconButton(onClick = { onDismiss(transfer.id) }) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = PandaTextMuted)
            }
        }
    }
}

/** Compact list of transfers, shown under the app bar so it follows the user between tabs. */
@Composable
fun TransferBanner(
    transfers: List<Transfer>,
    onCancel: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transfers.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        transfers.forEach { transfer ->
            TransferRow(transfer = transfer, onCancel = onCancel, onDismiss = onDismiss)
        }
    }
}
