package com.kareem.secondbrain.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kareem.secondbrain.capture.android.connector.RelayConnectionState
import com.kareem.secondbrain.capture.android.connector.RelayDeliveryState
import com.kareem.secondbrain.capture.android.connector.RelayFilterState
import com.kareem.secondbrain.capture.android.connector.RelayRecentSignal
import com.kareem.secondbrain.capture.android.connector.RelayRuntimeDiagnostics
import com.kareem.secondbrain.core.model.CaptureAccessSnapshot
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val signalTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
internal fun RelayDashboardScreen(
    captureState: CaptureState,
    access: CaptureAccessSnapshot,
    onToggleCapture: () -> Unit,
    onNotificationAccess: () -> Unit,
    onAccessibilityAccess: () -> Unit,
    onUsageAccess: () -> Unit,
) {
    val diagnostics by RelayRuntimeDiagnostics.state.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = "Cortex Relay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "Android evidence gateway for Cortex",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(
            title = "Capture",
            rows = listOf(
                "Mode" to if (captureState.mode == CaptureMode.RUNNING) "Running" else "Paused",
                "Notification listener" to if (captureState.notificationListenerConnected) "Connected" else "Disconnected",
                "Notification access" to if (access.notificationAccess) "Granted" else "Needs access",
            ),
        )

        StatusCard(
            title = "Cortex link",
            rows = listOf(
                "Endpoint" to when (diagnostics.connectionState) {
                    RelayConnectionState.CONNECTED -> "ACK verified"
                    RelayConnectionState.CONNECTING -> "Binding / handshake"
                    RelayConnectionState.DISCONNECTED -> "Disconnected"
                },
                "Last Cortex ACK" to (diagnostics.lastCortexStatus ?: "—"),
                "Last Cortex signal" to diagnostics.lastCortexSignalId.takeIf { it > 0 }?.toString().orEmpty().ifEmpty { "—" },
                "Protocol" to "Local Bus V1",
                "Connector" to "second_brain",
            ),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This process session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                MetricRow("Captured", diagnostics.captured.toString())
                MetricRow("Sent to Cortex", diagnostics.sent.toString())
                MetricRow("Delivered (Cortex ACK)", diagnostics.forwarded.toString())
                MetricRow("Rejected by Cortex", diagnostics.rejected.toString())
                MetricRow("Filtered confirmed noise", diagnostics.filtered.toString())
                MetricRow("Low-value but forwarded", diagnostics.lowValueForwarded.toString())
                MetricRow("Waiting / in-flight", diagnostics.waiting.toString())
                MetricRow("Failed / retry events", diagnostics.failedRetries.toString())
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recent signals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Live process view · newest first · up to 12 shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diagnostics.recentSignals.isEmpty()) {
                    Text(
                        text = "No notification signals captured in this process yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    diagnostics.recentSignals.take(12).forEachIndexed { index, signal ->
                        RecentSignalRow(signal)
                        if (index < diagnostics.recentSignals.take(12).lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Latest relay decision", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Source: ${diagnostics.lastPackage ?: "—"}")
                Text(
                    "Filter: ${when (diagnostics.lastFilterState) {
                        RelayFilterState.FORWARD -> "FORWARD"
                        RelayFilterState.LOW_VALUE -> "LOW_VALUE"
                        RelayFilterState.DROP_CONFIRMED_NOISE -> "DROP_CONFIRMED_NOISE"
                        null -> "—"
                    }}",
                )
                Text("Reason: ${diagnostics.lastFilterReason ?: "—"}")
                Text("Cortex ACK: ${diagnostics.lastCortexStatus ?: "—"}")
                diagnostics.lastError?.let { error -> Text("Last connector issue: $error") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = onToggleCapture, modifier = Modifier.fillMaxWidth()) {
                    Text(if (captureState.mode == CaptureMode.RUNNING) "Pause capture" else "Resume capture")
                }
                OutlinedButton(onClick = onNotificationAccess, modifier = Modifier.fillMaxWidth()) { Text("Notification access") }
                OutlinedButton(onClick = onAccessibilityAccess, modifier = Modifier.fillMaxWidth()) { Text("Accessibility access") }
                OutlinedButton(onClick = onUsageAccess, modifier = Modifier.fillMaxWidth()) { Text("Usage access") }
            }
        }

        HorizontalDivider()
        Text(
            text = "Delivery is confirmed only after Cortex replies with the same Local Bus event_id. " +
                "A successful Android bind or Messenger.send() alone is not shown as delivered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Filtering is deliberately conservative. Uncertain system state is preserved; only narrow confirmed noise rules suppress forwarding.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RecentSignalRow(signal: RelayRecentSignal) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatSignalTime(signal), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(text = deliveryLabel(signal.deliveryState), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            text = signal.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        signal.title?.let { title ->
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        signal.preview?.let { preview ->
            Text(text = preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = "sb_${signal.eventId} · ${filterLabel(signal.filterState)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = signal.deliveryDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (signal.cortexStatus != null) {
            Text(
                text = "Cortex: ${signal.cortexStatus}${if (signal.cortexSignalId > 0) " · signal ${signal.cortexSignalId}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (signal.filterState != RelayFilterState.FORWARD && signal.filterReason != null) {
            Text(text = "Filter: ${signal.filterReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSignalTime(signal: RelayRecentSignal): String = signalTimeFormatter.format(signal.occurredAt.atZone(ZoneId.systemDefault()))

private fun deliveryLabel(state: RelayDeliveryState): String = when (state) {
    RelayDeliveryState.CAPTURED -> "CAPTURED"
    RelayDeliveryState.WAITING -> "WAITING"
    RelayDeliveryState.SENT -> "AWAITING ACK"
    RelayDeliveryState.FORWARDED -> "DELIVERED"
    RelayDeliveryState.FILTERED -> "FILTERED"
    RelayDeliveryState.RETRYING -> "RETRYING"
    RelayDeliveryState.REJECTED -> "REJECTED"
    RelayDeliveryState.FAILED -> "FAILED"
}

private fun filterLabel(state: RelayFilterState?): String = when (state) {
    RelayFilterState.FORWARD -> "FORWARD"
    RelayFilterState.LOW_VALUE -> "LOW_VALUE"
    RelayFilterState.DROP_CONFIRMED_NOISE -> "DROP_CONFIRMED_NOISE"
    null -> "FILTER_PENDING"
}

@Composable
private fun StatusCard(title: String, rows: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            rows.forEach { (label, value) -> MetricRow(label, value) }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
