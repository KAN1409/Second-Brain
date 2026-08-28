package com.kareem.secondbrain.capture.android.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class RelayConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class RelayFilterState { FORWARD, LOW_VALUE, DROP_CONFIRMED_NOISE }

data class RelayFilterDecision(
    val state: RelayFilterState,
    val reason: String,
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
)

/**
 * Process-local operational telemetry for the Relay UI.
 *
 * This is deliberately not personal memory and is not part of the Cortex wire protocol. Counters
 * reset when the app process restarts. Durable delivery/accounting belongs to the coordinated V2
 * outbox + correlated-ACK work.
 */
object RelayRuntimeDiagnostics {
    private val lock = Any()
    private val mutableState = MutableStateFlow(RelayDiagnosticSnapshot())
    val state: StateFlow<RelayDiagnosticSnapshot> = mutableState.asStateFlow()

    fun markCaptured(packageName: String) = update { current ->
        current.copy(
            captured = current.captured + 1,
            lastPackage = packageName,
            lastActivityAt = Instant.now(),
        )
    }

    fun markFilterDecision(packageName: String, decision: RelayFilterDecision) = update { current ->
        current.copy(
            filtered = current.filtered + if (decision.state == RelayFilterState.DROP_CONFIRMED_NOISE) 1 else 0,
            lowValueForwarded = current.lowValueForwarded + if (decision.state == RelayFilterState.LOW_VALUE) 1 else 0,
            lastPackage = packageName,
            lastFilterState = decision.state,
            lastFilterReason = decision.reason,
            lastActivityAt = Instant.now(),
        )
    }

    fun markForwarded(waiting: Int) = update { current ->
        current.copy(
            forwarded = current.forwarded + 1,
            waiting = waiting,
            lastActivityAt = Instant.now(),
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

    private inline fun update(transform: (RelayDiagnosticSnapshot) -> RelayDiagnosticSnapshot) {
        synchronized(lock) {
            mutableState.value = transform(mutableState.value)
        }
    }
}
