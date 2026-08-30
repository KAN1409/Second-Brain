package com.kareem.secondbrain.capture.android.notification

import android.net.Uri
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Canonical Relay evidence contract.
 *
 * Raw Android source facts remain separate from the normalized envelope. The normalized layer may
 * describe media, continuity, deltas and currently discoverable Android capabilities, but it never
 * assigns personal importance, relationship meaning or long-term memory semantics.
 */
object RelayEvidenceGatewayV1 {
    const val SCHEMA = "CORTEX_RELAY_EVIDENCE_ENVELOPE_V1"
    const val RAW_SCHEMA = "CORTEX_RELAY_RAW_SOURCE_V1"
    const val CAPABILITY_SCHEMA = "CORTEX_RELAY_CAPABILITY_DISCOVERY_V1"

    private val urlRegex = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

    data class HandoffAttachment(
        val attachmentId: String,
        val kind: String,
        val mimeType: String?,
        val displayName: String?,
        val sizeBytes: Long?,
        val sha256: String?,
        val contentAvailable: Boolean,
        val storageRef: String?,
        val origin: String,
        val originalUriProvenance: String?,
    )

    /** Attach the canonical notification envelope after existing Relay V2 enrichment. */
    fun attachNotification(
        command: CaptureCommand.Notification,
        root: JSONObject,
    ): JSONObject {
        val normalization = root.optJSONObject("relay_normalization") ?: JSONObject()
        val semantic = root.optJSONObject("relay_semantic_v2") ?: JSONObject()
        val androidRaw = rawAndroidPayload(root)
        val notificationIdentity = normalization.optString("notification_identity")
            .takeIf(String::isNotBlank)
            ?: stableId("notification", command.packageName, command.notificationKey)
        val conversationIdentity = normalization.optString("conversation_identity")
            .takeIf(String::isNotBlank)
            ?: stableId("conversation", command.packageName, command.conversationTitle.orEmpty(), command.title.orEmpty())
        val generation = normalization.optInt("lifecycle_generation", 1).coerceAtLeast(1)
        val revision = normalization.optInt("update_sequence", 0).coerceAtLeast(0)
        val evidenceId = evidenceId(notificationIdentity, generation, revision)
        val previousEvidenceId = if (revision > 0) evidenceId(notificationIdentity, generation, revision - 1) else null
        val rawId = stableId("raw", notificationIdentity, generation.toString(), revision.toString())
        val rawRef = "metadata:relay_raw_source_v1:$rawId"
        val observedAt = command.occurredAt.toEpochMilli()
        val attachments = notificationAttachments(command, androidRaw, evidenceId)
        val actions = root.optJSONArray("relay_action_capabilities_v1") ?: JSONArray()
        val fields = notificationFields(command)
        val change = normalization.optString("meaningful_change", "NEW_POST")
        val payloadHash = sha256(
            listOfNotNull(
                command.title,
                command.body,
                command.expandedText,
                command.conversationTitle,
                command.messages.joinToString("\n") { "${it.sender.orEmpty()}:${it.text}" },
                attachments.toString(),
            ).joinToString("\n"),
        )

        val rawRecord = JSONObject().apply {
            put("schema", RAW_SCHEMA)
            put("raw_id", rawId)
            put("adapter", "ANDROID_NOTIFICATION")
            put("mechanism", "NOTIFICATION_LISTENER")
            put("source_package", command.packageName)
            put("observed_at", observedAt)
            put("payload", JSONObject(androidRaw.toString()))
        }

        val envelope = JSONObject().apply {
            put("schema", SCHEMA)
            put("schema_version", 1)
            put("evidence_id", evidenceId)
            put("source", JSONObject().apply {
                put("adapter", "ANDROID_NOTIFICATION")
                put("package", command.packageName)
                put("mechanism", "NOTIFICATION_LISTENER")
            })
            put("observed_at", observedAt)
            put("entity_key", notificationIdentity)
            put("thread_key", conversationIdentity)
            put("revision", revision)
            putNullable("previous_evidence_id", previousEvidenceId)
            put("event_type", eventType(change))
            put("payload_hash", payloadHash)
            put("fields", fields)
            put("attachments", attachments)
            put("capabilities", capabilityDiscovery(actions, evidenceId))
            put("delta", deltaJson(change, normalization, previousEvidenceId, fields, attachments))
            put("privacy_mode", root.optString("relay_privacy_mode", "CONTENT"))
            put("raw_ref", rawRef)
            put("trace", JSONObject().apply {
                put("raw_captured", true)
                put("normalized", true)
                put("thread_resolved", conversationIdentity.isNotBlank())
                put("delta_assessed", true)
                put("attachment_inspected", true)
                put("capability_discovered", actions.length() > 0)
                put("routing_decision", JSONObject.NULL)
                put("delivery_state", JSONObject.NULL)
                put("cortex_receipt", JSONObject.NULL)
            })
            semantic.optJSONObject("conversation_continuity")?.let {
                put("conversation_continuity", JSONObject(it.toString()))
            }
            root.optJSONObject("relay_evidence_intelligence_v1")?.optJSONObject("evidence_quality")?.let {
                put("evidence_quality", JSONObject(it.toString()))
            }
        }

        root.put("relay_raw_source_v1", rawRecord)
        root.put("relay_evidence_envelope_v1", envelope)
        return envelope
    }

