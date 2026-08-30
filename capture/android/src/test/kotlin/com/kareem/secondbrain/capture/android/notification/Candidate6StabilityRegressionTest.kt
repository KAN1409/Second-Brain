package com.kareem.secondbrain.capture.android.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class Candidate6StabilityRegressionTest {
    @Test
    fun ongoingServiceTimerUpdateIsMachineChurn() {
        val first = facts("Tap here to stop recording.\n00:06")
        val second = facts("Tap here to stop recording.\n00:07")

        assertEquals(
            NotificationSignalAnalyzer.stableChurnFingerprint(first),
            NotificationSignalAnalyzer.stableChurnFingerprint(second),
        )

        val lifecycle = NotificationLifecycleDecision(
            notificationIdentity = NotificationSignalAnalyzer.notificationIdentity(second),
            state = NotificationLifecycleState.UPDATED,
            generation = 1,
            sequence = 7,
            instanceStartedAtEpochMs = 1_000L,
            visibleFingerprint = NotificationSignalAnalyzer.visibleFingerprint(second),
            stableChurnFingerprint = NotificationSignalAnalyzer.stableChurnFingerprint(second),
            newMessageFingerprints = emptySet(),
            unchanged = false,
            stableChurnOnly = true,
            isNewInstance = false,
        )

        val analysis = NotificationSignalAnalyzer.analyze(second, lifecycle)
        assertEquals(NotificationMeaningfulChange.MACHINE_CHURN_ONLY, analysis.change)
        assertEquals(RelaySignalType.SYSTEM_NOISE, analysis.signalType)
    }

    private fun facts(body: String) = NotificationAnalysisFacts(
        packageName = "com.samsung.android.app.smartcapture",
        notificationKey = "screen-recorder",
        androidUserId = 0,
        uid = 1000,
        tag = "capture",
        shortcutId = null,
        channelId = "screen_recording",
        category = "service",
        isOngoing = true,
        title = "Samsung capture",
        body = body,
        expandedText = null,
        conversationTitle = null,
        messages = emptyList(),
        people = emptyList(),
        replyable = false,
    )
}
