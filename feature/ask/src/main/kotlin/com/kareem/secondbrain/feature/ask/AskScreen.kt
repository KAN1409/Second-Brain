package com.kareem.secondbrain.feature.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kareem.secondbrain.domain.AskAnswer
import com.kareem.secondbrain.domain.AskEvidence
import kotlinx.coroutines.launch

@Composable
fun AskScreen(
    onAsk: suspend (String) -> AskAnswer,
    modifier: Modifier = Modifier,
) {
    var question by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AskAnswer?>(null) }
    var asking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ask your brain", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Answers are built from retrieved memories. Unsupported claims are removed before you see them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask about something you saw, heard, or saved") },
            minLines = 2,
            maxLines = 4,
        )
        Button(
            enabled = question.isNotBlank() && !asking,
            onClick = {
                scope.launch {
                    asking = true
                    error = null
                    runCatching { onAsk(question) }
                        .onSuccess { result = it }
                        .onFailure { error = it.message ?: "Ask failed" }
                    asking = false
                }
            },
        ) { Text("Ask") }

        when {
            asking -> CircularProgressIndicator()
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            result != null -> AskResult(result = result!!, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AskResult(result: AskAnswer, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(result.answer, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Mode: ${result.synthesisMode.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
                        Text("Confidence: ${"%.0f".format(result.confidence * 100)}%", style = MaterialTheme.typography.labelMedium)
                    }
                    result.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (result.insufficientEvidence) {
                        Text("Evidence is insufficient for a supported answer.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (result.claims.isNotEmpty()) {
            item { Text("Supported claims", style = MaterialTheme.typography.titleMedium) }
            items(result.claims) { claim ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(claim.text)
                        Text("Evidence: ${claim.evidenceIds.joinToString()}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item { Text("Evidence", style = MaterialTheme.typography.titleMedium) }
        items(result.evidence, key = AskEvidence::id) { evidence ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("${evidence.id} • ${evidence.sourceLabel}", style = MaterialTheme.typography.labelLarge)
                    Text(evidence.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${evidence.occurredAt} • retrieval ${"%.3f".format(evidence.retrievalScore)}${if (evidence.cloudEligible) " • cloud allowed" else " • local only"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
