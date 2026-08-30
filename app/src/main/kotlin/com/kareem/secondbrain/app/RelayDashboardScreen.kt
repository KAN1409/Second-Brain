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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.material3.Surface
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
import com.kareem.secondbrain.capture.android.connector.RelayRawNotification
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

private enum class RelayFeedPage { ALL_CAPTURED, TO_CORTEX }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelayDashboardScreen(
    captureState: CaptureState,
    access: CaptureAccessSnapshot,
    onToggleCapture: () -> Unit,
    onNotificationAccess: () -> Unit,
    onAccessibilityAccess: () -> Unit,
    onUsageAccess: () -> Unit,
    onReplayEvidence: () -> Unit,
    onRunSystemTest: () -> Unit,
    systemTestRunning: Boolean,
) {
    val diagnostics by RelayRuntimeDiagnostics.state.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var selectedSignal by remember { mutableStateOf<RelayRecentSignal?>(null) }
    var selectedRaw by remember { mutableStateOf<RelayRawNotification?>(null) }
    var page by remember { mutableStateOf(RelayFeedPage.ALL_CAPTURED) }

    val rawNotifications = diagnostics.rawNotifications
    val toCortexSignals = remember(diagnostics.recentSignals) {
        diagnostics.recentSignals.filter { signal ->
            signal.filterState == RelayFilterState.FORWARD || signal.filterState == RelayFilterState.LOW_VALUE
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text("Cortex Relay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Phone evidence in. Grounded signals out.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = when {
                        captureState.mode != CaptureMode.RUNNING -> "Paused"
                        diagnostics.connectionState == RelayConnectionState.CONNECTED -> "Ready"
                        diagnostics.connectionState == RelayConnectionState.CONNECTING -> "Connecting"
                        else -> "Offline"
                    },
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Live pipeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowMetric("Received", diagnostics.rawReceived.toString(), Modifier.weight(1f))
                        FlowMetric("To Cortex", diagnostics.sent.toString(), Modifier.weight(1f))
                        FlowMetric("Filtered", diagnostics.filtered.toString(), Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    MetricRow("Notification listener", if (captureState.notificationListenerConnected && access.notificationAccess) "Listening" else "Needs access")
                    MetricRow(
                        "Cortex",
                        when (diagnostics.connectionState) {
                            RelayConnectionState.CONNECTED -> "Connected · ${cortexResponseLabel(diagnostics.lastCortexStatus)}"
                            RelayConnectionState.CONNECTING -> "Connecting"
                            RelayConnectionState.DISCONNECTED -> "Disconnected"
                        },
                    )
                    diagnostics.lastCortexSignalId.takeIf { it > 0 }?.let { MetricRow("Last signal", "#$it") }
                    if (diagnostics.waiting > 0) MetricRow("Waiting / in flight", diagnostics.waiting.toString())
                }
            }

            FeedPageSelector(
                selected = page,
                allCount = rawNotifications.size,
                toCortexCount = toCortexSignals.size,
                onSelect = { page = it },
            )

            when (page) {
                RelayFeedPage.ALL_CAPTURED -> RawNotificationFeedCard(
                    notifications = rawNotifications,
                    onNotification = { selectedRaw = it },
                )
                RelayFeedPage.TO_CORTEX -> ProcessedSignalFeedCard(
                    signals = toCortexSignals,
                    onSignal = { selectedSignal = it },
                )
            }

            diagnostics.lastError?.takeIf(String::isNotBlank)?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Delivery issue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Relay controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onToggleCapture, modifier = Modifier.fillMaxWidth()) {
                        Text(if (captureState.mode == CaptureMode.RUNNING) "Pause Relay" else "Resume Relay")
                    }
                    OutlinedButton(onClick = onRunSystemTest, enabled = !systemTestRunning, modifier = Modifier.fillMaxWidth()) {
                        Text(if (systemTestRunning) "Running full system test…" else "Full system test")
                    }
                    OutlinedButton(onClick = onReplayEvidence, modifier = Modifier.fillMaxWidth()) { Text("Replay latest evidence") }
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
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Evidence access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Notification access is required. Accessibility and Usage add grounded screen/app context; Relay has no microphone capture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onNotificationAccess, modifier = Modifier.fillMaxWidth()) { Text("Notification access") }
                    OutlinedButton(onClick = onAccessibilityAccess, modifier = Modifier.fillMaxWidth()) { Text("Accessibility access") }
                    OutlinedButton(onClick = onUsageAccess, modifier = Modifier.fillMaxWidth()) { Text("Usage access") }
                }
            }

            Text(
                "Relay only performs grounded categorization, filtering and evidence-quality assessment. Personal importance and priority remain Cortex decisions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    selectedSignal?.let { signal ->
        SignalDetailsSheet(signal = signal, onDismiss = { selectedSignal = null })
    }
    selectedRaw?.let { notification ->
        RawNotificationDetailsSheet(notification = notification, onDismiss = { selectedRaw = null })
    }
}

