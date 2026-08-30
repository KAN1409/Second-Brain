package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject

/** Grounded generic semantic layer. It reorganizes Android evidence without personal inference. */
data class RelayConversationContinuity(
    val conversationIdentity: String,
    val observationSequence: Long,
    val firstSeenAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
)

data class RelayActionCapabilityDescriptor(
    val capabilityId: String,
    val kind: String,
    val label: String?,
    val semanticAction: Int?,
    val requiresTextInput: Boolean,
    val source: String,
)

object RelayV2EvidenceBuilder {
    const val SCHEMA = "CORTEX_RELAY_SEMANTIC_V2"

    fun enrich(
        command: CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
        lifecycle: NotificationLifecycleDecision,
        continuity: RelayConversationContinuity?,
        actionCapabilities: List<RelayActionCapabilityDescriptor>,
    ): CaptureCommand.Notification {
        val root = parseObject(command.metadataJson)
        root.put(
            "relay_semantic_v2",
            buildSemanticEnvelope(command, analysis, lifecycle, continuity, actionCapabilities, root),
        )
        root.put(
            "relay_action_capabilities_v1",
            JSONArray().apply { actionCapabilities.forEach { put(actionJson(it)) } },
        )
        return command.copy(metadataJson = root.toString())
    }

    fun buildSemanticEnvelope(
        command: CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
        lifecycle: NotificationLifecycleDecision,
        continuity: RelayConversationContinuity? = null,
        actionCapabilities: List<RelayActionCapabilityDescriptor> = emptyList(),
        androidMetadata: JSONObject = parseObject(command.metadataJson),
    ): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("source_type", "NOTIFICATION")
        put("source_package", command.packageName)
        put("signal_type", analysis.signalType.name)
        put("logical_signal_id", analysis.logicalSignalId)
        put("source_profile_identity", analysis.sourceProfileIdentity)
        put("notification_identity", analysis.notificationIdentity)
        put("notification_instance_identity", analysis.notificationInstanceIdentity)
        put("conversation_identity", analysis.conversationIdentity)
        put("conversation_identity_basis", analysis.conversationIdentityBasis)
        put("lifecycle_state", lifecycle.state.name)
        put("lifecycle_generation", lifecycle.generation)
        put("update_sequence", lifecycle.sequence)
        put("meaningful_change", analysis.change.name)
        put("change_reason", analysis.changeReason)
        put("occurred_at", command.occurredAt.toEpochMilli())

        continuity?.let { item ->
            put("conversation_continuity", JSONObject().apply {
                put("identity", item.conversationIdentity)
                put("observation_sequence", item.observationSequence)
                put("first_seen_at", item.firstSeenAtEpochMs)
                put("last_seen_at", item.lastSeenAtEpochMs)
            })
        }

        put("android_context", androidContext(androidMetadata))
        put("content", JSONObject().apply {
            putNullable("title", command.title)
            putNullable("text", command.body)
            putNullable("expanded_text", command.expandedText)
            putNullable("conversation_title", command.conversationTitle)
            put("messages", JSONArray().apply {
                command.messages.forEachIndexed { index, message ->
                    put(JSONObject().apply {
                        put("index", index)
                        putNullable("sender", message.sender)
                        put("text", message.text)
                        putNullable("timestamp", message.timestamp?.toEpochMilli())
                    })
                }
            })
        })

