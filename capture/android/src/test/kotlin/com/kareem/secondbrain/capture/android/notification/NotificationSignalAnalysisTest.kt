package com.kareem.secondbrain.capture.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class NotificationSignalAnalysisTest {
    private fun facts(
        body: String,
        messages: List<NotificationAnalysisMessage> = emptyList(),
        category: String? = "msg",
        ongoing: Boolean = false,
        shortcutId: String? = "conversation-42",
    ) = NotificationAnalysisFacts(
        packageName = "com.whatsapp",
        notificationKey = "0|com.whatsapp|42|chat|10101",
        androidUserId = 0,
        uid = 10101,
        tag = "chat",
        shortcutId = shortcutId,
        channelId = "messages",
        category = category,
        isOngoing = ongoing,
        title = "Kareem",
        body = body,
        expandedText = null,
        conversationTitle = "Kareem",
        messages = messages,
        people = listOf(NotificationAnalysisPerson("Kareem", "person-1", null)),
        replyable = true,
    )

    @Test
    fun structuredNewMessageBecomesNewEvidenceInsideSameNotificationInstance() {
        val dir = Files.createTempDirectory("relay-lifecycle").toFile()
        try {
            val store = DurableNotificationLifecycleStore(dir)
            val firstFacts = facts(
                body = "Hello",
                messages = listOf(NotificationAnalysisMessage("Kareem", "Hello", Instant.ofEpochMilli(1000))),
            )
            val identity = NotificationSignalAnalyzer.notificationIdentity(firstFacts)
            val first = store.observePosted(
                notificationIdentity = identity,
                visibleFingerprint = NotificationSignalAnalyzer.visibleFingerprint(firstFacts),
                stableChurnFingerprint = NotificationSignalAnalyzer.stableChurnFingerprint(firstFacts),
                messageFingerprints = firstFacts.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                nowEpochMs = 1000,
            )
            val firstAnalysis = NotificationSignalAnalyzer.analyze(firstFacts, first)
            assertEquals(NotificationLifecycleState.POSTED, first.state)
            assertEquals(NotificationMeaningfulChange.NEW_POST, firstAnalysis.change)

            val updatedFacts = facts(
                body = "New message",
                messages = listOf(
                    NotificationAnalysisMessage("Kareem", "Hello", Instant.ofEpochMilli(1000)),
                    NotificationAnalysisMessage("Kareem", "New message", Instant.ofEpochMilli(2000)),
                ),
            )
            val second = store.observePosted(
                notificationIdentity = identity,
                visibleFingerprint = NotificationSignalAnalyzer.visibleFingerprint(updatedFacts),
                stableChurnFingerprint = NotificationSignalAnalyzer.stableChurnFingerprint(updatedFacts),
                messageFingerprints = updatedFacts.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                nowEpochMs = 2000,
            )
            val secondAnalysis = NotificationSignalAnalyzer.analyze(updatedFacts, second)

            assertEquals(NotificationLifecycleState.UPDATED, second.state)
            assertEquals(1, second.newMessageFingerprints.size)
            assertEquals(NotificationMeaningfulChange.NEW_MESSAGES, secondAnalysis.change)
            assertEquals(firstAnalysis.notificationInstanceIdentity, secondAnalysis.notificationInstanceIdentity)
            assertNotEquals(firstAnalysis.logicalSignalId, secondAnalysis.logicalSignalId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newMessageClassificationDoesNotReuseOtpFromOlderMessageInSnapshot() {
        val dir = Files.createTempDirectory("relay-delta-entities").toFile()
        try {
            val store = DurableNotificationLifecycleStore(dir)
            val oldOtp = NotificationAnalysisMessage("Kareem", "OTP code 482193", Instant.ofEpochMilli(1000))
            val firstFacts = facts(body = oldOtp.text, messages = listOf(oldOtp))
            val identity = NotificationSignalAnalyzer.notificationIdentity(firstFacts)
            store.observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(firstFacts),
                NotificationSignalAnalyzer.stableChurnFingerprint(firstFacts),
                firstFacts.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                nowEpochMs = 1000,
            )

            val newMessage = NotificationAnalysisMessage("Kareem", "Thanks, received", Instant.ofEpochMilli(2000))
            val updatedFacts = facts(body = newMessage.text, messages = listOf(oldOtp, newMessage))
            val lifecycle = store.observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(updatedFacts),
                NotificationSignalAnalyzer.stableChurnFingerprint(updatedFacts),
                updatedFacts.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                nowEpochMs = 2000,
            )
            val analysis = NotificationSignalAnalyzer.analyze(updatedFacts, lifecycle)

            assertEquals(NotificationMeaningfulChange.NEW_MESSAGES, analysis.change)
            assertEquals(RelaySignalType.HUMAN_MESSAGE, analysis.signalType)
            assertFalse(analysis.entities.any { it.type == "OTP" })
            assertTrue(analysis.entities.any { it.type == "PERSON" && it.sourceField == "messages[0].sender" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun exactSnapshotUpdateIsRecognizedAsDuplicateAcrossStoreInstances() {
        val dir = Files.createTempDirectory("relay-lifecycle-restart").toFile()
        try {
            val firstFacts = facts("Same")
            val identity = NotificationSignalAnalyzer.notificationIdentity(firstFacts)
            DurableNotificationLifecycleStore(dir).observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(firstFacts),
                NotificationSignalAnalyzer.stableChurnFingerprint(firstFacts),
                emptyList(),
                nowEpochMs = 1000,
            )
            val afterRestart = DurableNotificationLifecycleStore(dir).observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(firstFacts),
                NotificationSignalAnalyzer.stableChurnFingerprint(firstFacts),
                emptyList(),
                nowEpochMs = 2000,
            )
            val analysis = NotificationSignalAnalyzer.analyze(firstFacts, afterRestart)

            assertTrue(afterRestart.unchanged)
            assertEquals(NotificationMeaningfulChange.EXACT_DUPLICATE, analysis.change)
            assertEquals(1, afterRestart.sequence)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun removalEndsInstanceAndRepostGetsNewInstanceIdentity() {
        val dir = Files.createTempDirectory("relay-lifecycle-remove").toFile()
        try {
            val sample = facts("Hello")
            val identity = NotificationSignalAnalyzer.notificationIdentity(sample)
            val first = DurableNotificationLifecycleStore(dir).observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(sample),
                NotificationSignalAnalyzer.stableChurnFingerprint(sample),
                emptyList(),
                nowEpochMs = 1000,
            )
            val firstAnalysis = NotificationSignalAnalyzer.analyze(sample, first)
            DurableNotificationLifecycleStore(dir).markRemoved(identity, 1500)
            val repost = DurableNotificationLifecycleStore(dir).observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(sample),
                NotificationSignalAnalyzer.stableChurnFingerprint(sample),
                emptyList(),
                nowEpochMs = 3000,
            )
            val repostAnalysis = NotificationSignalAnalyzer.analyze(sample, repost)

            assertTrue(repost.isNewInstance)
            assertEquals(2, repost.generation)
            assertNotEquals(firstAnalysis.notificationInstanceIdentity, repostAnalysis.notificationInstanceIdentity)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun onlyProgressPercentageChangeCanBeDeterministicMachineChurn() {
        val dir = Files.createTempDirectory("relay-progress").toFile()
        try {
            val first = facts("Downloading 34%", category = "progress", ongoing = true, shortcutId = null)
            val identity = NotificationSignalAnalyzer.notificationIdentity(first)
            val store = DurableNotificationLifecycleStore(dir)
            store.observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(first),
                NotificationSignalAnalyzer.stableChurnFingerprint(first),
                emptyList(),
                nowEpochMs = 1000,
            )
            val secondFacts = facts("Downloading 35%", category = "progress", ongoing = true, shortcutId = null)
            val second = store.observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(secondFacts),
                NotificationSignalAnalyzer.stableChurnFingerprint(secondFacts),
                emptyList(),
                nowEpochMs = 2000,
            )
            val analysis = NotificationSignalAnalyzer.analyze(secondFacts, second)

            assertTrue(second.stableChurnOnly)
            assertEquals(NotificationMeaningfulChange.MACHINE_CHURN_ONLY, analysis.change)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extractsOnlyGroundedEntitiesWithSourceSpans() {
        val sample = facts("OTP code 482193. Pay EGP 250.00 at 14:30. Ref: AB-1234 https://example.com")
        val dir = Files.createTempDirectory("relay-entities").toFile()
        try {
            val identity = NotificationSignalAnalyzer.notificationIdentity(sample)
            val lifecycle = DurableNotificationLifecycleStore(dir).observePosted(
                identity,
                NotificationSignalAnalyzer.visibleFingerprint(sample),
                NotificationSignalAnalyzer.stableChurnFingerprint(sample),
                emptyList(),
                nowEpochMs = 1000,
            )
            val analysis = NotificationSignalAnalyzer.analyze(sample, lifecycle)
            val types = analysis.entities.map { it.type }.toSet()

            assertTrue("OTP" in types)
            assertTrue("MONEY" in types)
            assertTrue("TIME" in types)
            assertTrue("REFERENCE" in types)
            assertTrue("URL" in types)
            assertTrue("PERSON" in types)
            assertEquals(RelaySignalType.OTP, analysis.signalType)
            assertFalse(analysis.entities.any { it.start < 0 || it.endExclusive <= it.start })
        } finally {
            dir.deleteRecursively()
        }
    }
}
