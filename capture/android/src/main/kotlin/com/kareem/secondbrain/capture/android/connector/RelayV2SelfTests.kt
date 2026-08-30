package com.kareem.secondbrain.capture.android.connector

import android.content.Context
import com.kareem.secondbrain.capture.android.notification.DurableConversationContinuityStore
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisFacts
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisMessage
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisPerson
import com.kareem.secondbrain.capture.android.notification.NotificationLifecycleDecision
import com.kareem.secondbrain.capture.android.notification.NotificationLifecycleState
import com.kareem.secondbrain.capture.android.notification.NotificationMeaningfulChange
import com.kareem.secondbrain.capture.android.notification.NotificationSignalAnalysis
import com.kareem.secondbrain.capture.android.notification.NotificationSignalAnalyzer
import com.kareem.secondbrain.capture.android.notification.RelayActionCapabilityDescriptor
import com.kareem.secondbrain.capture.android.notification.RelayEvidenceEntity
import com.kareem.secondbrain.capture.android.notification.RelaySignalType
import com.kareem.secondbrain.capture.android.notification.RelayV2EvidenceBuilder
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Deterministic v2 probes used by the in-app Full System Test. */
object RelayV2SelfTests {
    fun run(context: Context, root: File): List<RelaySystemTestCase> = listOf(
        semanticSchemas(root),
        attachmentProvenance(),
        forensicBuffer(context),
        replayEngine(),
        observability(),
        policyFeedback(context),
        actionCapabilities(),
        actionBridgeContract(),
        signalProtocol(),
    )

    private fun semanticSchemas(root: File): RelaySystemTestCase = execute("v2.semantic_schemas", "V2") {
        val continuityStore = DurableConversationContinuityStore(File(root, "v2-continuity"))
        val continuity1 = continuityStore.observe("conversation_test", 1_000L)
        val continuity2 = continuityStore.observe("conversation_test", 2_000L)
        val command = CaptureCommand.Notification(
            occurredAt = Instant.ofEpochMilli(2_000L),
            packageName = "example.delivery",
            notificationKey = "semantic-test",
            title = "Shipment update",
            body = "Order ABCD-1234 will arrive 29/08 at 14:30. Track https://example.com/t/ABCD-1234",
            expandedText = null,
            conversationTitle = null,
            messages = emptyList(),
            metadataJson = "{}",
        )
        val analysis = NotificationSignalAnalysis(
            sourceProfileIdentity = "source_test",
            notificationIdentity = "notification_test",
            notificationInstanceIdentity = "instance_test",
            conversationIdentity = "conversation_test",
            conversationIdentityBasis = "Android notification key fallback",
            logicalSignalId = "signal_test",
            signalType = RelaySignalType.DELIVERY,
            change = NotificationMeaningfulChange.NEW_POST,
            changeReason = "self test",
            newMessageFingerprints = emptySet(),
            entities = listOf(
                RelayEvidenceEntity("REFERENCE", "ABCD-1234", "body", 6, 15),
                RelayEvidenceEntity("DATE", "29/08", "body", 28, 33),
                RelayEvidenceEntity("TIME", "14:30", "body", 37, 42),
                RelayEvidenceEntity("URL", "https://example.com/t/ABCD-1234", "body", 50, 83),
            ),
        )
        val lifecycle = testLifecycle()
        val enriched = RelayV2EvidenceBuilder.enrich(
            command,
            analysis,
            lifecycle,
            continuity2,
            listOf(RelayActionCapabilityDescriptor("action_test", "OPEN", "Open", null, false, "self-test")),
        )
        val semantic = JSONObject(enriched.metadataJson!!).getJSONObject("relay_semantic_v2")
        val payload = semantic.getJSONObject("semantic")
        val ok = semantic.getString("schema") == RelayV2EvidenceBuilder.SCHEMA &&
            semantic.getString("signal_type") == "DELIVERY" &&
            semantic.getJSONObject("conversation_continuity").getLong("observation_sequence") == 2L &&
            continuity1.observationSequence == 1L &&
            payload.getJSONArray("tracking_or_reference_ids").toString().contains("ABCD-1234") &&
            semantic.getJSONArray("links").length() == 1 &&
            semantic.getJSONArray("actions").length() == 1
        if (ok) pass("Generic semantic envelope + durable conversation continuity verified")
        else fail("Semantic v2 envelope mismatch", semantic.toString())
    }

