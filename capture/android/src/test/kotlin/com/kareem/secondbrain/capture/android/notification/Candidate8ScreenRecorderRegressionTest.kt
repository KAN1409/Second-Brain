package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.capture.android.connector.RelayFilterState
import org.junit.Assert.assertEquals
import org.junit.Test

class Candidate8ScreenRecorderRegressionTest {
    @Test
    fun samsungRecorderElapsedSecondUpdateIsDroppedEvenWithoutServiceFlags() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.samsung.android.app.smartcapture",
                title = "Samsung capture",
                body = "Tap here to stop recording.\n00:13",
                expandedText = null,
                isOngoing = false,
                category = null,
                channelId = null,
                meaningfulChange = NotificationMeaningfulChange.CONTENT_CHANGED,
                signalType = RelaySignalType.OTHER,
            ),
        )
        assertEquals(RelayFilterState.DROP_CONFIRMED_NOISE, decision.state)
        assertEquals("Samsung screen recorder elapsed-time churn", decision.reason)
    }

    @Test
    fun samsungRecorderInitialPostIsPreserved() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.samsung.android.app.smartcapture",
                title = "Samsung capture",
                body = "Tap here to stop recording.\n00:00",
                expandedText = null,
                isOngoing = false,
                category = null,
                channelId = null,
                meaningfulChange = NotificationMeaningfulChange.NEW_POST,
                signalType = RelaySignalType.OTHER,
            ),
        )
        assertEquals(RelayFilterState.FORWARD, decision.state)
    }

    @Test
    fun unrelatedClockUpdateIsNotDroppedByRecorderRule() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "example.timer",
                title = "Meeting reminder",
                body = "Starts at 10:30",
                expandedText = null,
                isOngoing = false,
                category = null,
                channelId = null,
                meaningfulChange = NotificationMeaningfulChange.CONTENT_CHANGED,
                signalType = RelaySignalType.OTHER,
            ),
        )
        assertEquals(RelayFilterState.FORWARD, decision.state)
    }
}