    /** Build canonical metadata for deliberate Android Shares after bytes have been ingested privately. */
    fun buildShareMetadata(
        shareId: String,
        sourcePackage: String?,
        observedAtEpochMs: Long,
        action: String,
        text: String?,
        attachments: List<HandoffAttachment>,
        referrerProvenance: String?,
    ): JSONObject {
        val evidenceId = stableId("evidence-share", shareId)
        val rawId = stableId("raw-share", shareId)
        val rawRef = "metadata:relay_raw_source_v1:$rawId"
        val attachmentJson = JSONArray().apply { attachments.forEach { put(handoffAttachmentJson(it)) } }
        val fields = JSONObject().apply {
            put("text", field(text, "ANDROID_SHARE_EXTRA_TEXT"))
        }
        val raw = JSONObject().apply {
            put("schema", RAW_SCHEMA)
            put("raw_id", rawId)
            put("adapter", "ANDROID_SHARE")
            put("mechanism", action)
            putNullable("source_package", sourcePackage)
            putNullable("referrer_provenance", referrerProvenance)
            put("observed_at", observedAtEpochMs)
            putNullable("text", text)
            put("attachments", JSONArray(attachmentJson.toString()))
        }
        val envelope = JSONObject().apply {
            put("schema", SCHEMA)
            put("schema_version", 1)
            put("evidence_id", evidenceId)
            put("source", JSONObject().apply {
                put("adapter", "ANDROID_SHARE")
                putNullable("package", sourcePackage)
                put("mechanism", action)
            })
            put("observed_at", observedAtEpochMs)
            put("entity_key", "share:$shareId")
            put("thread_key", JSONObject.NULL)
            put("revision", 0)
            put("previous_evidence_id", JSONObject.NULL)
            put("event_type", "CREATED")
            put("payload_hash", sha256(listOf(text.orEmpty(), attachmentJson.toString()).joinToString("\n")))
            put("fields", fields)
            put("attachments", attachmentJson)
            put("capabilities", JSONArray())
            put("delta", JSONObject().apply {
                put("change", "NEW_SHARE")
                put("previous_evidence_id", JSONObject.NULL)
                put("changed_fields", JSONArray().put("text").put("attachments"))
            })
            put("privacy_mode", "CONTENT")
            put("raw_ref", rawRef)
            put("trace", JSONObject().apply {
                put("raw_captured", true)
                put("normalized", true)
                put("thread_resolved", false)
                put("delta_assessed", true)
                put("attachment_inspected", true)
                put("capability_discovered", false)
                put("routing_decision", "USER_INITIATED_SHARE")
                put("delivery_state", JSONObject.NULL)
                put("cortex_receipt", JSONObject.NULL)
            })
        }
        val semantic = JSONObject().apply {
            put("schema", "CORTEX_RELAY_SEMANTIC_V2")
            put("source_type", "SHARE")
            putNullable("source_package", sourcePackage)
            put("signal_type", "USER_SHARE")
            put("occurred_at", observedAtEpochMs)
            put("content", JSONObject().apply {
                putNullable("text", text)
                put("attachments", JSONArray(attachmentJson.toString()))
            })
            put("attachments", JSONArray(attachmentJson.toString()))
            put("provenance", JSONArray().apply {
                if (!text.isNullOrBlank()) put(provenanceItem("text", "Android ACTION_SEND/EXTRA_TEXT"))
                if (attachments.isNotEmpty()) put(provenanceItem("attachments", "Android ACTION_SEND content URI imported into app-private AssetRepository"))
            })
        }
        return JSONObject().apply {
            put("relay_raw_source_v1", raw)
            put("relay_evidence_envelope_v1", envelope)
            put("relay_semantic_v2", semantic)
            put("relay_action_capabilities_v1", JSONArray())
        }
    }

