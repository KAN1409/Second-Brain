package com.kareem.secondbrain.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kareem.secondbrain.core.model.CaptureAccessSnapshot
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    captureState: CaptureState,
    access: CaptureAccessSnapshot,
    onToggleCapture: () -> Unit,
    onNotificationAccess: () -> Unit,
    onAccessibilityAccess: () -> Unit,
    onUsageAccess: () -> Unit,
    onMicrophoneAccess: () -> Unit,
    onAppPolicies: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Reliable Capture", style = MaterialTheme.typography.titleMedium)

        Button(onClick = onToggleCapture) {
            Text(if (captureState.mode == CaptureMode.RUNNING) "Pause all capture" else "Resume all capture")
        }

        HorizontalDivider()
        HealthRow("Notification access", access.notificationAccess, onNotificationAccess)
        HealthRow("Screen understanding", access.accessibilityAccess, onAccessibilityAccess)
        HealthRow("Usage access", access.usageAccess, onUsageAccess)
        HealthRow("Microphone", access.microphoneAccess, onMicrophoneAccess)

        HorizontalDivider()
        Text("Capture health", style = MaterialTheme.typography.titleMedium)
        Text("Notification listener: ${if (captureState.notificationListenerConnected) "connected" else "not connected"}")
        Text("Accessibility service: ${if (captureState.accessibilityConnected) "connected" else "not connected"}")
        Text("Last notification: ${formatInstant(captureState.lastNotificationAt)}")
        Text("Last screen memory: ${formatInstant(captureState.lastScreenMemoryAt)}")
        Text("Last app activity: ${formatInstant(captureState.lastAppActivityAt)}")
        Text("Sensitive credential/authenticator-style packages default to screen/OCR blocked and cloud AI is off by default.")
        OutlinedButton(onClick = onAppPolicies) { Text("App policies") }
    }
}

@Composable
private fun HealthRow(label: String, enabled: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(label)
            Text(if (enabled) "Enabled" else "Needs access", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(onClick = onOpen) { Text(if (enabled) "Review" else "Enable") }
    }
}

private fun formatInstant(value: Instant?): String = value?.let(TIME_FORMAT::format) ?: "—"
private val TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
