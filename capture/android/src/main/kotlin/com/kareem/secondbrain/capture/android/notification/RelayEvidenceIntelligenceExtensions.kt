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
    return copy(metadataJson = root.toString())
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
    graph.put("action_execution_policy", "Cortex may propose; Relay revalidates a still-live capability and executes only an explicit request")
}