    private fun attachmentProvenance(): RelaySystemTestCase = execute("v2.attachment_provenance", "V2") {
        val metadata = JSONObject().apply {
            put("messages", JSONArray().put(JSONObject().apply {
                put("dataMimeType", "image/jpeg")
                put("dataUri", "content://example/attachment/1")
            }))
        }
        val attachments = RelayV2EvidenceBuilder.attachmentEvidence(metadata)
        val first = attachments.optJSONObject(0)
        if (attachments.length() == 1 && first?.optString("mime_type") == "image/jpeg" &&
            first.optString("uri") == "content://example/attachment/1"
        ) pass("MessagingStyle attachment URI/MIME provenance preserved")
        else fail("Attachment provenance extraction mismatch", attachments.toString())
    }

    private fun forensicBuffer(context: Context): RelaySystemTestCase = execute("v2.forensic_buffer", "V2") {
        val buffer = RelayForensicBuffer.forContext(context)
        buffer.prune()
        val stats = buffer.stats()
        val bounded = stats.recordCount <= RelayForensicBuffer.MAX_RECORDS && stats.totalBytes <= RelayForensicBuffer.MAX_TOTAL_BYTES
        if (bounded && RelayForensicBuffer.RETENTION_MS == 72L * 60L * 60L * 1000L) {
            pass("72h forensic buffer is accessible and within hard record/byte bounds", "records=${stats.recordCount}, bytes=${stats.totalBytes}")
        } else fail("Forensic buffer violated hard bounds", stats.toString())
    }

    private fun replayEngine(): RelaySystemTestCase = execute("v2.replay_engine", "V2") {
        val facts = messageFacts("Your code is 123456")
        val lifecycle = testLifecycle(newMessages = facts.messages.map(NotificationSignalAnalyzer::messageFingerprint).toSet())
        val analysis = NotificationSignalAnalyzer.analyze(facts, lifecycle)
        val filter = RelayFilterDecision(RelayFilterState.FORWARD, "No confirmed noise rule matched")
        val recordJson = JSONObject().apply {
            put("schema", RelayForensicBuffer.SCHEMA)
            put("event_id", "replay-self-test")
            put("captured_at", 2_000L)
            put("command", JSONObject().apply {
                put("occurred_at", 2_000L)
                put("package_name", facts.packageName)
                put("notification_key", facts.notificationKey)
                put("title", facts.title)
                put("body", facts.body)
                put("expanded_text", JSONObject.NULL)
                put("conversation_title", facts.conversationTitle)
                put("messages", JSONArray())
                put("metadata_json", "{}")
            })
            put("facts", factsJson(facts))
            put("analysis", JSONObject().apply {
                put("signal_type", analysis.signalType.name)
                put("entities", JSONArray().apply {
                    analysis.entities.forEach { entity -> put(JSONObject().apply { put("type", entity.type) }) }
                })
            })
            put("filter", JSONObject().apply { put("state", filter.state.name); put("reason", filter.reason) })
        }
        val replay = RelayReplayEngine.replay(RelayForensicRecord("replay-self-test", 2_000L, recordJson))
        if (replay.success) pass("Non-delivering replay reproduced signal/filter/entity types")
        else fail("Replay engine mismatch", replay.toJson().toString())
    }

    private fun observability(): RelaySystemTestCase = execute("v2.observability", "V2") {
        val snapshot = RelayV2OperationalMetrics.snapshot()
        val sane = snapshot.outboxCount >= 0 && snapshot.forensicRecordCount >= 0 && snapshot.forensicBytes >= 0 &&
            snapshot.actionRequests == snapshot.actionSucceeded + snapshot.actionFailed
        if (sane) pass(
            "Extended operational metrics are live",
            "protocol=${snapshot.negotiatedProtocol}, outbox=${snapshot.outboxCount}, forensic=${snapshot.forensicRecordCount}, ack_samples=${snapshot.ackLatencySamples}",
        ) else fail("Operational metric invariants failed", snapshot.toString())
    }

