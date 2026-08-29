package com.kareem.secondbrain.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kareem.secondbrain.capture.android.connector.RelayDiagnosticSnapshot
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestReport
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestStatus
import com.kareem.secondbrain.capture.android.connector.RelayV2OperationalMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.format.DateTimeFormatter

internal object RelaySystemTestExporter {
    fun share(context: Context, report: RelaySystemTestReport, diagnostics: RelayDiagnosticSnapshot) {
        val directory = File(context.cacheDir, "relay-diagnostics").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(report.finishedAt)
        val file = File(directory, "CortexRelayFullSystemTest_$stamp.json")
        file.writeText(build(report, diagnostics).toString(2), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cortex Relay Full System Test")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share Cortex Relay full system test").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun build(report: RelaySystemTestReport, diagnostics: RelayDiagnosticSnapshot) = JSONObject().apply {
        val v2 = RelayV2OperationalMetrics.snapshot()
        put("schema", report.schema)
        put("run_id", report.runId)
        put("started_at", report.startedAt.toString())
        put("finished_at", report.finishedAt.toString())
        put("overall_status", report.overallStatus)
        put("app", JSONObject().apply {
            put("package", report.packageName)
            put("version_name", report.versionName)
            put("version_code", report.versionCode)
        })
        put("device", JSONObject().apply {
            put("manufacturer", report.manufacturer)
            put("model", report.model)
            put("sdk_int", report.sdkInt)
        })
        put("counts", JSONObject().apply {
            RelaySystemTestStatus.entries.forEach { status -> put(status.name, report.counts[status] ?: 0) }
        })
        put("tests", JSONArray().apply {
            report.cases.forEach { test ->
                put(JSONObject().apply {
                    put("id", test.id)
                    put("area", test.area)
                    put("status", test.status.name)
                    put("summary", test.summary)
                    put("detail", test.detail ?: JSONObject.NULL)
                    put("duration_ms", test.durationMs)
                })
            }
        })
        put("runtime_health", JSONObject().apply {
            put("connection_state", diagnostics.connectionState.name)
            put("captured", diagnostics.captured)
            put("send_attempts", diagnostics.sent)
            put("delivered_cortex_ack", diagnostics.forwarded)
            put("rejected_by_cortex", diagnostics.rejected)
            put("filtered", diagnostics.filtered)
            put("waiting_or_in_flight", diagnostics.waiting)
            put("delivery_issue_incidents", diagnostics.failedRetries)
            put("last_cortex_status", diagnostics.lastCortexStatus ?: JSONObject.NULL)
            put("last_cortex_signal_id", diagnostics.lastCortexSignalId)
            put("last_ack_at", diagnostics.lastAckAt?.toString() ?: JSONObject.NULL)
            put("last_error", diagnostics.lastError ?: JSONObject.NULL)
            put("lifecycle", JSONObject().apply {
                put("posted", diagnostics.lifecyclePosted)
                put("updated", diagnostics.lifecycleUpdated)
                put("removed", diagnostics.lifecycleRemoved)
                put("exact_duplicate_updates_suppressed", diagnostics.duplicateUpdatesSuppressed)
                put("machine_churn_updates_suppressed", diagnostics.machineChurnSuppressed)
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
            put("recent_signal_evidence", JSONArray().apply {
                diagnostics.recentSignals.take(20).forEach { signal ->
                    put(JSONObject().apply {
                        put("wire_event_id", "sb_${signal.eventId}")
                        put("source_package", signal.packageName)
                        put("logical_signal_id", signal.logicalSignalId ?: JSONObject.NULL)
                        put("source_profile_identity", signal.sourceProfileIdentity ?: JSONObject.NULL)
                        put("conversation_identity", signal.conversationIdentity ?: JSONObject.NULL)
                        put("lifecycle_state", signal.lifecycleState ?: JSONObject.NULL)
                        put("update_sequence", signal.updateSequence ?: JSONObject.NULL)
                        put("signal_type", signal.signalType ?: JSONObject.NULL)
                        put("filter_state", signal.filterState?.name ?: JSONObject.NULL)
                        put("delivery_state", signal.deliveryState.name)
                        put("send_attempts", signal.sendAttempts)
                        put("delivery_issue_incidents", signal.deliveryIssueIncidents)
                        put("cortex_status", signal.cortexStatus ?: JSONObject.NULL)
                        put("cortex_signal_id", signal.cortexSignalId)
                    })
                }
            })
        })
        put("review_guidance", JSONObject().apply {
            put("candidate_blockers", "Any FAIL or NOT_IMPLEMENTED blocks a Cortex Relay v2 device candidate.")
            put("real_event_rule", "NEEDS_REAL_EVENT is not a synthetic failure; it must map to an explicit guided real-device acceptance step.")
            put("privacy", "Notification title/body/message content is omitted from this full-system report by default.")
        })
    }
}
