package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject

fun CaptureCommand.Notification.withEvidenceIntelligence(envelope: JSONObject): CaptureCommand.Notification {
    val root = RelayV2EvidenceBuilder.parseObject(metadataJson)
    val intelligence = JSONObject(envelope.toString())
    attachActionCapabilityGraph(
        intelligence = intelligence,
        actions = root.optJSONArray("relay_action_capabilities_v1") ?: JSONArray(),
    )
    root.put("relay_evidence_intelligence_v1", JSONObject(intelligence.toString()))
    val semantic = root.optJSONObject("relay_semantic_v2")
    semantic?.put("evidence_intelligence", JSONObject(intelligence.toString()))

    // Candidate5 adds the source-agnostic canonical EvidenceEnvelope while preserving every
    // existing V1/V2 field. Raw Android source facts remain a separate forensic record.
    val canonical = RelayEvidenceGatewayV1.attachNotification(this, root)
    markNotificationAttachmentAvailabilityUnverified(canonical)
    return copy(metadataJson = root.toString())
}

/** A Notification data URI proves a reference was observed, not that Relay can safely read bytes. */
private fun markNotificationAttachmentAvailabilityUnverified(envelope: JSONObject) {
    val attachments = envelope.optJSONArray("attachments") ?: return
    for (index in 0 until attachments.length()) {
        val item = attachments.optJSONObject(index) ?: continue
        if (item.optString("origin") != "NOTIFICATION_METADATA") continue
        val referencePresent = !item.isNull("content_uri") && item.optString("content_uri").isNotBlank()
        item.put("content_reference_present", referencePresent)
        item.put("content_available", false)
        item.put("content_availability", "UNVERIFIED")
    }
}

private fun attachActionCapabilityGraph(intelligence: JSONObject, actions: JSONArray) {
    if (actions.length() == 0) return
    val graph = intelligence.optJSONObject("graph") ?: return
    val nodes = graph.optJSONArray("nodes") ?: JSONArray().also { graph.put("nodes", it) }
    val edges = graph.optJSONArray("edges") ?: JSONArray().also { graph.put("edges", it) }
    val signalId = intelligence.optJSONObject("conversation_state")
        ?.optString("conversation_identity")
        ?.takeIf(String::isNotBlank)
        ?: "unknown"

    for (i in 0 until actions.length()) {
        val action = actions.optJSONObject(i) ?: continue
        val capabilityId = action.optString("capability_id").takeIf(String::isNotBlank) ?: continue
        nodes.put(JSONObject().apply {
            put("id", "action:$capabilityId")
            put("type", "ACTION_CAPABILITY")
            put("kind", action.optString("kind"))
            put("requires_input", action.optBoolean("requires_text_input"))
        })
        edges.put(JSONObject().apply {
            put("from", "conversation:$signalId")
            put("to", "action:$capabilityId")
            put("relation", "OFFERS_ACTION")
            put("confidence", 1.0)
            put("basis", action.optString("source", "Android Notification.Action evidence"))
        })
    }
    graph.put("live_action_capability_count", actions.length())
    graph.put(
        "action_execution_policy",
        "Discovery is evidence. Any execution still requires a live capability, an explicit Cortex request, and user authorization.",
    )
}