package com.kareem.secondbrain.capture.android.connector

import com.kareem.secondbrain.capture.android.notification.NotificationLifecycleState
import com.kareem.secondbrain.capture.android.notification.NotificationMeaningfulChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class RelayConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class RelayFilterState { FORWARD, LOW_VALUE, DROP_CONFIRMED_NOISE }
enum class RelayDeliveryState { CAPTURED, WAITING, SENT, FORWARDED, FILTERED, RETRYING, REJECTED, FAILED }

data class RelayFilterDecision(
    val state: RelayFilterState,
    val reason: String,
)

data class RelayMessageSnapshot(
    val sender: String?,
    val text: String,
    val occurredAt: Instant?,
)

/** Exact process-local notification observation before Relay dedupe/filter/routing. */
data class RelayRawNotification(
    val id: String,
    val notificationKey: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val packageName: String,
    val title: String?,
    val body: String?,
    val conversationTitle: String?,
)

data class RelayRecentSignal(
    val eventId: String,
    val occurredAt: Instant,
    val capturedAt: Instant,
    val updatedAt: Instant,
    val packageName: String,
    val title: String?,
    val preview: String?,
    val body: String? = null,
    val expandedText: String? = null,
    val conversationTitle: String? = null,
    val messages: List<RelayMessageSnapshot> = emptyList(),
    val metadataJson: String? = null,
    val logicalSignalId: String? = null,
    val sourceProfileIdentity: String? = null,
    val notificationIdentity: String? = null,
    val notificationInstanceIdentity: String? = null,
    val conversationIdentity: String? = null,
    val conversationIdentityBasis: String? = null,
    val lifecycleState: String? = null,
    val updateSequence: Int? = null,
    val signalType: String? = null,
    val filterState: RelayFilterState? = null,
    val filterReason: String? = null,
    val deliveryState: RelayDeliveryState = RelayDeliveryState.CAPTURED,
    val deliveryDetail: String = "Stored locally",
    val cortexStatus: String? = null,
    val cortexSignalId: Long = 0,
    /** Actual Messenger.send() ingest attempts for this event in the current process. */
    val sendAttempts: Int = 0,
    /** Retry/failure incidents attributable to this event in the current process. */
    val deliveryIssueIncidents: Int = 0,
)

data class RelayDiagnosticSnapshot(
    /** Every NotificationListener callback observed before Relay processing. */
    val rawReceived: Long = 0,
    val captured: Long = 0,
    val sent: Long = 0,
    /** Number of events explicitly ACKed as accepted by Cortex. */
    val forwarded: Long = 0,
    val rejected: Long = 0,
    val filtered: Long = 0,
    val lowValueForwarded: Long = 0,
    val waiting: Int = 0,
    val failedRetries: Long = 0,
    val lifecyclePosted: Long = 0,
    val lifecycleUpdated: Long = 0,
    val lifecycleRemoved: Long = 0,
    val duplicateUpdatesSuppressed: Long = 0,
    val machineChurnSuppressed: Long = 0,
    val restoredPendingEventIds: List<String> = emptyList(),
    val connectionState: RelayConnectionState = RelayConnectionState.DISCONNECTED,
    val lastPackage: String? = null,
    val lastFilterState: RelayFilterState? = null,
    val lastFilterReason: String? = null,
    val lastError: String? = null,
    val lastCortexStatus: String? = null,
    val lastCortexSignalId: Long = 0,
    val lastAckAt: Instant? = null,
    val lastActivityAt: Instant? = null,
    val rawNotifications: List<RelayRawNotification> = emptyList(),
    val recentSignals: List<RelayRecentSignal> = emptyList(),
)

/**
 * Process-local operational telemetry for the Relay UI.
 *
 * This is deliberately not personal memory and is not part of the Cortex wire protocol. Counters
 * and recent feeds reset when the app process restarts. Raw notifications are captured before
 * dedupe/filter/routing so the UI can show exactly what Android delivered to Relay this session.
 */
