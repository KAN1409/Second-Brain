package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONObject

fun CaptureCommand.Notification.withEvidenceIntelligence(envelope: JSONObject): CaptureCommand.Notification {
    val root = RelayV2EvidenceBuilder.parseObject(metadataJson)
    root.put("relay_evidence_intelligence_v1", JSONObject(envelope.toString()))
    val semantic = root.optJSONObject("relay_semantic_v2")
    semantic?.put("evidence_intelligence", JSONObject(envelope.toString()))
    return copy(metadataJson = root.toString())
}