    fun inferAttachmentKind(mimeType: String?, displayName: String?, textHint: String? = null): String {
        val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
        val name = displayName.orEmpty().lowercase(Locale.ROOT)
        val text = textHint.orEmpty().lowercase(Locale.ROOT)
        val voiceHint = containsAny(text, "voice note", "voice message", "audio message", "رسالة صوتية", "مقطع صوتي")
        return when {
            voiceHint && mime.startsWith("audio/") -> "VOICE_NOTE"
            mime.startsWith("image/") -> if (mime.contains("webp") && containsAny(text, "sticker", "ملصق")) "STICKER" else "IMAGE"
            mime.startsWith("video/") -> "VIDEO"
            mime.startsWith("audio/") -> if (voiceHint) "VOICE_NOTE" else "AUDIO"
            mime == "application/pdf" || name.endsWith(".pdf") -> "PDF"
            mime.contains("vcard") || mime.contains("contact") || name.endsWith(".vcf") -> "CONTACT"
            mime.contains("location") || mime == "application/vnd.google-earth.kml+xml" -> "LOCATION"
            mime.isNotBlank() && mime != "application/octet-stream" -> "FILE"
            name.endsWith(".pdf") -> "PDF"
            voiceHint -> "VOICE_NOTE"
            containsAny(text, "sent a photo", "sent an image", "photo", "image", "صورة") -> "IMAGE"
            containsAny(text, "sent a video", "video", "فيديو") -> "VIDEO"
            containsAny(text, "sticker", "ملصق") -> "STICKER"
            containsAny(text, "shared location", "location", "موقع") -> "LOCATION"
            containsAny(text, "shared contact", "contact", "vcard", "جهة اتصال") -> "CONTACT"
            containsAny(text, "pdf") -> "PDF"
            containsAny(text, "audio") -> "AUDIO"
            containsAny(text, "document", "file", "ملف") -> "FILE"
            else -> "UNKNOWN"
        }
    }

    private fun notificationAttachments(
        command: CaptureCommand.Notification,
        androidRaw: JSONObject,
        evidenceId: String,
    ): JSONArray {
        val out = JSONArray()
        val visibleHint = listOfNotNull(
            command.title,
            command.body,
            command.expandedText,
            command.conversationTitle,
            command.messages.joinToString(" ") { it.text },
        ).joinToString(" ")
        val messages = androidRaw.optJSONArray("messages")
        if (messages != null) {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val mime = message.optString("dataMimeType").takeIf { it.isNotBlank() && it != "null" }
                val uri = message.optString("dataUri").takeIf { it.isNotBlank() && it != "null" }
                if (mime == null && uri == null) continue
                val kind = inferAttachmentKind(mime, null, visibleHint)
                out.put(JSONObject().apply {
                    put("attachment_id", stableId("attachment", evidenceId, index.toString(), kind, mime.orEmpty(), uri.orEmpty()))
                    put("kind", kind)
                    putNullable("mime_type", mime)
                    put("name", JSONObject.NULL)
                    put("size_bytes", JSONObject.NULL)
                    put("sha256", JSONObject.NULL)
                    put("content_available", uri != null)
                    put("origin", "NOTIFICATION_METADATA")
                    putNullable("content_uri", uri)
                    putNullable("original_uri_provenance", uri?.let(::uriProvenance))
                })
            }
        }

