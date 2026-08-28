package com.kareem.secondbrain.capture.android.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class RelayConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class RelayFilterState { FORWARD, LOW_VALUE, DROP_CONFIRMED_NOISE }
enum class RelayDeliveryState { CAPTURED, WAITING, FORWARDED, FILTERED, RETRYING, FAILED }

data class RelayFilterDecision(
    val state: RelayFilterState,
    val reason: String,
)

data class RelayRecentSignal(
    val eventId: String,
    val occurredAt: Instant,
    val capturedAt: Instant,
    val updatedAt: Instant,
    val packageName: String,
    val title: String?,
    val preview: String?,
    val filterState: RelayFilterState? = null,
    val filterReason: String? = null,
    val deliveryState: RelayDeliveryState = RelayDeliveryState.CAPTURED,
    val deliveryDetail: String = "Stored locally",
)

data class RelayDiagnosticSnapshot(
    val captured: Long = 0,
    val forwarded: Long = 0,
    val filtered: Long = 0,
    val lowValueForwarded: Long = 0,
    val waiting: Int = 0,
    val failedRetries: Long = 0,
    val connectionState: RelayConnectionState = RelayConnectionState.DISCONNECTED,
    val lastPackage: String? = null,
    val lastFilterState: RelayFilterState? = null,
    val lastFilterReason: String? = null,
    val lastError: String? = null,
    val lastActivityAt: Instant? = null,
    val recentSignals: List<RelayRecentSignal> = emptyList(),
)

/**
 * Process-local operational telemetry for the Relay UI.
 *
 * This is deliberately not personal memory and is not part of the Cortex wire protocol. Counters
 * and recent signals reset when the app process restarts. Durable delivery/accounting belongs to
 * the coordinated V2 outbox + correlated-ACK work.
 */
object RelayRuntimeDiagnostics {
    private const val MAX_RECENT_SIGNALS = 20

    private val lock = Any()
    private val mutableState = MutableStateFlow(RelayDiagnosticSnapshot())
    val state: StateFlow<RelayDiagnosticSnapshot> = mutableState.asStateFlow()

    fun markCaptured(
        eventId: String,
        packageName: String,
        occurredAt: Instant,
        title: String?,
        preview: String?,
    ) = update { current ->
        val now = Instant.now()
        current.copy(
            captured = current.captured + 1,
            lastPackage = packageName,
            lastActivityAt = now,
            recentSignals = upsert(
                current.recentSignals,
                RelayRecentSignal(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    capturedAt = now,
                    updatedAt = now,
                    packageName = packageName,
                    title = title?.takeIf(String::isNotBlank),
                    preview = preview?.takeIf(String::isNotBlank),
                ),
            ),
        )
    }

    fun markFilterDecision(
        eventId: String,
        packageName: String,
        decision: RelayFilterDecision,
    ) = update { current ->
        val now = Instant.now()
        current.copy(
            filtered = current.filtered + if (decision.state == RelayFilterState.DROP_CONFIRMED_NOISE) 1 else 0,
            lowValueForwarded = current.lowValueForwarded + if (decision.state == RelayFilterState.LOW_VALUE) 1 else 0,
            lastPackage = packageName,
            lastFilterState = decision.state,
            lastFilterReason = decision.reason,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    filterState = decision.state,
                    filterReason = decision.reason,
                    deliveryState = if (decision.state == RelayFilterState.DROP_CONFIRMED_NOISE) {
                        RelayDeliveryState.FILTERED
                    } else {
                        signal.deliveryState
                    },
                    deliveryDetail = if (decision.state == RelayFilterState.DROP_CONFIRMED_NOISE) {
                        "Stored locally; forwarding suppressed as confirmed noise"
                    } else {
                        signal.deliveryDetail
                    },
                    updatedAt = now,
                )
            },
        )
    }

    fun markQueued(eventId: String, waiting: Int) = update { current ->
        val now = Instant.now()
        current.copy(
            waiting = waiting,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    deliveryState = RelayDeliveryState.WAITING,
                    deliveryDetail = "Queued for Cortex Local Bus V1",
                    updatedAt = now,
                )
            },
        )
    }

    fun markForwarded(eventId: String, waiting: Int) = update { current ->
        val now = Instant.now()
        current.copy(
            forwarded = current.forwarded + 1,
            waiting = waiting,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    deliveryState = RelayDeliveryState.FORWARDED,
                    deliveryDetail = "Messenger.send() accepted (V1; not correlated ACK)",
                    updatedAt = now,
                )
            },
        )
    }

    fun markRetry(eventId: String, reason: String) = update { current ->
        val now = Instant.now()
        current.copy(
            failedRetries = current.failedRetries + 1,
            lastError = reason,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    deliveryState = RelayDeliveryState.RETRYING,
                    deliveryDetail = reason,
                    updatedAt = now,
                )
            },
        )
    }

    fun markDroppedDeliveryCopy(eventId: String, reason: String) = update { current ->
        val now = Instant.now()
        current.copy(
            failedRetries = current.failedRetries + 1,
            lastError = reason,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    deliveryState = RelayDeliveryState.FAILED,
                    deliveryDetail = reason,
                    updatedAt = now,
                )
            },
        )
    }

    fun markWaiting(waiting: Int) = update { current -> current.copy(waiting = waiting) }

    fun markConnection(state: RelayConnectionState) = update { current ->
        current.copy(connectionState = state, lastActivityAt = Instant.now())
    }

    fun markFailure(reason: String) = update { current ->
        current.copy(
            failedRetries = current.failedRetries + 1,
            lastError = reason,
            lastActivityAt = Instant.now(),
        )
    }

    private fun upsert(
        current: List<RelayRecentSignal>,
        signal: RelayRecentSignal,
    ): List<RelayRecentSignal> = buildList {
        add(signal)
        current.asSequence()
            .filterNot { it.eventId == signal.eventId }
            .take(MAX_RECENT_SIGNALS - 1)
            .forEach(::add)
    }

    private fun mutate(
        current: List<RelayRecentSignal>,
        eventId: String,
        transform: (RelayRecentSignal) -> RelayRecentSignal,
    ): List<RelayRecentSignal> {
        val index = current.indexOfFirst { it.eventId == eventId }
        if (index < 0) return current
        val updated = transform(current[index])
        return current.toMutableList().apply { this[index] = updated }
    }

    private inline fun update(transform: (RelayDiagnosticSnapshot) -> RelayDiagnosticSnapshot) {
        synchronized(lock) {
            mutableState.value = transform(mutableState.value)
        }
    }
}
