package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.domain.CaptureCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class RelayEvidenceIntelligenceTest {
    @Test
    fun crossAppEpisodeConversationEntityAndQualityAreGrounded() {
        val root = Files.createTempDirectory("relay-intelligence-test").toFile()
        try {
            val stateFile = File(root, "state.json")
            val engine = RelayEvidenceIntelligence.forFile(stateFile)
            engine.observeAppActivity("example.mail", 1_000L, true)
            engine.observeScreen(
                "example.mail",
                "Order ABCD-1234\nTracking https://example.com/t/ABCD-1234\nDelivery tomorrow",
                1_100L,
            )
            engine.observeAppActivity("example.chat", 1_200L, true)
            engine.observeScreen("example.chat", "Alice\nOnline\nType a message\nSend message", 1_250L)

            val command = CaptureCommand.Notification(
                occurredAt = Instant.ofEpochMilli(1_300L),
                packageName = "example.chat",
                notificationKey = "key-1",
                title = "Alice",
                body = "Order ABCD-1234 is ready",
                expandedText = null,
                conversationTitle = "Alice",
                messages = listOf(
                    CaptureCommand.NotificationMessage("Alice", "Order ABCD-1234 is ready", Instant.ofEpochMilli(1_300L)),
                ),
                metadataJson = "{}",
            )
            val analysis = NotificationSignalAnalysis(
                sourceProfileIdentity = "source-1",
                notificationIdentity = "notification-1",
                notificationInstanceIdentity = "instance-1",
                conversationIdentity = "conversation-1",
                conversationIdentityBasis = "Conversation title / explicit participants",
                logicalSignalId = "signal-1",
                signalType = RelaySignalType.HUMAN_MESSAGE,
                change = NotificationMeaningfulChange.NEW_MESSAGES,
                changeReason = "one new structured message",
                newMessageFingerprints = setOf("m1"),
                entities = listOf(
                    RelayEvidenceEntity("REFERENCE", "ABCD-1234", "body", 6, 15, 1.0),
                    RelayEvidenceEntity("PERSON", "Alice", "messages[0].sender", 0, 5, 1.0),
                ),
            )
            val lifecycle = NotificationLifecycleDecision(
                notificationIdentity = "notification-1",
                state = NotificationLifecycleState.UPDATED,
                generation = 1,
                sequence = 2,
                instanceStartedAtEpochMs = 900L,
                visibleFingerprint = "visible",
                stableChurnFingerprint = "stable",
                newMessageFingerprints = setOf("m1"),
                unchanged = false,
                stableChurnOnly = false,
                isNewInstance = false,
            )

            val envelope = engine.notificationEnvelope(command, analysis, lifecycle)
            assertEquals(RelayEvidenceIntelligence.SCHEMA, envelope.getString("schema"))
            val episode = envelope.getJSONObject("episode")
            assertTrue(episode.getBoolean("cross_app"))
            assertTrue(episode.getJSONArray("packages").toString().contains("example.mail"))
            assertTrue(episode.getJSONArray("packages").toString().contains("example.chat"))
            assertTrue(episode.getJSONArray("screen_types").toString().contains("CHAT"))

            val conversation = envelope.getJSONObject("conversation_state")
            assertTrue(conversation.getBoolean("screen_open_observed"))
            assertTrue(conversation.getBoolean("app_foreground_observed"))
            assertEquals("CHAT", conversation.getString("recent_screen_type"))

            val candidates = envelope.getJSONArray("entity_candidates")
            var sawCrossAppReference = false
            for (i in 0 until candidates.length()) {
                val item = candidates.getJSONObject(i)
                assertFalse(item.getBoolean("identity_claim"))
                if (item.getString("type") == "REFERENCE" && item.getString("display_value").contains("ABCD-1234")) {
                    sawCrossAppReference = item.getBoolean("cross_app_observed") && item.getBoolean("directly_observed_in_signal")
                }
            }
            assertTrue("Exact reference should link across screen and notification evidence", sawCrossAppReference)

            val change = envelope.getJSONObject("change_intelligence")
            assertEquals("NEW_MESSAGES", change.getString("change"))
            assertEquals(1, change.getInt("new_message_fingerprint_count"))

            val quality = envelope.getJSONObject("evidence_quality")
            assertTrue(quality.getDouble("overall") > 0.8)
            assertTrue(envelope.getJSONObject("graph").getJSONArray("edges").length() >= 5)

            val reloaded = RelayEvidenceIntelligence.forFile(stateFile).stats()
            assertTrue(reloaded.getInt("observations") >= 5)
            assertTrue(reloaded.getInt("cross_app_episodes") >= 1)
            assertTrue(reloaded.getInt("cross_app_entity_candidates") >= 1)
        } finally {
            root.deleteRecursively()
        }
    }
}
