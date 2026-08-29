package com.kareem.secondbrain.capture.android.connector

import com.kareem.secondbrain.capture.android.notification.RelayActionRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional v2 wire contract. It is used only after Cortex explicitly selects CORTEX_SIGNAL_V2 in
 * the Local Bus hello ACK; otherwise Relay continues sending the already validated V1 payload.
 */
object RelayV2Protocol {
    const val SIGNAL_PROTOCOL = "CORTEX_SIGNAL_V2"
    const val V1_PROTOCOL = "CORTEX_INGEST_V1"
    const val ACTION_BRIDGE = "ACTION_BRIDGE_V1"
    const val POLICY_FEEDBACK = "POLICY_FEEDBACK_V1"
    const val REPLAY_DIAGNOSTICS = "REPLAY_DIAGNOSTICS_V1"
    const val EVIDENCE_INTELLIGENCE = "EVIDENCE_INTELLIGENCE_V1"

    const val MSG_INGEST_V2 = 20
    const val MSG_ACTION_REQUEST = 200
    const val MSG_POLICY_UPDATE = 201
    const val MSG_ACTION_RESULT = 202
    const val MSG_POLICY_RESULT = 203

    const val KEY_SELECTED_PROTOCOL = "selected_protocol"
    const val KEY_REQUEST_JSON = "request_json"
    const val KEY_RESULT_JSON = "result_json"

    fun advertisedCapabilities(): JSONArray = JSONArray().apply {
        put("NOTIFICATIONS")
        put(SIGNAL_PROTOCOL)
        put(ACTION_BRIDGE)
        put(POLICY_FEEDBACK)
        put(REPLAY_DIAGNOSTICS)
        put(EVIDENCE_INTELLIGENCE)
    }

    /** Convert a durable V1 notification event into a v2 envelope without changing event identity. */
    fun fromV1(v1: JSONObject): JSONObject {
        val metadata = v1.optJSONObject("metadata") ?: JSONObject()
        val semantic = metadata.optJSONObject("relay_semantic_v2") ?: JSONObject().apply {
            put("schema", "CORTEX_RELAY_SEMANTIC_V2")
            put("source_type", v1.optString("source_type", "NOTIFICATION"))
            put("source_package", v1.optString("source_package"))
            put("content", JSONObject().apply {
                put("title", v1.optString("title"))
                put("text", v1.optString("text"))
                put("expanded_text", v1.optString("expanded_text"))
                put("conversation_title", v1.optString("conversation_title"))
                put("messages", v1.optJSONArray("messages") ?: JSONArray())
            })
        }
        return JSONObject().apply {
            put("protocol", SIGNAL_PROTOCOL)
            put("schema", "CORTEX_RELAY_SIGNAL_V2")
            put("event_id", v1.optString("event_id"))
            put("connector_id", v1.optString("connector_id", "second_brain"))
            put("occurred_at", v1.optLong("occurred_at"))
            put("source", JSONObject().apply {
                put("type", v1.optString("source_type", "NOTIFICATION"))
                put("package", v1.optString("source_package"))
                put("notification_key", v1.optString("notification_key"))
            })
            put("semantic", JSONObject(semantic.toString()))
            put("action_capabilities", metadata.optJSONArray("relay_action_capabilities_v1") ?: JSONArray())
            metadata.optJSONObject("relay_evidence_intelligence_v1")?.let {
                put("evidence_intelligence", JSONObject(it.toString()))
            }
            put("compatibility", JSONObject().apply {
                put("v1_protocol", V1_PROTOCOL)
                put("v1_event_id", v1.optString("event_id"))
            })
        }
    }

    fun parseActionRequest(raw: String): RelayActionRequest {
        val json = JSONObject(raw)
        return RelayActionRequest(
            requestId = json.getString("request_id"),
            logicalSignalId = json.getString("logical_signal_id"),
            capabilityId = json.getString("capability_id"),
            inputText = if (json.has("input_text") && !json.isNull("input_text")) json.getString("input_text") else null,
        )
    }

    fun policyResultJson(result: RelayPolicyUpdateResult): JSONObject = JSONObject().apply {
        put("status", result.status)
        put("accepted", result.accepted)
        put("detail", result.detail)
        put("policy_version", result.policy.version)
        put("disabled_noise_rules", JSONArray().apply { result.policy.disabledNoiseRules.map { it.name }.sorted().forEach(::put) })
        put("forensic_retention_hours", result.policy.forensicRetentionHours)
    }
}