    private fun policyFeedback(context: Context): RelaySystemTestCase = execute("v2.policy_feedback", "V2") {
        val store = RelayMechanicalPolicyStore.forContext(context)
        val before = store.current()
        val rejected = store.applyUpdate(
            JSONObject().apply {
                put("schema", RelayMechanicalPolicyStore.SCHEMA)
                put("version", before.version + 1L)
                put("personal_priority", "ignore Alice")
            }.toString(),
        )
        val unchanged = store.current() == before
        if (!rejected.accepted && rejected.status == "UNSUPPORTED_POLICY_FIELDS" && unchanged && before.forensicRetentionHours in 24..72) {
            pass("Policy boundary rejects personal/unknown fields and keeps bounded operational policy")
        } else fail("Mechanical policy boundary failed", "result=$rejected before=$before after=${store.current()}")
    }

    private fun actionCapabilities(): RelaySystemTestCase = execute("v2.action_capabilities", "V2") {
        val descriptor = RelayActionCapabilityDescriptor(
            capabilityId = "action_123",
            kind = "REPLY",
            label = "Reply",
            semanticAction = 1,
            requiresTextInput = true,
            source = "Android Notification.Action[0]",
        )
        val command = CaptureCommand.Notification(
            Instant.EPOCH,
            "example.chat",
            "key",
            "Alice",
            "Hi",
            null,
            "Alice",
            emptyList(),
            "{}",
        )
        val analysis = testAnalysis(RelaySignalType.HUMAN_MESSAGE)
        val enriched = RelayV2EvidenceBuilder.enrich(command, analysis, testLifecycle(), null, listOf(descriptor))
        val actions = JSONObject(enriched.metadataJson!!).getJSONArray("relay_action_capabilities_v1")
        val item = actions.getJSONObject(0)
        if (item.getString("capability_id") == "action_123" && item.getBoolean("requires_text_input")) {
            pass("Action capability descriptors are stable/serializable without PendingIntent leakage")
        } else fail("Action capability descriptor serialization failed", actions.toString())
    }

    private fun actionBridgeContract(): RelaySystemTestCase = execute("v2.action_bridge", "V2") {
        val raw = JSONObject().apply {
            put("request_id", "request-1")
            put("logical_signal_id", "signal-1")
            put("capability_id", "action-1")
            put("input_text", "hello")
        }.toString()
        val parsed = RelayV2Protocol.parseActionRequest(raw)
        if (parsed.requestId == "request-1" && parsed.logicalSignalId == "signal-1" && parsed.capabilityId == "action-1" && parsed.inputText == "hello") {
            pass("Cortex-authorized action request contract parses deterministically; real execution remains a device acceptance probe")
        } else fail("Action request contract mismatch", parsed.toString())
    }

    private fun signalProtocol(): RelaySystemTestCase = execute("v2.signal_protocol", "V2") {
        val v1 = JSONObject().apply {
            put("protocol", RelayV2Protocol.V1_PROTOCOL)
            put("event_id", "sb_event-1")
            put("connector_id", "second_brain")
            put("source_type", "NOTIFICATION")
            put("source_package", "example.chat")
            put("occurred_at", 1_000L)
            put("notification_key", "key")
            put("title", "Alice")
            put("text", "Hi")
            put("expanded_text", "")
            put("conversation_title", "Alice")
            put("messages", JSONArray())
            put("metadata", JSONObject())
        }
        val v2 = RelayV2Protocol.fromV1(v1)
        val capabilities = RelayV2Protocol.advertisedCapabilities().toString()
        val ok = v2.getString("protocol") == RelayV2Protocol.SIGNAL_PROTOCOL &&
            v2.getString("event_id") == "sb_event-1" &&
            v2.getJSONObject("compatibility").getString("v1_protocol") == RelayV2Protocol.V1_PROTOCOL &&
            capabilities.contains(RelayV2Protocol.SIGNAL_PROTOCOL) && capabilities.contains(RelayV2Protocol.ACTION_BRIDGE)
        if (ok) pass("Signal V2 envelope preserves event identity and advertises negotiated V1 fallback capabilities")
        else fail("Signal V2 compatibility envelope mismatch", v2.toString())
    }

