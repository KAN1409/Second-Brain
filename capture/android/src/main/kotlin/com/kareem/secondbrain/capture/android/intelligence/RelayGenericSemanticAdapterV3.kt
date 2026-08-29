package com.kareem.secondbrain.capture.android.intelligence

import org.json.JSONObject

/**
 * Feeds non-screen Android sensors into the same short-lived V3 episode/evidence graph without
 * inventing a second persistence model. The underlying observation primitive is intentionally the
 * same deterministic text/entity correlator used for Accessibility; the exported envelope identifies
 * the real sensor kind explicitly so Cortex never mistakes a Share/Voice/OCR observation for a screen.
 */
fun RelayIntelligenceV3.observeGenericEvidence(
    kind: String,
    sourcePackage: String?,
    text: String?,
    occurredAtEpochMs: Long,
    provenance: String,
    metadata: JSONObject? = null,
): JSONObject {
    val normalizedKind = kind.trim().uppercase().ifBlank { "OTHER" }
    val packageIdentity = sourcePackage?.trim()?.takeIf(String::isNotBlank)
        ?: "com.kareem.secondbrain.sensor.${normalizedKind.lowercase()}"
    val evidenceText = text?.trim()?.takeIf(String::isNotBlank)
        ?: "$normalizedKind evidence observed"

    val envelope = observeScreen(
        packageName = packageIdentity,
        accessibleText = evidenceText,
        className = "RELAY_GENERIC_$normalizedKind",
        eventType = -1,
        occurredAtEpochMs = occurredAtEpochMs,
    )
    envelope.remove("structured_screen_state")
    envelope.put("semantic_adapter", JSONObject().apply {
        put("kind", normalizedKind)
        put("source_package", sourcePackage ?: JSONObject.NULL)
        put("provenance", provenance)
        put("direct_android_evidence", true)
        put("text_present", !text.isNullOrBlank())
        put("personal_importance_scored", false)
    })
    envelope.put("generic_observation", JSONObject().apply {
        put("kind", normalizedKind)
        put("occurred_at", occurredAtEpochMs)
        put("source_package", sourcePackage ?: JSONObject.NULL)
        put("metadata", metadata ?: JSONObject())
    })
    return envelope
}
