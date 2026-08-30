package com.kareem.secondbrain.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kareem.secondbrain.capture.android.connector.CortexConnectorClient
import com.kareem.secondbrain.capture.android.connector.RelayDiagnosticSnapshot
import com.kareem.secondbrain.capture.android.connector.RelayV2OperationalMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Shareable sanitized diagnostic for joining Relay state to Cortex by sb_<eventId>. */
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
        val v2 = RelayV2OperationalMetrics.snapshot()
        put("schema", "CORTEX_RELAY_DIAGNOSTIC_V2")
        put("generated_at", Instant.now().toString())
        put("package", context.packageName)
        put("app_label", "Cortex Relay")
        put("protocol", CortexConnectorClient.negotiatedProtocol())
        put("v1_fallback_protocol", "CORTEX_INGEST_V1")
        put("connector_id", "second_brain")
        put("connection_state", s.connectionState.name)
        put("captured", s.captured)
        put("sent", s.sent)
        put("send_attempts", s.sent)
        put("delivered_cortex_ack", s.forwarded)
        put("rejected_by_cortex", s.rejected)
        put("filtered", s.filtered)
        put("low_value_forwarded", s.lowValueForwarded)
        put("waiting_or_in_flight", s.waiting)
        put("restored_pending_event_ids", JSONArray().apply { s.restoredPendingEventIds.forEach { put("sb_$it") } })
        put("failed_or_retry_events", s.failedRetries)
        put("delivery_issue_incidents", s.failedRetries)
        put("lifecycle", JSONObject().apply {
            put("posted", s.lifecyclePosted)
            put("updated", s.lifecycleUpdated)
            put("removed", s.lifecycleRemoved)
            put("exact_duplicate_updates_suppressed", s.duplicateUpdatesSuppressed)
            put("machine_churn_updates_suppressed", s.machineChurnSuppressed)
        })
        put("v2_observability", JSONObject().apply {
            put("negotiated_protocol", v2.negotiatedProtocol)
            put("protocol_negotiated_at", v2.protocolNegotiatedAt?.toString() ?: JSONObject.NULL)
            put("last_ack_latency_ms", v2.lastAckLatencyMs ?: JSONObject.NULL)
            put("average_ack_latency_ms", v2.averageAckLatencyMs ?: JSONObject.NULL)
            put("max_ack_latency_ms", v2.maxAckLatencyMs ?: JSONObject.NULL)
            put("ack_latency_samples", v2.ackLatencySamples)
            put("outbox_count", v2.outboxCount)
            put("oldest_pending_age_ms", v2.oldestPendingAgeMs ?: JSONObject.NULL)
            put("forensic_record_count", v2.forensicRecordCount)
            put("forensic_bytes", v2.forensicBytes)
            put("replay_runs", v2.replayRuns)
            put("replay_failures", v2.replayFailures)
            put("policy_version", v2.policyVersion)
            put("action_requests", v2.actionRequests)
            put("action_succeeded", v2.actionSucceeded)
            put("action_failed", v2.actionFailed)
            put("last_successful_delivery_at", v2.lastSuccessfulDeliveryAt?.toString() ?: JSONObject.NULL)
            put("last_action_at", v2.lastActionAt?.toString() ?: JSONObject.NULL)
        })
        put("counter_semantics", JSONObject().apply {
            put("captured", "Locally stored meaningful notification evidence events. Incremented once per stored event.")
            put("send_attempts", "Actual Messenger.send() ingest attempts. Retries can make this greater than captured.")
            put("delivered_cortex_ack", "Events accepted only after a correlated Cortex ACK for the same event_id.")
            put("delivery_issue_incidents", "Retry/failure incidents, not unique events; one event can increment this more than once.")
            put("restored_pending_event_ids", "Exact event ids recovered from the durable outbox when this Relay process started.")
        })
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
                    put("logical_signal_id", signal.logicalSignalId ?: JSONObject.NULL)
                    put("source_profile_identity", signal.sourceProfileIdentity ?: JSONObject.NULL)
                    put("notification_identity", signal.notificationIdentity ?: JSONObject.NULL)
                    put("notification_instance_identity", signal.notificationInstanceIdentity ?: JSONObject.NULL)
                    put("conversation_identity", signal.conversationIdentity ?: JSONObject.NULL)
                    put("conversation_identity_basis", signal.conversationIdentityBasis ?: JSONObject.NULL)
                    put("lifecycle_state", signal.lifecycleState ?: JSONObject.NULL)
                    put("update_sequence", signal.updateSequence ?: JSONObject.NULL)
                    put("signal_type", signal.signalType ?: JSONObject.NULL)
                    put("occurred_at", signal.occurredAt.toString())
                    put("captured_at", signal.capturedAt.toString())
                    put("updated_at", signal.updatedAt.toString())
                    put("source_package", signal.packageName)
                    put("filter_state", signal.filterState?.name ?: JSONObject.NULL)
                    put("filter_reason", signal.filterReason ?: JSONObject.NULL)
                    put("delivery_state", signal.deliveryState.name)
                    put("delivery_detail", signal.deliveryDetail)
                    put("send_attempts", signal.sendAttempts)
                    put("delivery_issue_incidents", signal.deliveryIssueIncidents)
                    put("cortex_status", signal.cortexStatus ?: JSONObject.NULL)
                    put("cortex_signal_id", signal.cortexSignalId)
                })
            }
        })
    }
}
