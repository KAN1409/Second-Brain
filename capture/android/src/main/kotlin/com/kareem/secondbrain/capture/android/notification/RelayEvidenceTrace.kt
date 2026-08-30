package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONObject

/** Add mechanical routing state without changing the evidence content that produced the decision. */
fun CaptureCommand.Notification.withRelayRoutingTrace(
    filterState: String,
    reason: String,
): CaptureCommand.Notification {
    val root = RelayV2EvidenceBuilder.parseObject(metadataJson)
    val envelope = root.optJSONObject("relay_evidence_envelope_v1") ?: return this
    val trace = envelope.optJSONObject("trace") ?: JSONObject().also { envelope.put("trace", it) }
    trace.put("routing_decision", filterState)
    trace.put("routing_reason", reason)
    trace.put(
        "delivery_state",
        if (filterState == "DROP_CONFIRMED_NOISE") "NOT_ELIGIBLE" else "ELIGIBLE_FOR_CORTEX",
    )
    trace.put("cortex_receipt", JSONObject.NULL)
    root.put("relay_evidence_envelope_v1", envelope)
    return copy(metadataJson = root.toString())
}