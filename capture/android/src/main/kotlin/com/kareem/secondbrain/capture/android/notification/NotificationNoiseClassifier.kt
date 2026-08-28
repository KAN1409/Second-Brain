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
    val isGroupSummary: Boolean = false,
    val meaningfulChange: NotificationMeaningfulChange? = null,
    val signalType: RelaySignalType? = null,
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
        if (facts.meaningfulChange == NotificationMeaningfulChange.EXACT_DUPLICATE) {
            return RelayFilterDecision(
                state = RelayFilterState.DROP_CONFIRMED_NOISE,
                reason = "Exact duplicate snapshot for the same notification lifecycle",
            )
        }
        if (facts.meaningfulChange == NotificationMeaningfulChange.MACHINE_CHURN_ONLY) {
            return RelayFilterDecision(
                state = RelayFilterState.DROP_CONFIRMED_NOISE,
                reason = "Only deterministic progress/percentage fields changed",
            )
        }
        if (facts.isGroupSummary) {
            return RelayFilterDecision(
                state = RelayFilterState.DROP_CONFIRMED_NOISE,
                reason = "Android notification group summary; child notifications carry the underlying evidence",
            )
        }

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

        if (facts.signalType == RelaySignalType.SYSTEM_NOISE && facts.isOngoing) {
            return RelayFilterDecision(
                state = RelayFilterState.LOW_VALUE,
                reason = "Persistent control/service signal; preserved because meaning is uncertain",
            )
        }

        return RelayFilterDecision(
            state = RelayFilterState.FORWARD,
            reason = "No confirmed noise rule matched",
        )
    }
}
