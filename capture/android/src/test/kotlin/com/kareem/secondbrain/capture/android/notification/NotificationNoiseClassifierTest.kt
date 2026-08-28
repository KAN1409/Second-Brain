package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.capture.android.connector.RelayFilterState
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationNoiseClassifierTest {
    @Test
    fun ongoingSystemUiChargingPercentageIsConfirmedNoise() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.android.systemui",
                title = "Charging",
                body = "35% • 41 min until full",
                expandedText = null,
                isOngoing = true,
                category = "sys",
                channelId = "BATTERY",
            ),
        )

        assertEquals(RelayFilterState.DROP_CONFIRMED_NOISE, decision.state)
    }

    @Test
    fun ongoingAndroidMediaTransportControlIsConfirmedNoise() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.google.android.apps.youtube.music",
                title = "Song title",
                body = "Artist name",
                expandedText = null,
                isOngoing = true,
                category = "transport",
                channelId = "playback",
            ),
        )

        assertEquals(RelayFilterState.DROP_CONFIRMED_NOISE, decision.state)
    }

    @Test
    fun usefulYoutubeMusicNotificationIsStillForwarded() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.google.android.apps.youtube.music",
                title = "Download complete",
                body = "Your playlist is ready offline",
                expandedText = null,
                isOngoing = false,
                category = "status",
                channelId = "downloads",
            ),
        )

        assertEquals(RelayFilterState.FORWARD, decision.state)
    }

    @Test
    fun nonOngoingTransportNotificationIsNotDestroyed() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.example.player",
                title = "Playback stopped",
                body = "Tap to resume",
                expandedText = null,
                isOngoing = false,
                category = "transport",
                channelId = "playback",
            ),
        )

        assertEquals(RelayFilterState.FORWARD, decision.state)
    }

    @Test
    fun uncertainPersistentSystemUiStateIsLowValueButPreserved() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.android.systemui",
                title = "Hotspot active",
                body = "1 device connected",
                expandedText = null,
                isOngoing = true,
                category = "sys",
                channelId = "HOTSPOT",
            ),
        )

        assertEquals(RelayFilterState.LOW_VALUE, decision.state)
    }

    @Test
    fun humanMessageIsForwarded() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.whatsapp",
                title = "Kareem",
                body = "Are you coming?",
                expandedText = null,
                isOngoing = false,
                category = "msg",
                channelId = "messages",
            ),
        )

        assertEquals(RelayFilterState.FORWARD, decision.state)
    }

    @Test
    fun nonOngoingChargingNotificationIsNotDestroyed() {
        val decision = NotificationNoiseClassifier.classify(
            NotificationNoiseFacts(
                packageName = "com.android.systemui",
                title = "Charging",
                body = "35%",
                expandedText = null,
                isOngoing = false,
                category = "sys",
                channelId = "BATTERY",
            ),
        )

        assertEquals(RelayFilterState.FORWARD, decision.state)
    }
}