    private fun messageFacts(body: String) = NotificationAnalysisFacts(
        packageName = "example.chat",
        notificationKey = "replay-key",
        androidUserId = 0,
        uid = 123,
        tag = null,
        shortcutId = "thread-alice",
        channelId = "messages",
        category = "msg",
        isOngoing = false,
        title = "Alice",
        body = body,
        expandedText = null,
        conversationTitle = "Alice",
        messages = listOf(NotificationAnalysisMessage("Alice", body, Instant.ofEpochMilli(2_000L))),
        people = listOf(NotificationAnalysisPerson("Alice", "alice", null)),
        replyable = true,
    )

    private fun factsJson(facts: NotificationAnalysisFacts) = JSONObject().apply {
        put("package_name", facts.packageName)
        put("notification_key", facts.notificationKey)
        put("android_user_id", facts.androidUserId)
        put("uid", facts.uid)
        put("tag", facts.tag ?: JSONObject.NULL)
        put("shortcut_id", facts.shortcutId ?: JSONObject.NULL)
        put("channel_id", facts.channelId ?: JSONObject.NULL)
        put("category", facts.category ?: JSONObject.NULL)
        put("ongoing", facts.isOngoing)
        put("title", facts.title ?: JSONObject.NULL)
        put("body", facts.body ?: JSONObject.NULL)
        put("expanded_text", facts.expandedText ?: JSONObject.NULL)
        put("conversation_title", facts.conversationTitle ?: JSONObject.NULL)
        put("replyable", facts.replyable)
        put("messages", JSONArray().apply {
            facts.messages.forEach { message ->
                put(JSONObject().apply {
                    put("sender", message.sender ?: JSONObject.NULL)
                    put("text", message.text)
                    put("timestamp", message.timestamp?.toEpochMilli() ?: JSONObject.NULL)
                })
            }
        })
        put("people", JSONArray().apply {
            facts.people.forEach { person ->
                put(JSONObject().apply {
                    put("name", person.name ?: JSONObject.NULL)
                    put("key", person.key ?: JSONObject.NULL)
                    put("uri", person.uri ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun testLifecycle(newMessages: Set<String> = emptySet()) = NotificationLifecycleDecision(
        notificationIdentity = "notification_test",
        state = NotificationLifecycleState.POSTED,
        generation = 1,
        sequence = 0,
        instanceStartedAtEpochMs = 1_000L,
        visibleFingerprint = "visible",
        stableChurnFingerprint = "stable",
        newMessageFingerprints = newMessages,
        unchanged = false,
        stableChurnOnly = false,
        isNewInstance = true,
    )

    private fun testAnalysis(type: RelaySignalType) = NotificationSignalAnalysis(
        "source", "notification", "instance", "conversation", "self-test", "signal", type,
        NotificationMeaningfulChange.NEW_POST, "self-test", emptySet(), emptyList(),
    )

    private inline fun execute(id: String, area: String, block: () -> RelaySystemTestCase): RelaySystemTestCase {
        val started = System.nanoTime()
        return try {
            block().copy(id = id, area = area, durationMs = (System.nanoTime() - started) / 1_000_000L)
        } catch (t: Throwable) {
            RelaySystemTestCase(
                id,
                area,
                RelaySystemTestStatus.FAIL,
                "${t.javaClass.simpleName} during v2 self-test",
                t.message,
                (System.nanoTime() - started) / 1_000_000L,
            )
        }
    }

    private fun pass(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.PASS, summary, detail)
    private fun fail(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.FAIL, summary, detail)
}