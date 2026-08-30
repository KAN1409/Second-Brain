package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RelayEvidenceGatewayV1Test {
    @Test
    fun voiceNoteHintBecomesGroundedUnavailableAttachment() {
        val command = CaptureCommand.Notification(
            occurredAt = Instant.ofEpochMilli(10_000L),
            packageName = "com.whatsapp",
            notificationKey = "wa-key",
            title = "Ahmed",
            body = "Voice message",
            expandedText = null,
            conversationTitle = "Ahmed",
            messages = listOf(CaptureCommand.NotificationMessage("Ahmed", "Voice message", Instant.ofEpochMilli(10_000L))),
            metadataJson = null,
        )
        val root = baseRoot(sequence = 0).apply {
            put("category", "msg")
            put("messages", JSONArray())
            put("relay_action_capabilities_v1", JSONArray().put(JSONObject().apply {
                put("capability_id", "cap-reply")
                put("kind", "REPLY")
                put("requires_text_input", true)
                put("source", "Android Notification.Action[0]")
            }))
        }

        val envelope = RelayEvidenceGatewayV1.attachNotification(command, root)
        val attachments = envelope.getJSONArray("attachments")
        assertEquals(1, attachments.length())
        assertEquals("VOICE_NOTE", attachments.getJSONObject(0).getString("kind"))
        assertFalse(attachments.getJSONObject(0).getBoolean("content_available"))
        assertEquals("NOTIFICATION_TEXT_HINT", attachments.getJSONObject(0).getString("origin"))
        assertTrue(envelope.getString("raw_ref").startsWith("metadata:relay_raw_source_v1:"))
        assertEquals(RelayEvidenceGatewayV1.RAW_SCHEMA, root.getJSONObject("relay_raw_source_v1").getString("schema"))

        val capability = envelope.getJSONArray("capabilities").getJSONObject(0)
        assertEquals("REPLY", capability.getString("type"))
        assertTrue(capability.getBoolean("ephemeral"))
        assertTrue(capability.getBoolean("requires_confirmation"))
        assertEquals("WHILE_NOTIFICATION_LIVE", capability.getString("validity"))

        val textField = envelope.getJSONObject("fields").getJSONObject("text")
        assertEquals("Voice message", textField.getString("value"))
        assertEquals("NOTIFICATION_EXTRA_TEXT", textField.getString("provenance"))
    }

    @Test
    fun updateCarriesPreviousEvidenceReferenceWithoutInventingBeforeValues() {
        val command = CaptureCommand.Notification(
            occurredAt = Instant.ofEpochMilli(20_000L),
            packageName = "example.chat",
            notificationKey = "key",
            title = "Alice",
            body = "Updated text",
            expandedText = null,
            conversationTitle = "Alice",
            metadataJson = null,
        )
        val root = baseRoot(sequence = 3).apply {
            getJSONObject("relay_normalization").put("meaningful_change", "CONTENT_CHANGED")
        }

        val envelope = RelayEvidenceGatewayV1.attachNotification(command, root)
        assertEquals(3, envelope.getInt("revision"))
        assertNotNull(envelope.optString("previous_evidence_id").takeIf(String::isNotBlank))
        val delta = envelope.getJSONObject("delta")
        assertFalse(delta.getBoolean("before_materialized"))
        assertTrue(delta.getJSONArray("changed_fields").toString().contains("content"))
        assertEquals(envelope.getString("previous_evidence_id"), delta.getString("previous_evidence_id"))
    }

    @Test
    fun deliberateShareCarriesPrivateAttachmentHandoffEvidence() {
        val metadata = RelayEvidenceGatewayV1.buildShareMetadata(
            shareId = "share-1",
            sourcePackage = "com.whatsapp",
            observedAtEpochMs = 30_000L,
            action = "android.intent.action.SEND",
            text = null,
            attachments = listOf(
                RelayEvidenceGatewayV1.HandoffAttachment(
                    attachmentId = "attachment-1",
                    kind = "PDF",
                    mimeType = "application/pdf",
                    displayName = "contract.pdf",
                    sizeBytes = 1234L,
                    sha256 = "abc123",
                    contentAvailable = true,
                    storageRef = "asset:asset-1",
                    origin = "ANDROID_SHARE_CONTENT_URI",
                    originalUriProvenance = "content://com.whatsapp.provider.media",
                ),
            ),
            referrerProvenance = "android-app://com.whatsapp",
        )

        val envelope = metadata.getJSONObject("relay_evidence_envelope_v1")
        assertEquals("ANDROID_SHARE", envelope.getJSONObject("source").getString("adapter"))
        val attachment = envelope.getJSONArray("attachments").getJSONObject(0)
        assertEquals("PDF", attachment.getString("kind"))
        assertEquals("abc123", attachment.getString("sha256"))
        assertEquals("asset:asset-1", attachment.getString("storage_ref"))
        assertTrue(attachment.getBoolean("content_available"))
        assertEquals("USER_INITIATED_SHARE", envelope.getJSONObject("trace").getString("routing_decision"))
    }

    private fun baseRoot(sequence: Int): JSONObject = JSONObject().apply {
        put("id", 1)
        put("uid", 1000)
        put("androidUserId", 0)
        put("relay_normalization", JSONObject().apply {
            put("notification_identity", "notification_test")
            put("conversation_identity", "conversation_test")
            put("lifecycle_generation", 1)
            put("update_sequence", sequence)
            put("meaningful_change", "NEW_POST")
            put("change_reason", "test")
        })
        put("relay_semantic_v2", JSONObject().apply {
            put("conversation_continuity", JSONObject().apply {
                put("identity", "conversation_test")
                put("observation_sequence", sequence + 1L)
                put("first_seen_at", 1_000L)
                put("last_seen_at", 2_000L)
            })
        })
    }
}