object RelayRuntimeDiagnostics {
    private const val MAX_RECENT_SIGNALS = 250
    private const val MAX_RAW_NOTIFICATIONS = 250
    private const val MAX_RESTORED_IDS = 128

    private val lock = Any()
    private val mutableState = MutableStateFlow(RelayDiagnosticSnapshot())
    val state: StateFlow<RelayDiagnosticSnapshot> = mutableState.asStateFlow()

    fun markRawNotification(
        notificationKey: String,
        packageName: String,
        occurredAt: Instant,
        title: String?,
        body: String?,
        conversationTitle: String?,
    ) = update { current ->
        val now = Instant.now()
        val sequence = current.rawReceived + 1
        val item = RelayRawNotification(
            id = "$notificationKey:${now.toEpochMilli()}:$sequence",
            notificationKey = notificationKey,
            occurredAt = occurredAt,
            receivedAt = now,
            packageName = packageName,
            title = title?.takeIf(String::isNotBlank),
            body = body?.takeIf(String::isNotBlank),
            conversationTitle = conversationTitle?.takeIf(String::isNotBlank),
        )
        current.copy(
            rawReceived = sequence,
            lastPackage = packageName,
            lastActivityAt = now,
            rawNotifications = buildList {
                add(item)
                current.rawNotifications.take(MAX_RAW_NOTIFICATIONS - 1).forEach(::add)
            },
        )
    }

    fun markLifecycle(state: NotificationLifecycleState, change: NotificationMeaningfulChange?) = update { current ->
        current.copy(
            lifecyclePosted = current.lifecyclePosted + if (state == NotificationLifecycleState.POSTED) 1 else 0,
            lifecycleUpdated = current.lifecycleUpdated + if (state == NotificationLifecycleState.UPDATED) 1 else 0,
            lifecycleRemoved = current.lifecycleRemoved + if (state == NotificationLifecycleState.REMOVED) 1 else 0,
            duplicateUpdatesSuppressed = current.duplicateUpdatesSuppressed + if (change == NotificationMeaningfulChange.EXACT_DUPLICATE) 1 else 0,
            machineChurnSuppressed = current.machineChurnSuppressed + if (change == NotificationMeaningfulChange.MACHINE_CHURN_ONLY) 1 else 0,
            lastActivityAt = Instant.now(),
        )
    }

    fun markRestoredPending(eventIds: List<String>, waiting: Int) = update { current ->
        current.copy(
            waiting = waiting,
            restoredPendingEventIds = eventIds.take(MAX_RESTORED_IDS),
            lastActivityAt = Instant.now(),
        )
    }