        val inferredKinds = linkedSetOf<String>()
        val lower = visibleHint.lowercase(Locale.ROOT)
        listOf(
            "VOICE_NOTE" to listOf("voice note", "voice message", "audio message", "رسالة صوتية", "مقطع صوتي"),
            "STICKER" to listOf("sticker", "ملصق"),
            "VIDEO" to listOf("sent a video", "video", "فيديو"),
            "IMAGE" to listOf("sent a photo", "sent an image", "photo", "image", "صورة"),
            "LOCATION" to listOf("shared location", "location", "موقع"),
            "CONTACT" to listOf("shared contact", "contact", "vcard", "جهة اتصال"),
            "PDF" to listOf("pdf"),
        ).forEach { (kind, tokens) -> if (tokens.any(lower::contains)) inferredKinds += kind }
        if (urlRegex.containsMatchIn(visibleHint)) inferredKinds += "LINK"

        val existingKinds = mutableSetOf<String>()
        for (index in 0 until out.length()) out.optJSONObject(index)?.optString("kind")?.let(existingKinds::add)
        inferredKinds.filterNot(existingKinds::contains).forEachIndexed { index, kind ->
            out.put(JSONObject().apply {
                put("attachment_id", stableId("attachment-hint", evidenceId, kind, index.toString()))
                put("kind", kind)
                put("mime_type", JSONObject.NULL)
                put("name", JSONObject.NULL)
                put("size_bytes", JSONObject.NULL)
                put("sha256", JSONObject.NULL)
                put("content_available", false)
                put("origin", "NOTIFICATION_TEXT_HINT")
                put("content_uri", JSONObject.NULL)
                put("original_uri_provenance", JSONObject.NULL)
            })
        }
        return out
    }

    private fun notificationFields(command: CaptureCommand.Notification): JSONObject = JSONObject().apply {
        put("sender", field(command.messages.lastOrNull()?.sender ?: command.title, "NOTIFICATION_EXTRA_OR_MESSAGING_STYLE_SENDER"))
        put("title", field(command.title, "NOTIFICATION_EXTRA_TITLE"))
        put("text", field(command.body, "NOTIFICATION_EXTRA_TEXT"))
        put("expanded_text", field(command.expandedText, "NOTIFICATION_EXTRA_BIG_TEXT"))
        put("conversation_title", field(command.conversationTitle, "NOTIFICATION_EXTRA_CONVERSATION_TITLE"))
        put("messages", field(JSONArray().apply {
            command.messages.forEach { message ->
                put(JSONObject().apply {
                    putNullable("sender", message.sender)
                    put("text", message.text)
                    putNullable("timestamp", message.timestamp?.toEpochMilli())
                })
            }
        }, "ANDROID_NOTIFICATION_MESSAGING_STYLE"))
    }

    private fun capabilityDiscovery(actions: JSONArray, evidenceId: String): JSONArray = JSONArray().apply {
        for (index in 0 until actions.length()) {
            val action = actions.optJSONObject(index) ?: continue
            put(JSONObject().apply {
                put("schema", CAPABILITY_SCHEMA)
                put("capability_id", action.optString("capability_id"))
                put("type", action.optString("kind"))
                put("evidence_id", evidenceId)
                put("provider", action.optString("source", "ANDROID_NOTIFICATION_ACTION"))
                put("available", true)
                put("ephemeral", true)
                put("validity", "WHILE_NOTIFICATION_LIVE")
                put("expires_at", JSONObject.NULL)
                put("requires_confirmation", true)
                put("constraints", JSONObject().apply {
                    put("requires_text", action.optBoolean("requires_text_input", false))
                    action.opt("semantic_action")?.takeUnless { it == JSONObject.NULL }?.let { put("semantic_action", it) }
                })
                put("execution_policy", "EXPLICIT_CORTEX_REQUEST_AND_USER_AUTHORIZATION_REQUIRED")
            })
        }
    }

    private fun deltaJson(
        change: String,
        normalization: JSONObject,
        previousEvidenceId: String?,
        fields: JSONObject,
        attachments: JSONArray,
    ) = JSONObject().apply {
        put("change", change)
        put("reason", normalization.optString("change_reason"))
        putNullable("previous_evidence_id", previousEvidenceId)
        put("before_ref", previousEvidenceId ?: JSONObject.NULL)
        put("changed_fields", JSONArray().apply {
            when (change) {
                "NEW_MESSAGES" -> put("messages")
                "CONTENT_CHANGED" -> put("content")
                "MACHINE_CHURN_ONLY" -> put("machine_state")
                "NEW_POST" -> {
                    put("content")
                    if (attachments.length() > 0) put("attachments")
                }
                else -> put("content")
            }
        })
        put("after", JSONObject().apply {
            put("fields", JSONObject(fields.toString()))
            put("attachments", JSONArray(attachments.toString()))
        })
        put("before_materialized", false)
        put("before_resolution", "Use previous_evidence_id/raw_ref; Relay does not invent unavailable prior field values")
    }

    private fun rawAndroidPayload(root: JSONObject): JSONObject = JSONObject().apply {
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("relay_")) continue
            val value = root.opt(key)
            put(key, when (value) {
                is JSONObject -> JSONObject(value.toString())
                is JSONArray -> JSONArray(value.toString())
                null -> JSONObject.NULL
                else -> value
            })
        }
    }

    private fun handoffAttachmentJson(item: HandoffAttachment) = JSONObject().apply {
        put("attachment_id", item.attachmentId)
        put("kind", item.kind)
        putNullable("mime_type", item.mimeType)
        putNullable("name", item.displayName)
        putNullable("size_bytes", item.sizeBytes)
        putNullable("sha256", item.sha256)
        put("content_available", item.contentAvailable)
        putNullable("storage_ref", item.storageRef)
        put("origin", item.origin)
        putNullable("original_uri_provenance", item.originalUriProvenance)
    }

    private fun field(value: Any?, provenance: String) = JSONObject().apply {
        put("value", value ?: JSONObject.NULL)
        put("provenance", provenance)
        put("grounded", value != null && value != JSONObject.NULL)
    }

    private fun provenanceItem(field: String, source: String) = JSONObject().apply {
        put("field", field)
        put("source", source)
        put("grounded", true)
    }

    private fun eventType(change: String): String = when (change) {
        "NEW_POST" -> "CREATED"
        "NEW_MESSAGES", "CONTENT_CHANGED", "MACHINE_CHURN_ONLY" -> "UPDATED"
        "EXACT_DUPLICATE" -> "UNCHANGED"
        else -> "OBSERVED"
    }

    private fun evidenceId(notificationIdentity: String, generation: Int, revision: Int): String =
        stableId("evidence", notificationIdentity, generation.toString(), revision.toString())

    private fun uriProvenance(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        listOfNotNull(uri.scheme, uri.authority).joinToString("://")
    }.getOrDefault("URI_PRESENT")

    fun uriProvenance(uri: Uri): String = listOfNotNull(uri.scheme, uri.authority).joinToString("://")
        .ifBlank { "URI_PRESENT" }

    private fun containsAny(text: String, vararg tokens: String): Boolean = tokens.any(text::contains)

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun stableId(prefix: String, vararg values: String): String {
        val joined = values.joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return prefix + "_" + digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}