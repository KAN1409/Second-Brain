package com.kareem.secondbrain.capture.android.connector

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class Candidate6ConnectorRegressionTest {
    @Test
    fun repeatedRawCallbackForSameNotificationKeyDoesNotGrowDashboardRows() {
        val key = "candidate6-${System.nanoTime()}"
        val before = RelayRuntimeDiagnostics.state.value

        RelayRuntimeDiagnostics.markRawNotification(
            notificationKey = key,
            packageName = "com.samsung.android.app.smartcapture",
            occurredAt = Instant.ofEpochMilli(1_000L),
            title = "Samsung capture",
            body = "00:06",
            conversationTitle = null,
        )
        RelayRuntimeDiagnostics.markRawNotification(
            notificationKey = key,
            packageName = "com.samsung.android.app.smartcapture",
            occurredAt = Instant.ofEpochMilli(2_000L),
            title = "Samsung capture",
            body = "00:07",
            conversationTitle = null,
        )

        val after = RelayRuntimeDiagnostics.state.value
        assertEquals(before.rawReceived + 2L, after.rawReceived)
        assertEquals(1, after.rawNotifications.count { it.notificationKey == key })
        assertEquals("00:07", after.rawNotifications.first { it.notificationKey == key }.body)
    }

    @Test
    fun canonicalAdapterNeverReplacesStableCortexWireSourceType() {
        val v1 = JSONObject().apply {
            put("protocol", RelayV2Protocol.V1_PROTOCOL)
            put("event_id", "sb_event-1")
            put("connector_id", "second_brain")
            put("source_type", "NOTIFICATION")
            put("source_package", "com.whatsapp")
            put("occurred_at", 123L)
            put("notification_key", "key-1")
            put("metadata", JSONObject().apply {
                put("relay_evidence_envelope_v1", JSONObject().apply {
                    put("source", JSONObject().apply {
                        put("adapter", "ANDROID_NOTIFICATION")
                        put("package", "com.whatsapp")
                        put("mechanism", "NOTIFICATION_LISTENER")
                    })
                })
            })
        }

        val v2 = RelayV2Protocol.fromV1(v1)
        val source = v2.getJSONObject("source")
        assertEquals("NOTIFICATION", source.getString("type"))
        assertEquals("ANDROID_NOTIFICATION", source.getString("adapter"))
        assertEquals("NOTIFICATION_LISTENER", source.getString("mechanism"))
        assertTrue(v2.getJSONObject("compatibility").getBoolean("source_type_vocabulary_preserved"))
    }
}
