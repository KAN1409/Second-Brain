package com.kareem.secondbrain.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import com.kareem.secondbrain.core.model.Memory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TimelineScreen(
    memories: List<Memory>,
    captureState: CaptureState,
    onToggleCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Timeline", style = MaterialTheme.typography.headlineMedium)
                Text(if (captureState.mode == CaptureMode.RUNNING) "Capture active" else "Capture paused")
            }
            Button(onClick = onToggleCapture) {
                Text(if (captureState.mode == CaptureMode.RUNNING) "Pause" else "Resume")
            }
        }

        if (memories.isEmpty()) {
            Text("No memories yet. Enable capture access and use the phone normally.")
        } else {
            memories.take(12).forEach { memory -> MemoryRow(memory) }
        }
    }
}

@Composable
private fun MemoryRow(memory: Memory) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = listOfNotNull(memory.sourcePackage, TIME_FORMAT.format(memory.startedAt)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
            )
            memory.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(memory.body.take(220), maxLines = 4)
        }
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
