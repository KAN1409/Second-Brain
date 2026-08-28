package com.kareem.secondbrain.capture.android.notification

import com.kareem.secondbrain.capture.android.connector.RelayFilterDecision
import com.kareem.secondbrain.capture.android.connector.RelayFilterState

data class NotificationNoiseFacts(
    val packageName: String,
    val title: String?,
    val body: String?,
    val expandedText: String?,
    val isOngoing: Boolean,
    val category: String?,
    val channelId: String?,
)

/**
 * Conservative Relay-side classification. Only narrow, deterministic machine/control-surface
 * churn is suppressed. LOW_VALUE is diagnostic only in V1 and is still forwarded to Cortex.
 *
 * Important boundary: rules classify notification mechanics, never personal importance.
 */
object NotificationNoiseClassifier {
    private val percentage = Regex("(?<!\\d)(?:100|[1-9]?\\d)%(?!\\d)")
    private val chargingMarkers = listOf("charging", "charge", "شحن", "الشحن")

    fun classify(facts: NotificationNoiseFacts): RelayFilterDecision {
        val text = listOfNotNull(
            facts.title,
            facts.body,
            facts.expandedText,
            facts.category,
            facts.channelId,
        ).joinToString(" ").lowercase()

        val isSystemUi = facts.packageName == "com.android.systemui"
        val looksLikeChargingPercentage = percentage.containsMatchIn(text) &&
            chargingMarkers.any(text::contains)

        if (isSystemUi && facts.isOngoing && looksLikeChargingPercentage) {
            return RelayFilterDecision(
                state = RelayFilterState.DROP_CONFIRMED_NOISE,
                reason = "Ongoing SystemUI charging percentage churn",
            )
        }

        // Android CATEGORY_TRANSPORT is the platform's semantic category for media playback
        // controls. When it is also ongoing, the notification is a persistent control surface,
        // not a discrete app event. Keep it in local capture for forensics, but do not forward
        // repeated now-playing/control state into Cortex. Other notifications from the same app
        // (recommendations, downloads, account/security notices, etc.) remain untouched.
        if (facts.isOngoing && facts.category.equals("transport", ignoreCase = true)) {
            return RelayFilterDecision(
                state = RelayFilterState.DROP_CONFIRMED_NOISE,
                reason = "Ongoing Android media transport control surface",
            )
        }

        if (isSystemUi && facts.isOngoing) {
            return RelayFilterDecision(
                state = RelayFilterState.LOW_VALUE,
                reason = "Persistent SystemUI state; preserved because meaning is uncertain",
            )
        }

        return RelayFilterDecision(
            state = RelayFilterState.FORWARD,
            reason = "No confirmed noise rule matched",
        )
    }
}