@Composable
private fun FeedPageSelector(
    selected: RelayFeedPage,
    allCount: Int,
    toCortexCount: Int,
    onSelect: (RelayFeedPage) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected == RelayFeedPage.ALL_CAPTURED) {
            Button(onClick = { onSelect(RelayFeedPage.ALL_CAPTURED) }, modifier = Modifier.weight(1f)) { Text("All · $allCount") }
        } else {
            OutlinedButton(onClick = { onSelect(RelayFeedPage.ALL_CAPTURED) }, modifier = Modifier.weight(1f)) { Text("All · $allCount") }
        }
        if (selected == RelayFeedPage.TO_CORTEX) {
            Button(onClick = { onSelect(RelayFeedPage.TO_CORTEX) }, modifier = Modifier.weight(1f)) { Text("To Cortex · $toCortexCount") }
        } else {
            OutlinedButton(onClick = { onSelect(RelayFeedPage.TO_CORTEX) }, modifier = Modifier.weight(1f)) { Text("To Cortex · $toCortexCount") }
        }
    }
}

@Composable
private fun RawNotificationFeedCard(
    notifications: List<RelayRawNotification>,
    onNotification: (RelayRawNotification) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("All notifications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Raw listener feed · every notification callback before dedupe, categorization, filtering or routing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notifications.isEmpty()) {
                Text("No notifications received in this session yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                notifications.forEachIndexed { index, item ->
                    RawNotificationRow(item = item, onClick = { onNotification(item) })
                    if (index < notifications.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RawNotificationRow(item: RelayRawNotification, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                friendlyAppName(context, item.packageName),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("RAW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (item.title ?: item.conversationTitle)?.let { title ->
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item.body?.let { body ->
            Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatSignalTime(item.receivedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProcessedSignalFeedCard(
    signals: List<RelayRecentSignal>,
    onSignal: (RelayRecentSignal) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("To Cortex", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Categorized, deduplicated, filtered and mechanically assessed. This is the exact notification lane allowed to Cortex.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (signals.isEmpty()) {
                Text("Nothing has passed Relay's routing gate in this session yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                signals.forEachIndexed { index, signal ->
                    ProcessedSignalRow(signal = signal, onClick = { onSignal(signal) })
                    if (index < signals.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProcessedSignalRow(signal: RelayRecentSignal, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                friendlyAppName(context, signal.packageName),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(humanRouteLabel(signal), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        signal.title?.let { title ->
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        signal.preview?.let { preview ->
            Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            buildString {
                append(signal.signalType?.let(::humanSignalType) ?: "Uncategorized")
                signal.lifecycleState?.let { append(" · ").append(humanLifecycle(it)) }
                append(" · ").append(humanFilterLabel(signal.filterState))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        signal.filterReason?.takeIf(String::isNotBlank)?.let { reason ->
            Text(
                "Assessment: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatSignalTime(signal.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawNotificationDetailsSheet(notification: RelayRawNotification, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(friendlyAppName(context, notification.packageName), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Raw notification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                fullSignalTimeFormatter.format(notification.receivedAt.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notification.title?.let { DetailSection("Title", it) }
            notification.conversationTitle?.takeIf { it != notification.title }?.let { DetailSection("Conversation", it) }
            notification.body?.let { DetailSection("Notification text", it) }
            HorizontalDivider()
            Text("Before processing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "This view is captured before Relay dedupe, categorization, filtering and Cortex routing. Check the To Cortex page for the processed lane.",
                style = MaterialTheme.typography.bodyMedium,
            )
            DetailRow("Package", notification.packageName)
            DetailRow("Notification key", notification.notificationKey)
            DetailRow("Android post time", notification.occurredAt.toString())
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
            Text(humanDeliveryLabel(signal.deliveryState), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                fullSignalTimeFormatter.format(signal.occurredAt.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            signal.signalType?.let { DetailRow("Category", humanSignalType(it)) }
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
            Text("Relay assessment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            DetailRow("Routing", humanFilterLabel(signal.filterState))
            signal.filterReason?.let { DetailSection("Reason", it) }
            Text(
                "Operational evidence assessment only — Relay does not decide personal importance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Delivery", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            DetailRow("Status", humanDeliveryLabel(signal.deliveryState))
            signal.cortexStatus?.let { DetailRow("Cortex response", cortexResponseLabel(it)) }
            if (signal.cortexSignalId > 0) DetailRow("Cortex signal", "#${signal.cortexSignalId}")
            if (signal.deliveryState != RelayDeliveryState.FORWARDED) {
                Text(signal.deliveryDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

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
                        signal.conversationIdentity?.let { DetailRow("Conversation identity", it) }
                        DetailRow("Send attempts", signal.sendAttempts.toString())
                        DetailRow("Delivery issues", signal.deliveryIssueIncidents.toString())
                        DetailRow("Protocol", "CORTEX_SIGNAL_V2 · V1 fallback")
                        DetailRow("Captured", signal.capturedAt.toString())
                        DetailRow("Last updated", signal.updatedAt.toString())
                        signal.filterState?.let { DetailRow("Filter state", it.name) }
                        signal.cortexStatus?.let { DetailRow("Raw Cortex status", it) }
                        signal.metadataJson?.let { metadata ->
                            Text("Grounded metadata", fontWeight = FontWeight.SemiBold)
                            Text(prettyJson(metadata), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FlowMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
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
    RelayDeliveryState.CAPTURED -> "Captured"
    RelayDeliveryState.WAITING -> "Queued for Cortex"
    RelayDeliveryState.SENT -> "Awaiting Cortex ACK"
    RelayDeliveryState.FORWARDED -> "Delivered to Cortex"
    RelayDeliveryState.FILTERED -> "Filtered · kept locally"
    RelayDeliveryState.RETRYING -> "Retrying delivery"
    RelayDeliveryState.REJECTED -> "Rejected by Cortex"
    RelayDeliveryState.FAILED -> "Delivery failed"
}

private fun humanRouteLabel(signal: RelayRecentSignal): String = when (signal.deliveryState) {
    RelayDeliveryState.FORWARDED -> "Delivered"
    RelayDeliveryState.SENT -> "Sent"
    RelayDeliveryState.WAITING -> "Queued"
    RelayDeliveryState.RETRYING -> "Retrying"
    RelayDeliveryState.REJECTED -> "Rejected"
    RelayDeliveryState.FAILED -> "Failed"
    RelayDeliveryState.CAPTURED -> "Approved"
    RelayDeliveryState.FILTERED -> "Filtered"
}

private fun humanFilterLabel(state: RelayFilterState?): String = when (state) {
    RelayFilterState.FORWARD -> "Forward"
    RelayFilterState.LOW_VALUE -> "Low-value · forward"
    RelayFilterState.DROP_CONFIRMED_NOISE -> "Confirmed noise · keep local"
    null -> "Pending assessment"
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
    "POSTED" -> "New"
    "UPDATED" -> "Updated"
    "REMOVED" -> "Removed"
    else -> state.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun cortexResponseLabel(status: String?): String = when (status) {
    null, "" -> "—"
    "READY", "HELLO_ACCEPTED" -> "Ready"
    "ACCEPTED" -> "Accepted"
    "DUPLICATE_ACCEPTED" -> "Accepted · already received"
    "POLICY_BLOCKED" -> "Blocked by Cortex policy"
    "EMPTY" -> "Rejected · no usable content"
    "INVALID_EVENT" -> "Rejected · invalid signal"
    "RAW_CAPTURE_FAILED" -> "Cortex capture failed"
    "INGEST_FAILED" -> "Cortex ingest failed"
    else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun prettyJson(raw: String): String = runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)