    fun markCaptured(
        eventId: String,
        packageName: String,
        occurredAt: Instant,
        title: String?,
        preview: String?,
        body: String?,
        expandedText: String?,
        conversationTitle: String?,
        messages: List<RelayMessageSnapshot>,
        metadataJson: String?,
        logicalSignalId: String? = null,
        sourceProfileIdentity: String? = null,
        notificationIdentity: String? = null,
        notificationInstanceIdentity: String? = null,
        conversationIdentity: String? = null,
        conversationIdentityBasis: String? = null,
        lifecycleState: String? = null,
        updateSequence: Int? = null,
        signalType: String? = null,
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
                    body = body?.takeIf(String::isNotBlank),
                    expandedText = expandedText?.takeIf(String::isNotBlank),
                    conversationTitle = conversationTitle?.takeIf(String::isNotBlank),
                    messages = messages.takeLast(24),
                    metadataJson = metadataJson?.takeIf(String::isNotBlank),
                    logicalSignalId = logicalSignalId,
                    sourceProfileIdentity = sourceProfileIdentity,
                    notificationIdentity = notificationIdentity,
                    notificationInstanceIdentity = notificationInstanceIdentity,
                    conversationIdentity = conversationIdentity,
                    conversationIdentityBasis = conversationIdentityBasis,
                    lifecycleState = lifecycleState,
                    updateSequence = updateSequence,
                    signalType = signalType,
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
                    deliveryState = if (decision.state == RelayFilterState.DROP_CONFIRMED_NOISE) RelayDeliveryState.FILTERED else signal.deliveryState,
                    deliveryDetail = if (decision.state == RelayFilterState.DROP_CONFIRMED_NOISE) {
                        "Stored locally; forwarding suppressed as confirmed noise"
                    } else signal.deliveryDetail,
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
                    deliveryDetail = "Queued durably for Cortex Local Bus V1",
                    updatedAt = now,
                )
            },
        )
    }

    fun markSentAwaitingAck(eventId: String, waiting: Int) = update { current ->
        val now = Instant.now()
        current.copy(
            sent = current.sent + 1,
            waiting = waiting,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    deliveryState = RelayDeliveryState.SENT,
                    deliveryDetail = "Sent to Cortex; durable copy retained until correlated ACK",
                    sendAttempts = signal.sendAttempts + 1,
                    updatedAt = now,
                )
            },
        )
    }

    fun markForwarded(eventId: String, waiting: Int, status: String, signalId: Long) = update { current ->
        val now = Instant.now()
        current.copy(
            forwarded = current.forwarded + 1,
            waiting = waiting,
            restoredPendingEventIds = current.restoredPendingEventIds.filterNot { it == eventId },
            lastCortexStatus = status,
            lastCortexSignalId = signalId,
            lastAckAt = now,
            lastError = null,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    deliveryState = RelayDeliveryState.FORWARDED,
                    deliveryDetail = "Cortex ACK: $status${if (signalId > 0) " · signal $signalId" else ""}",
                    cortexStatus = status,
                    cortexSignalId = signalId,
                    updatedAt = now,
                )
            },
        )
    }

    fun markRejected(eventId: String, waiting: Int, status: String, detail: String) = update { current ->
        val now = Instant.now()
        val reason = listOf(status, detail).filter(String::isNotBlank).joinToString(" · ")
        current.copy(
            rejected = current.rejected + 1,
            waiting = waiting,
            restoredPendingEventIds = current.restoredPendingEventIds.filterNot { it == eventId },
            lastError = reason,
            lastCortexStatus = status,
            lastAckAt = now,
            lastActivityAt = now,
            recentSignals = mutate(current.recentSignals, eventId) { signal ->
                signal.copy(
                    deliveryState = RelayDeliveryState.REJECTED,
                    deliveryDetail = "Cortex rejected delivery: $reason",
                    cortexStatus = status,
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
                    deliveryIssueIncidents = signal.deliveryIssueIncidents + 1,
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
                    deliveryIssueIncidents = signal.deliveryIssueIncidents + 1,
                    updatedAt = now,
                )
            },
        )
    }

    fun markWaiting(waiting: Int) = update { current -> current.copy(waiting = waiting) }

    fun markConnection(state: RelayConnectionState) = update { current ->
        current.copy(connectionState = state, lastActivityAt = Instant.now())
    }

    fun markEndpointAck(status: String) = update { current ->
        val now = Instant.now()
        current.copy(
            connectionState = RelayConnectionState.CONNECTED,
            lastCortexStatus = status,
            lastAckAt = now,
            lastActivityAt = now,
        )
    }

    fun markFailure(reason: String) = update { current ->
        current.copy(
            failedRetries = current.failedRetries + 1,
            lastError = reason,
            lastActivityAt = Instant.now(),
        )
    }

    private fun upsert(current: List<RelayRecentSignal>, signal: RelayRecentSignal): List<RelayRecentSignal> = buildList {
        add(signal)
        current.asSequence().filterNot { it.eventId == signal.eventId }.take(MAX_RECENT_SIGNALS - 1).forEach(::add)
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
        synchronized(lock) { mutableState.value = transform(mutableState.value) }
    }
}
