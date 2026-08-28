package com.kareem.secondbrain.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kareem.secondbrain.capture.android.connector.RelayDiagnosticSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Shareable process diagnostic for joining Relay state to Cortex by sb_<eventId>. */
internal object RelayDiagnosticExporter {
    fun share(context: Context, snapshot: RelayDiagnosticSnapshot) {
        val directory = File(context.cacheDir, "relay-diagnostics").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(java.time.ZoneId.systemDefault()).format(Instant.now())
        val file = File(directory, "CortexRelayDiagnostic_$stamp.json")
        file.writeText(build(context, snapshot).toString(2), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share Cortex Relay diagnostic").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun build(context: Context, s: RelayDiagnosticSnapshot): JSONObject = JSONObject().apply {
        put("schema", "CORTEX_RELAY_DIAGNOSTIC_V1")
        put("generated_at", Instant.now().toString())
        put("package", context.packageName)
        put("app_label", "Cortex Relay")
        put("protocol", "CORTEX_INGEST_V1")
        put("connector_id", "second_brain")
        put("connection_state", s.connectionState.name)
        put("captured", s.captured)
        put("sent", s.sent)
        put("delivered_cortex_ack", s.forwarded)
        put("rejected_by_cortex", s.rejected)
        put("filtered", s.filtered)
        put("low_value_forwarded", s.lowValueForwarded)
        put("waiting_or_in_flight", s.waiting)
        put("failed_or_retry_events", s.failedRetries)
        put("last_package", s.lastPackage ?: JSONObject.NULL)
        put("last_filter_state", s.lastFilterState?.name ?: JSONObject.NULL)
        put("last_filter_reason", s.lastFilterReason ?: JSONObject.NULL)
        put("last_error", s.lastError ?: JSONObject.NULL)
        put("last_cortex_status", s.lastCortexStatus ?: JSONObject.NULL)
        put("last_cortex_signal_id", s.lastCortexSignalId)
        put("last_ack_at", s.lastAckAt?.toString() ?: JSONObject.NULL)
        put("last_activity_at", s.lastActivityAt?.toString() ?: JSONObject.NULL)
        put("recent_signals", JSONArray().apply {
            s.recentSignals.forEach { signal ->
                put(JSONObject().apply {
                    put("event_id", signal.eventId)
                    put("wire_event_id", "sb_${signal.eventId}")
                    put("occurred_at", signal.occurredAt.toString())
                    put("captured_at", signal.capturedAt.toString())
                    put("updated_at", signal.updatedAt.toString())
                    put("source_package", signal.packageName)
                    // Diagnostic export intentionally omits notification title/body preview. Cortex
                    // can correlate delivery using wire_event_id without duplicating message content.
                    put("filter_state", signal.filterState?.name ?: JSONObject.NULL)
                    put("filter_reason", signal.filterReason ?: JSONObject.NULL)
                    put("delivery_state", signal.deliveryState.name)
                    put("delivery_detail", signal.deliveryDetail)
                    put("cortex_status", signal.cortexStatus ?: JSONObject.NULL)
                    put("cortex_signal_id", signal.cortexSignalId)
                })
            }
        })
    }
}
