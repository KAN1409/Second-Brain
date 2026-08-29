package com.kareem.secondbrain.capture.android.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

data class RelayV2MetricsSnapshot(
    val negotiatedProtocol: String = "CORTEX_INGEST_V1",
    val protocolNegotiatedAt: Instant? = null,
    val lastAckLatencyMs: Long? = null,
    val averageAckLatencyMs: Long? = null,
    val maxAckLatencyMs: Long? = null,
    val ackLatencySamples: Long = 0,
    val oldestPendingAgeMs: Long? = null,
    val outboxCount: Int = 0,
    val forensicRecordCount: Int = 0,
    val forensicBytes: Long = 0,
    val replayRuns: Long = 0,
    val replayFailures: Long = 0,
    val policyVersion: Long = 0,
    val actionRequests: Long = 0,
    val actionSucceeded: Long = 0,
    val actionFailed: Long = 0,
    val lastSuccessfulDeliveryAt: Instant? = null,
    val lastActionAt: Instant? = null,
)

/** Process-level v2 health metrics. No personal content is retained here. */
object RelayV2OperationalMetrics {
    private val lock = Any()
    private val mutable = MutableStateFlow(RelayV2MetricsSnapshot())
    val state: StateFlow<RelayV2MetricsSnapshot> = mutable.asStateFlow()
    private var ackLatencyTotalMs: Long = 0L

    fun markProtocol(protocol: String) = update { current ->
        current.copy(negotiatedProtocol = protocol, protocolNegotiatedAt = Instant.now())
    }

    fun markAckLatency(latencyMs: Long) = update { current ->
        val safe = latencyMs.coerceAtLeast(0L)
        ackLatencyTotalMs += safe
        val samples = current.ackLatencySamples + 1L
        current.copy(
            lastAckLatencyMs = safe,
            averageAckLatencyMs = ackLatencyTotalMs / samples,
            maxAckLatencyMs = maxOf(current.maxAckLatencyMs ?: 0L, safe),
            ackLatencySamples = samples,
            lastSuccessfulDeliveryAt = Instant.now(),
        )
    }

    fun markOutbox(count: Int, oldestPendingAgeMs: Long?) = update { current ->
        current.copy(outboxCount = count.coerceAtLeast(0), oldestPendingAgeMs = oldestPendingAgeMs?.coerceAtLeast(0L))
    }

    fun markForensic(stats: RelayForensicStats) = update { current ->
        current.copy(forensicRecordCount = stats.recordCount, forensicBytes = stats.totalBytes)
    }

    fun markReplay(success: Boolean) = update { current ->
        current.copy(
            replayRuns = current.replayRuns + 1L,
            replayFailures = current.replayFailures + if (success) 0L else 1L,
        )
    }

    fun markPolicyVersion(version: Long) = update { current -> current.copy(policyVersion = version.coerceAtLeast(0L)) }

    fun markActionResult(success: Boolean) = update { current ->
        current.copy(
            actionRequests = current.actionRequests + 1L,
            actionSucceeded = current.actionSucceeded + if (success) 1L else 0L,
            actionFailed = current.actionFailed + if (success) 0L else 1L,
            lastActionAt = Instant.now(),
        )
    }

    fun snapshot(): RelayV2MetricsSnapshot = mutable.value

    private inline fun update(transform: (RelayV2MetricsSnapshot) -> RelayV2MetricsSnapshot) {
        synchronized(lock) { mutable.value = transform(mutable.value) }
    }
}