package com.kareem.secondbrain.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    embeddingModelInstalled: Boolean,
    embeddingModelSizeBytes: Long,
    embeddingModelMessage: String?,
    geminiKeyConfigured: Boolean,
    geminiKeyMessage: String?,
    onToggleCapture: () -> Unit,
    onNotificationAccess: () -> Unit,
    onAccessibilityAccess: () -> Unit,
    onUsageAccess: () -> Unit,
    onMicrophoneAccess: () -> Unit,
    onAppPolicies: () -> Unit,
    onInstallEmbeddingModel: () -> Unit,
    onSaveGeminiApiKey: (String) -> Unit,
    onClearGeminiApiKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiKey by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
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

        HorizontalDivider()
        Text("Semantic search", style = MaterialTheme.typography.titleMedium)
        Text(
            if (embeddingModelInstalled) {
                "EmbeddingGemma installed (${formatBytes(embeddingModelSizeBytes)}). Hybrid semantic search is enabled and warms gradually."
            } else {
                "Embedding model not installed. Search remains fully usable with local lexical ranking."
            },
        )
        embeddingModelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!embeddingModelInstalled) {
            Button(onClick = onInstallEmbeddingModel) { Text("Install EmbeddingGemma model") }
        }

        HorizontalDivider()
        Text("Ask My Brain • Gemini BYOK", style = MaterialTheme.typography.titleMedium)
        Text(
            if (geminiKeyConfigured) "Gemini key configured. Cloud synthesis can use only memories whose app policy explicitly allows AI upload."
            else "No Gemini key configured. Ask remains evidence-only and does not upload app memories.",
        )
        Text(
            "Personal/sideload BYOK only. A key stored on a phone is encrypted at rest with Android Keystore, but a determined attacker with runtime access may still extract it. Production deployments should proxy Gemini through a backend.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Cloud upload stays off per app until enabled in App policies. Gemini free-tier data handling may differ from paid tiers, so review Google's current terms before enabling cloud upload.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Gemini API key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = apiKey.isNotBlank(),
                onClick = {
                    onSaveGeminiApiKey(apiKey)
                    apiKey = ""
                },
            ) { Text("Save key") }
            if (geminiKeyConfigured) {
                OutlinedButton(onClick = onClearGeminiApiKey) { Text("Remove key") }
            }
        }
        geminiKeyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("The key is encrypted at rest with Android Keystore and is never committed to the project.", style = MaterialTheme.typography.bodySmall)
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
