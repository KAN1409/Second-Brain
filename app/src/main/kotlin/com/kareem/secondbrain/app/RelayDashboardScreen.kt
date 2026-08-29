package com.kareem.secondbrain.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val signalTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val fullSignalTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var selectedSignal by remember { mutableStateOf<RelayRecentSignal?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = "Cortex Relay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "Captures phone evidence and delivers it to Cortex",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(
            title = "Capture",
            rows = listOf(
                "Status" to if (captureState.mode == CaptureMode.RUNNING) "Running" else "Paused",
                "Notifications" to if (captureState.notificationListenerConnected && access.notificationAccess) "Listening" else "Needs attention",
            ),
        )

        StatusCard(
            title = "Cortex",
            rows = listOf(
                "Connection" to when (diagnostics.connectionState) {
                    RelayConnectionState.CONNECTED -> "Ready"
                    RelayConnectionState.CONNECTING -> "Connecting"
                    RelayConnectionState.DISCONNECTED -> "Disconnected"
                },
                "Last response" to cortexResponseLabel(diagnostics.lastCortexStatus),
                "Last Cortex signal" to diagnostics.lastCortexSignalId.takeIf { it > 0 }?.let { "#$it" }.orEmpty().ifEmpty { "—" },
            ),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                MetricRow("Captured", diagnostics.captured.toString())
                MetricRow("Send attempts", diagnostics.sent.toString())
                MetricRow("Delivered to Cortex", diagnostics.forwarded.toString())
                MetricRow("Rejected by Cortex", diagnostics.rejected.toString())
                MetricRow("Filtered as confirmed noise", diagnostics.filtered.toString())
                MetricRow("Waiting / in flight", diagnostics.waiting.toString())
                MetricRow("Retries / delivery issues", diagnostics.failedRetries.toString())
                if (diagnostics.lifecycleUpdated > 0 || diagnostics.lifecycleRemoved > 0) {
                    MetricRow(
                        "Lifecycle P / U / R",
                        "${diagnostics.lifecyclePosted} / ${diagnostics.lifecycleUpdated} / ${diagnostics.lifecycleRemoved}",
                    )
                }
                OutlinedButton(
                    onClick = {
                        runCatching { RelayDiagnosticExporter.share(context, diagnostics) }
                            .onFailure { error -> Toast.makeText(context, "Diagnostic export failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share diagnostic report") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recent signals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Newest first · tap any signal to see everything Relay captured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diagnostics.recentSignals.isEmpty()) {
                    Text(
                        text = "No notification signals captured in this session yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val visibleSignals = diagnostics.recentSignals.take(12)
                    visibleSignals.forEachIndexed { index, signal ->
                        RecentSignalRow(signal = signal, onClick = { selectedSignal = signal })
                        if (index < visibleSignals.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        diagnostics.lastError?.takeIf(String::isNotBlank)?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Latest delivery issue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Open the affected signal above for its delivery status and Cortex response.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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

        Text(
            text = "Delivered means Cortex replied for that exact signal. Sending it to Android IPC alone is not counted as delivery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    selectedSignal?.let { signal ->
        SignalDetailsSheet(signal = signal, onDismiss = { selectedSignal = null })
    }
}

@Composable
private fun RecentSignalRow(signal: RelayRecentSignal, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = friendlyAppName(context, signal.packageName),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = humanDeliveryLabel(signal.deliveryState),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        signal.title?.let { title ->
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        signal.preview?.let { preview ->
            Text(text = preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        signal.signalType?.let { type ->
            Text(
                text = buildString {
                    append(humanSignalType(type))
                    signal.lifecycleState?.let { lifecycle -> append(" · ").append(humanLifecycle(lifecycle)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatSignalTime(signal.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Tap for details",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignalDetailsSheet(signal: RelayRecentSignal, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showTechnical by remember(signal.eventId) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(friendlyAppName(context, signal.packageName), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                humanDeliveryLabel(signal.deliveryState),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                fullSignalTimeFormatter.format(signal.occurredAt.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            signal.signalType?.let { DetailRow("Type", humanSignalType(it)) }
            signal.lifecycleState?.let { lifecycle ->
                DetailRow(
                    "Lifecycle",
                    buildString {
                        append(humanLifecycle(lifecycle))
                        signal.updateSequence?.let { append(" · update #").append(it) }
                    },
                )
            }
            signal.title?.let { DetailSection("From / title", it) }
            signal.conversationTitle?.takeIf { it != signal.title }?.let { DetailSection("Conversation", it) }

            val fullText = signal.expandedText ?: signal.body
            fullText?.let { DetailSection("Notification text", it) }
            signal.body?.takeIf { it != fullText }?.let { DetailSection("Short text", it) }

            if (signal.messages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Messages", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    signal.messages.forEach { message ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                message.sender?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                                Text(message.text)
                                message.occurredAt?.let { time ->
                                    Text(
                                        fullSignalTimeFormatter.format(time.atZone(ZoneId.systemDefault())),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Delivery", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            DetailRow("Status", humanDeliveryLabel(signal.deliveryState))
            signal.cortexStatus?.let { DetailRow("Cortex response", cortexResponseLabel(it)) }
            if (signal.cortexSignalId > 0) DetailRow("Cortex signal", "#${signal.cortexSignalId}")
            if (signal.deliveryState != RelayDeliveryState.FORWARDED) {
                Text(signal.deliveryDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text("Relay decision", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            DetailRow("Action", humanFilterLabel(signal.filterState))
            signal.filterReason?.let { DetailSection("Reason", it) }

            OutlinedButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showTechnical) "Hide technical details" else "Show technical details")
            }

            if (showTechnical) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Technical details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        DetailRow("Package", signal.packageName)
                        DetailRow("Relay event", "sb_${signal.eventId}")
                        signal.logicalSignalId?.let { DetailRow("Logical signal", it) }
                        signal.notificationIdentity?.let { DetailRow("Notification identity", it) }
                        DetailRow("Send attempts", signal.sendAttempts.toString())
                        DetailRow("Delivery issues", signal.deliveryIssueIncidents.toString())
                        DetailRow("Protocol", "CORTEX_INGEST_V1 / Local Bus V1")
                        DetailRow("Captured", signal.capturedAt.toString())
                        DetailRow("Last updated", signal.updatedAt.toString())
                        signal.filterState?.let { DetailRow("Filter state", it.name) }
                        signal.cortexStatus?.let { DetailRow("Raw Cortex status", it) }
                        signal.metadataJson?.let { metadata ->
                            Text("Android metadata", fontWeight = FontWeight.SemiBold)
                            Text(prettyJson(metadata), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.58f), fontWeight = FontWeight.Medium)
    }
}

private fun formatSignalTime(instant: Instant): String = signalTimeFormatter.format(instant.atZone(ZoneId.systemDefault()))

private fun friendlyAppName(context: Context, packageName: String): String {
    val packageManager = context.packageManager
    val installedLabel = runCatching {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(applicationInfo).toString().trim()
    }.getOrNull()?.takeIf { label ->
        label.isNotBlank() && !(label.equals("Android", ignoreCase = true) && !isAndroidFrameworkPackage(packageName))
    }
    if (installedLabel != null) return installedLabel

    return when (packageName) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "com.google.android.gm" -> "Gmail"
        "com.google.android.apps.messaging" -> "Messages"
        "com.samsung.android.messaging" -> "Samsung Messages"
        "com.samsung.android.dialer" -> "Phone"
        "com.android.systemui" -> "Android System"
        "com.samsung.android.app.smartcapture" -> "Samsung Capture"
        "com.openai.chatgpt" -> "ChatGPT"
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        else -> packageDerivedAppName(packageName)
    }
}

private fun isAndroidFrameworkPackage(packageName: String): Boolean =
    packageName == "android" || packageName.startsWith("com.android.")

private fun packageDerivedAppName(packageName: String): String {
    val genericSegments = setOf("com", "org", "net", "android", "app", "apps", "mobile", "client", "release", "debug")
    val candidate = packageName
        .split('.')
        .asReversed()
        .firstOrNull { segment -> segment.isNotBlank() && segment.lowercase() !in genericSegments }
        ?: packageName.substringAfterLast('.')
    return candidate.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
}

private fun humanDeliveryLabel(state: RelayDeliveryState): String = when (state) {
    RelayDeliveryState.CAPTURED -> "Saved locally"
    RelayDeliveryState.WAITING -> "Waiting to send"
    RelayDeliveryState.SENT -> "Waiting for Cortex"
    RelayDeliveryState.FORWARDED -> "Delivered to Cortex"
    RelayDeliveryState.FILTERED -> "Filtered · kept locally"
    RelayDeliveryState.RETRYING -> "Retrying delivery"
    RelayDeliveryState.REJECTED -> "Rejected by Cortex"
    RelayDeliveryState.FAILED -> "Delivery failed"
}

private fun humanFilterLabel(state: RelayFilterState?): String = when (state) {
    RelayFilterState.FORWARD -> "Send to Cortex"
    RelayFilterState.LOW_VALUE -> "Low-value, still send"
    RelayFilterState.DROP_CONFIRMED_NOISE -> "Keep locally, do not send"
    null -> "Not decided yet"
}

private fun humanSignalType(type: String): String = when (type) {
    "HUMAN_MESSAGE" -> "Message"
    "EMAIL" -> "Email"
    "CALL" -> "Call"
    "SMS" -> "SMS"
    "OTP" -> "Verification code"
    "BANKING" -> "Banking"
    "DELIVERY" -> "Delivery"
    "CALENDAR" -> "Calendar"
    "SECURITY" -> "Security"
    "DOWNLOAD" -> "Download"
    "SYSTEM_NOISE" -> "System state"
    "OTHER" -> "Other"
    else -> type.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun humanLifecycle(state: String): String = when (state) {
    "POSTED" -> "New notification"
    "UPDATED" -> "Notification update"
    "REMOVED" -> "Removed"
    else -> state.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun cortexResponseLabel(status: String?): String = when (status) {
    null, "" -> "—"
    "READY", "HELLO_ACCEPTED" -> "Ready"
    "ACCEPTED" -> "Accepted"
    "DUPLICATE_ACCEPTED" -> "Accepted (already received)"
    "POLICY_BLOCKED" -> "Blocked by Cortex policy"
    "EMPTY" -> "Rejected: no usable content"
    "INVALID_EVENT" -> "Rejected: invalid signal"
    "RAW_CAPTURE_FAILED" -> "Cortex capture failed"
    "INGEST_FAILED" -> "Cortex ingest failed"
    else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun prettyJson(raw: String): String = runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)

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
