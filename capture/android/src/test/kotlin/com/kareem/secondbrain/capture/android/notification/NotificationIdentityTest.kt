package com.kareem.secondbrain.capture.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.file.Files

class NotificationIdentityTest {
    private fun facts(userId: Int, uid: Int, key: String) = NotificationAnalysisFacts(
        packageName = "com.whatsapp",
        notificationKey = key,
        androidUserId = userId,
        uid = uid,
        tag = "chat-tag",
        shortcutId = "conversation-42",
        channelId = "messages",
        category = "msg",
        isOngoing = false,
        title = "Same person",
        body = "Hello",
        expandedText = null,
        conversationTitle = "Same person",
        messages = emptyList(),
        people = emptyList(),
        replyable = true,
    )

    private fun analyze(facts: NotificationAnalysisFacts, dirName: String): NotificationSignalAnalysis {
        val dir = Files.createTempDirectory(dirName).toFile()
        try {
            val identity = NotificationSignalAnalyzer.notificationIdentity(facts)
            val lifecycle = DurableNotificationLifecycleStore(dir).observePosted(
                notificationIdentity = identity,
                visibleFingerprint = NotificationSignalAnalyzer.visibleFingerprint(facts),
                stableChurnFingerprint = NotificationSignalAnalyzer.stableChurnFingerprint(facts),
                messageFingerprints = emptyList(),
                nowEpochMs = 1000,
            )
            return NotificationSignalAnalyzer.analyze(facts, lifecycle)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun samePackageAndConversationInDifferentAndroidProfilesRemainDistinct() {
        val primary = analyze(facts(userId = 0, uid = 10101, key = "0|com.whatsapp|42|chat|10101"), "primary-wa")
        val secondary = analyze(facts(userId = 150, uid = 1510101, key = "150|com.whatsapp|42|chat|1510101"), "secondary-wa")

        assertNotEquals(primary.sourceProfileIdentity, secondary.sourceProfileIdentity)
        assertNotEquals(primary.notificationIdentity, secondary.notificationIdentity)
        assertNotEquals(primary.conversationIdentity, secondary.conversationIdentity)
        assertEquals("Android shortcutId", primary.conversationIdentityBasis)
        assertEquals("Android shortcutId", secondary.conversationIdentityBasis)
    }

    @Test
    fun sameNotificationIdentityIsDeterministicAcrossCalls() {
        val sample = facts(userId = 0, uid = 10101, key = "0|com.whatsapp|42|chat|10101")
        assertEquals(
            NotificationSignalAnalyzer.notificationIdentity(sample),
            NotificationSignalAnalyzer.notificationIdentity(sample),
        )
    }
}
