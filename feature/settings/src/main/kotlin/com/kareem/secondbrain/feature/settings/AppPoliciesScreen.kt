package com.kareem.secondbrain.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kareem.secondbrain.core.model.AppCapturePolicy

data class AppPolicyItem(
    val label: String,
    val policy: AppCapturePolicy,
)

@Composable
fun AppPoliciesScreen(
    items: List<AppPolicyItem>,
    onPolicyChanged: (AppCapturePolicy) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("App policies", style = MaterialTheme.typography.headlineMedium)
                Text("Cloud AI is off unless explicitly enabled.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.policy.packageName }) { item ->
                PolicyCard(item, onPolicyChanged)
            }
        }
    }
}

@Composable
private fun PolicyCard(item: AppPolicyItem, onPolicyChanged: (AppCapturePolicy) -> Unit) {
    val policy = item.policy
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleMedium)
            Text(policy.packageName, style = MaterialTheme.typography.labelSmall)
            PolicyToggle("Remember notifications", policy.notifications) {
                onPolicyChanged(policy.copy(notifications = it))
            }
            PolicyToggle("Remember screen content", policy.accessibility) {
                onPolicyChanged(policy.copy(accessibility = it))
            }
            PolicyToggle("Track application usage", policy.usage) {
                onPolicyChanged(policy.copy(usage = it))
            }
            PolicyToggle("Allow screenshot OCR", policy.ocr) {
                onPolicyChanged(policy.copy(ocr = it))
            }
            PolicyToggle("Allow cloud AI", policy.allowAiUpload) {
                onPolicyChanged(policy.copy(allowAiUpload = it))
            }
        }
    }
}

@Composable
private fun PolicyToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