        put("semantic", semanticPayload(command, analysis))
        put("entities", JSONArray().apply { analysis.entities.forEach { put(entityJson(it)) } })
        put("attachments", attachmentEvidence(androidMetadata))
        put("links", JSONArray().apply {
            analysis.entities.filter { it.type == "URL" }.forEach { entity ->
                put(JSONObject().apply {
                    put("url", entity.value)
                    put("source_field", entity.sourceField)
                    put("start", entity.start)
                    put("end_exclusive", entity.endExclusive)
                })
            }
        })
        put("actions", JSONArray().apply { actionCapabilities.forEach { put(actionJson(it)) } })
        put("provenance", provenance(command, analysis, androidMetadata, actionCapabilities))
    }

    /** Type-specific payload containing only fields that already exist in Android evidence. */
    fun semanticPayload(command: CaptureCommand.Notification, analysis: NotificationSignalAnalysis): JSONObject {
        val entitiesByType = analysis.entities.groupBy { it.type }
        val base = JSONObject().apply {
            put("kind", analysis.signalType.name)
            putNullable("display_subject", command.title ?: command.conversationTitle)
        }
        when (analysis.signalType) {
            RelaySignalType.HUMAN_MESSAGE, RelaySignalType.SMS -> {
                base.put("message_count", command.messages.size)
                base.putNullable("latest_sender", command.messages.lastOrNull()?.sender)
                base.putNullable("latest_text", command.messages.lastOrNull()?.text ?: command.body)
            }
            RelaySignalType.EMAIL -> {
                base.putNullable("subject", command.title)
                base.putNullable("preview", command.body ?: command.expandedText)
            }
            RelaySignalType.OTP -> {
                base.put("codes", values(entitiesByType["OTP"]))
                base.putNullable("context_text", command.messages.lastOrNull()?.text ?: command.body)
            }
            RelaySignalType.BANKING -> {
                base.put("amounts", values(entitiesByType["MONEY"]))
                base.put("references", values(entitiesByType["REFERENCE"]))
                base.putNullable("text", command.body ?: command.expandedText)
            }
            RelaySignalType.DELIVERY -> {
                base.put("tracking_or_reference_ids", values(entitiesByType["REFERENCE"]))
                base.put("dates", values(entitiesByType["DATE"]))
                base.put("times", values(entitiesByType["TIME"]))
                base.putNullable("status_text", command.body ?: command.expandedText)
            }
            RelaySignalType.CALENDAR -> {
                base.put("dates", values(entitiesByType["DATE"]))
                base.put("times", values(entitiesByType["TIME"]))
                base.putNullable("event_text", command.title ?: command.body)
            }
            RelaySignalType.SECURITY -> {
                base.put("codes", values(entitiesByType["OTP"]))
                base.putNullable("alert_text", command.body ?: command.expandedText)
            }
            RelaySignalType.DOWNLOAD -> {
                base.put("references", values(entitiesByType["REFERENCE"]))
                base.put("urls", values(entitiesByType["URL"]))
                base.putNullable("status_text", command.body ?: command.expandedText)
            }
            RelaySignalType.CALL -> base.putNullable("call_text", command.body ?: command.title)
            RelaySignalType.SYSTEM_NOISE, RelaySignalType.OTHER -> base.putNullable("text", command.body ?: command.expandedText)
        }
        return base
    }

    fun attachmentEvidence(androidMetadata: JSONObject): JSONArray = JSONArray().apply {
        val messages = androidMetadata.optJSONArray("messages") ?: return@apply
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val mime = message.optString("dataMimeType").takeIf { it.isNotBlank() && it != "null" }
            val uri = message.optString("dataUri").takeIf { it.isNotBlank() && it != "null" }
            if (mime == null && uri == null) continue
            put(JSONObject().apply {
                put("source_field", "android.messages[$index]")
                putNullable("mime_type", mime)
                putNullable("uri", uri)
                put("provenance", "Android MessagingStyle.Message data attachment")
            })
        }
    }

    private fun androidContext(metadata: JSONObject): JSONObject = JSONObject().apply {
        copyIfPresent(metadata, this, "id")
        copyIfPresent(metadata, this, "tag")
        copyIfPresent(metadata, this, "uid")
        copyIfPresent(metadata, this, "androidUserId")
        copyIfPresent(metadata, this, "category")
        copyIfPresent(metadata, this, "channelId")
        copyIfPresent(metadata, this, "shortcutId")
        copyIfPresent(metadata, this, "importance")
        copyIfPresent(metadata, this, "groupKey")
        copyIfPresent(metadata, this, "isGroup")
        copyIfPresent(metadata, this, "isGroupSummary")
        copyIfPresent(metadata, this, "isOngoing")
        copyIfPresent(metadata, this, "isClearable")
        copyIfPresent(metadata, this, "replyable")
        copyIfPresent(metadata, this, "hasContentIntent")
        put("people_count", metadata.optJSONArray("people")?.length() ?: 0)
        put("android_action_count", metadata.optJSONArray("actions")?.length() ?: 0)
    }

    private fun provenance(
        command: CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
        metadata: JSONObject,
        actions: List<RelayActionCapabilityDescriptor>,
    ): JSONArray = JSONArray().apply {
        listOf(
            "title" to command.title,
            "body" to command.body,
            "expanded_text" to command.expandedText,
            "conversation_title" to command.conversationTitle,
        ).forEach { (field, value) ->
            if (!value.isNullOrBlank()) put(provenanceItem(field, "Android Notification extras"))
        }
        if (command.messages.isNotEmpty()) put(provenanceItem("messages", "Android Notification.MessagingStyle"))
        if ((metadata.optJSONArray("people")?.length() ?: 0) > 0) {
            put(provenanceItem("people", "Android Notification.EXTRA_PEOPLE_LIST"))
        }

        listOf(
            "category" to "Android Notification.category",
            "channelId" to "Android Notification.channelId",
            "shortcutId" to "Android Notification.shortcutId",
            "importance" to "NotificationListenerService.Ranking.importance",
            "groupKey" to "StatusBarNotification.groupKey",
            "isGroupSummary" to "Android Notification.FLAG_GROUP_SUMMARY",
            "replyable" to "Android Notification.Action RemoteInput",
            "hasContentIntent" to "Android Notification.contentIntent",
        ).forEach { (field, source) ->
            if (metadata.has(field) && !metadata.isNull(field)) put(provenanceItem("android_context.$field", source))
        }

        if (attachmentEvidence(metadata).length() > 0) {
            put(provenanceItem("attachments", "Android MessagingStyle.Message dataMimeType/dataUri"))
        }
        actions.forEach { action ->
            put(JSONObject().apply {
                put("field", "action:${action.capabilityId}")
                put("source", action.source)
                put("grounded", true)
                put("derived", "Stable capability ID over live Android action evidence; executable handle remains runtime-only")
            })
        }
        put(JSONObject().apply {
            put("field", "conversation_identity")
            put("source", analysis.conversationIdentityBasis)
            put("grounded", true)
            put("derived", "Deterministic hash over supplied Android identity evidence")
        })
        analysis.entities.forEach { entity ->
            put(JSONObject().apply {
                put("field", "entity:${entity.type}")
                put("source", entity.sourceField)
                put("grounded", true)
                put("span_start", entity.start)
                put("span_end_exclusive", entity.endExclusive)
            })
        }
    }

    private fun provenanceItem(field: String, source: String) = JSONObject().apply {
        put("field", field)
        put("source", source)
        put("grounded", true)
    }

    private fun entityJson(entity: RelayEvidenceEntity) = JSONObject().apply {
        put("type", entity.type)
        put("value", entity.value)
        put("source_field", entity.sourceField)
        put("start", entity.start)
        put("end_exclusive", entity.endExclusive)
        put("confidence", entity.confidence)
    }

    private fun actionJson(action: RelayActionCapabilityDescriptor) = JSONObject().apply {
        put("capability_id", action.capabilityId)
        put("kind", action.kind)
        putNullable("label", action.label)
        putNullable("semantic_action", action.semanticAction)
        put("requires_text_input", action.requiresTextInput)
        put("source", action.source)
    }

    private fun values(entities: List<RelayEvidenceEntity>?): JSONArray = JSONArray().apply {
        entities.orEmpty().map { it.value }.distinct().forEach(::put)
    }

    internal fun parseObject(raw: String?): JSONObject = try {
        raw?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun copyIfPresent(source: JSONObject, target: JSONObject, key: String) {
        if (source.has(key)) target.put(key, if (source.isNull(key)) JSONObject.NULL else source.opt(key))
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
