package com.kareem.secondbrain.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CaptureScreen(
    onAddNote: (String) -> Unit,
    onAddLink: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var note by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Capture", style = MaterialTheme.typography.headlineMedium)
        }

        Text("Voice memory", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                if (recording) onStopVoice() else onStartVoice()
                recording = !recording
            },
        ) {
            Text(if (recording) "Stop recording" else "Start recording")
        }
        if (recording) Text("Recording is active and visible in Android notifications.")

        Text("Import", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPickImage) { Text("Image") }
            OutlinedButton(onClick = onPickFile) { Text("File") }
        }

        Text("Note", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Remember this") },
        )
        Button(
            enabled = note.isNotBlank(),
            onClick = { onAddNote(note.trim()); note = "" },
        ) { Text("Save note") }

        Text("Link", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("https://…") },
            singleLine = true,
        )
        Button(
            enabled = link.isNotBlank(),
            onClick = { onAddLink(link.trim()); link = "" },
        ) { Text("Save link") }
    }
}
