package com.kareem.secondbrain.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.kareem.secondbrain.core.model.SearchHit
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    onSearch: suspend (String) -> List<SearchHit>,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<SearchHit>()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Search your brain", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Local search over captured memories. Results stay available without cloud AI.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("What are you looking for?") },
        )
        Button(
            enabled = query.isNotBlank() && !searching,
            onClick = {
                scope.launch {
                    searching = true
                    error = null
                    runCatching { onSearch(query) }
                        .onSuccess { hits = it }
                        .onFailure { error = it.message ?: "Search failed" }
                    searching = false
                }
            },
        ) {
            Text("Search")
        }

        when {
            searching -> CircularProgressIndicator()
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            query.isNotBlank() && hits.isEmpty() -> Text("No matching memories found.")
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hits, key = { "${it.memoryId}:${it.chunkId}" }) { hit ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(hit.snippet, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Score ${"%.3f".format(hit.score)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
