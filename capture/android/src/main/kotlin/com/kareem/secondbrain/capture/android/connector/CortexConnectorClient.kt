package com.kareem.secondbrain.capture.android.connector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import com.kareem.secondbrain.capture.android.notification.RelayActionRuntimeRegistry
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional live tunnel into Cortex with a disk-backed delivery outbox.
 *
 * Local Bus V1 remains the compatibility baseline. Relay advertises optional v2 capabilities in an
 * additional hello field; it sends CORTEX_SIGNAL_V2 only if Cortex explicitly selects it. During
 * the fresh-Cortex migration Relay prefers the rebuild endpoint when that service is installed and
 * falls back to the legacy Cortex endpoint only when the rebuild endpoint is absent. The durable
 * event, V1/V2 negotiation and ACK semantics are unchanged.
 */
object CortexConnectorClient {
    private const val TAG = "CortexConnector"
    private const val REBUILD_PACKAGE = "com.kareem.cortex.rebuild"
    private const val REBUILD_SERVICE = "com.kareem.cortex.rebuild.CortexLocalBusService"
    private const val LEGACY_PACKAGE = "com.kareem.cortex"
    private const val LEGACY_SERVICE = "com.kareem.cortex.CortexLocalBusService"
    private const val ACTION_BIND = "com.kareem.cortex.LOCAL_BUS_V1"
    private const val PROTOCOL_V1 = "CORTEX_INGEST_V1"
    private const val CONNECTOR_ID = "second_brain"

    private const val MSG_HELLO = 1
    private const val MSG_INGEST = 2
    private const val MSG_ACK = 100
    private const val MSG_ERROR = 101

    private const val MAX_MEMORY_PENDING = 128
    private const val MAX_PAYLOAD_BYTES = 128 * 1024
    private const val MAX_RETRY_DELAY_MS = 30_000L
    private const val ACK_TIMEOUT_MS = 8_000L
    private const val OUTBOX_DIRECTORY = "cortex-relay-outbox-v1"

    private const val KEY_CONNECTOR_ID = "connector_id"
    private const val KEY_CAPABILITIES_JSON = "capabilities_json"
    private const val KEY_RELAY_CAPABILITIES_JSON = "relay_capabilities_json"
    private const val KEY_EVENT_JSON = "event_json"
    private const val KEY_EVENT_ID = "event_id"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_SIGNAL_ID = "signal_id"

    private data class CortexEndpoint(
        val packageName: String,
        val serviceName: String,
        val label: String,
    )

    private val endpoints = listOf(
        CortexEndpoint(REBUILD_PACKAGE, REBUILD_SERVICE, "fresh"),
        CortexEndpoint(LEGACY_PACKAGE, LEGACY_SERVICE, "legacy"),
    )

    private data class PendingEvent(
        val eventId: String,
        val raw: String,
    ) {
        val wireEventId: String = "sb_$eventId"
    }

    private val queueLock: Any = Any()
    private val queue: ArrayDeque<PendingEvent> = ArrayDeque()
    private val bindActive: AtomicBoolean = AtomicBoolean(false)
    private val draining: AtomicBoolean = AtomicBoolean(false)
    private val retryAttempt: AtomicInteger = AtomicInteger(0)
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val ackTimeoutToken: Any = Any()

    @Volatile private var remote: Messenger? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var endpointReady: Boolean = false
    @Volatile private var inFlight: PendingEvent? = null
    @Volatile private var inFlightSentAtElapsedMs: Long = 0L
    @Volatile private var outboxLoaded: Boolean = false
    @Volatile private var selectedProtocol: String = PROTOCOL_V1
    @Volatile private var activeEndpoint: CortexEndpoint? = null
    private var outbox: DurableRelayOutbox? = null

    private lateinit var connection: ServiceConnection

    private val retryRunnable: Runnable = Runnable {
        val context = appContext ?: return@Runnable
        if (!hasPending() || remote != null) return@Runnable
        if (bindActive.getAndSet(false)) runCatching { context.unbindService(connection) }
        ensureBound(context)
    }

    private val replies: Messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = message.data ?: Bundle.EMPTY
        val eventId = data.getString(KEY_EVENT_ID).orEmpty().trim()
        val status = data.getString(KEY_STATUS).orEmpty().trim()
        val detail = data.getString(KEY_DETAIL).orEmpty().trim()
        val signalId = data.getLong(KEY_SIGNAL_ID, 0L)
        when (message.what) {
            MSG_ACK -> {
                if (eventId.isEmpty()) {
                    selectedProtocol = data.getString(RelayV2Protocol.KEY_SELECTED_PROTOCOL)
                        ?.takeIf { it == RelayV2Protocol.SIGNAL_PROTOCOL }
                        ?: PROTOCOL_V1
                    endpointReady = true
                    retryAttempt.set(0)
                    RelayV2OperationalMetrics.markProtocol(selectedProtocol)
                    RelayRuntimeDiagnostics.markEndpointAck(status.ifEmpty { "READY" })
                    drain()
                } else {
                    handleIngestAck(eventId, status.ifEmpty { "ACCEPTED" }, signalId)
                }
                true
            }
            MSG_ERROR -> {
                if (eventId.isEmpty()) {
                    markFailureForPending(
                        listOf("Cortex Local Bus error", status, detail).filter { it.isNotBlank() }.joinToString(" · "),
                    )
                    resetBindingAndRetry()
                } else {
                    handleIngestError(eventId, status.ifEmpty { "INGEST_FAILED" }, detail)
                }
                true
            }
            RelayV2Protocol.MSG_ACTION_REQUEST -> {
                handleActionRequest(message)
                true
            }
            RelayV2Protocol.MSG_POLICY_UPDATE -> {
                handlePolicyUpdate(message)
                true
            }
            else -> false
        }
    })

    init {
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = service?.let(::Messenger)
                endpointReady = false
                selectedProtocol = PROTOCOL_V1
                retryAttempt.set(0)
                mainHandler.removeCallbacks(retryRunnable)
                RelayRuntimeDiagnostics.markConnection(
                    if (remote != null) RelayConnectionState.CONNECTING else RelayConnectionState.DISCONNECTED,
                )
                if (remote != null) sendHello() else scheduleReconnect()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                val interrupted = inFlight
                remote = null
                endpointReady = false
                selectedProtocol = PROTOCOL_V1
                inFlight = null
                inFlightSentAtElapsedMs = 0L
                cancelAckTimeout()
                if (interrupted != null) {
                    RelayRuntimeDiagnostics.markRetry(
                        interrupted.eventId,
                        "Cortex disconnected before ACK; durable event retained for retry",
                    )
                }
                RelayRuntimeDiagnostics.markConnection(RelayConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onBindingDied(name: ComponentName?) {
                markFailureForPending("Cortex Local Bus binding died; durable event retained")
                resetBindingAndRetry()
            }

            override fun onNullBinding(name: ComponentName?) {
                markFailureForPending("Cortex Local Bus returned a null binding; durable event retained")
                resetBindingAndRetry()
            }
        }
    }

    fun negotiatedProtocol(): String = selectedProtocol

    /** Restore durable pending work whenever the Relay process starts. */
    fun start(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        var corruptFiles = 0
        var restoredEventIds: List<String> = emptyList()
        val waiting = synchronized(queueLock) {
            val store = outbox ?: DurableRelayOutbox(File(applicationContext.noBackupFilesDir, OUTBOX_DIRECTORY)).also {
                outbox = it
            }
            if (!outboxLoaded) {
                val loaded = store.loadAll()
                corruptFiles = loaded.corruptFiles
                restoredEventIds = loaded.entries.map { it.eventId }
                queue.clear()
                loaded.entries.take(MAX_MEMORY_PENDING).forEach { entry ->
                    queue.addLast(PendingEvent(entry.eventId, entry.raw))
                }
                outboxLoaded = true
                updateOutboxMetrics(loaded.entries)
            }
            durableWaitingCountLocked()
        }
        if (restoredEventIds.isNotEmpty()) {
            RelayRuntimeDiagnostics.markRestoredPending(restoredEventIds, waiting)
        } else {
            RelayRuntimeDiagnostics.markWaiting(waiting)
        }
        if (corruptFiles > 0) {
            RelayRuntimeDiagnostics.markFailure(
                "Durable outbox contains $corruptFiles unreadable entr${if (corruptFiles == 1) "y" else "ies"}; preserved for diagnostics",
            )
        }
        if (waiting > 0) {
            ensureBound(applicationContext)
            drain()
        }
    }

    fun enqueueNotification(context: Context, command: CaptureCommand.Notification, storedEventId: String) {
        start(context)
        val raw = buildNotificationEvent(command, storedEventId).toString()
        val store = synchronized(queueLock) { outbox }
        if (store == null) {
            RelayRuntimeDiagnostics.markDroppedDeliveryCopy(
                eventId = storedEventId,
                reason = "Durable outbox unavailable; local capture retained",
            )
            return
        }

        try {
            store.put(storedEventId, raw)
        } catch (t: Throwable) {
            Log.e(TAG, "Durable outbox write failed", t)
            RelayRuntimeDiagnostics.markDroppedDeliveryCopy(
                eventId = storedEventId,
                reason = "Durable outbox write failed: ${t.javaClass.simpleName}; local capture retained",
            )
            return
        }

        val waiting = synchronized(queueLock) { refillMemoryQueueLocked() }
        RelayRuntimeDiagnostics.markQueued(storedEventId, waiting)
        ensureBound(context.applicationContext)
        drain()
    }

    /** Prefer fresh Cortex when installed; legacy remains a compatibility fallback only. */
    private fun selectEndpoint(context: Context): CortexEndpoint? = endpoints.firstOrNull { endpoint ->
        runCatching {
            context.packageManager.getServiceInfo(
                ComponentName(endpoint.packageName, endpoint.serviceName),
                0,
            )
            true
        }.getOrDefault(false)
    }

    private fun ensureBound(context: Context) {
        if (remote != null || !bindActive.compareAndSet(false, true)) return
        RelayRuntimeDiagnostics.markConnection(RelayConnectionState.CONNECTING)
        val endpoint = selectEndpoint(context)
        if (endpoint == null) {
            bindActive.set(false)
            activeEndpoint = null
            RelayRuntimeDiagnostics.markConnection(RelayConnectionState.DISCONNECTED)
            markFailureForPending("No Cortex Local Bus endpoint is installed; durable event retained")
            scheduleReconnect()
            return
        }
        activeEndpoint = endpoint
        try {
            val intent = Intent(ACTION_BIND).apply {
                component = ComponentName(endpoint.packageName, endpoint.serviceName)
            }
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bindActive.set(false)
                RelayRuntimeDiagnostics.markConnection(RelayConnectionState.DISCONNECTED)
                markFailureForPending("Cortex ${endpoint.label} Local Bus bind was rejected; durable event retained")
                scheduleReconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cortex ${endpoint.label} bind failed: ${t.javaClass.simpleName}")
            bindActive.set(false)
            remote = null
            endpointReady = false
            RelayRuntimeDiagnostics.markConnection(RelayConnectionState.DISCONNECTED)
            markFailureForPending("Cortex ${endpoint.label} bind failed: ${t.javaClass.simpleName}; durable event retained")
            scheduleReconnect()
        }
    }

    private fun sendHello() {
        val target = remote ?: return
        try {
            val message = Message.obtain(null, MSG_HELLO)
            message.replyTo = replies
            message.data = Bundle().apply {
                putString(KEY_CONNECTOR_ID, CONNECTOR_ID)
                // Preserve the exact V1 capability field for old Cortex builds.
                putString(KEY_CAPABILITIES_JSON, JSONArray().put("NOTIFICATIONS").toString())
                // New Cortex builds can opt in after inspecting this additional field.
                putString(KEY_RELAY_CAPABILITIES_JSON, RelayV2Protocol.advertisedCapabilities().toString())
            }
            target.send(message)
        } catch (t: Throwable) {
            Log.w(TAG, "Cortex hello failed: ${t.javaClass.simpleName}")
            markFailureForPending("Cortex hello failed: ${t.javaClass.simpleName}; durable event retained")
            resetBindingAndRetry()
        }
    }

    /** Serializes sends and leaves the durable queue head in place until Cortex ACKs it. */
    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            if (!endpointReady) {
                appContext?.let(::ensureBound)
                return
            }
            val target = remote ?: run {
                appContext?.let(::ensureBound)
                scheduleReconnect()
                return
            }
            if (inFlight != null) return
            val pending = synchronized(queueLock) {
                if (queue.isEmpty()) refillMemoryQueueLocked()
                queue.peekFirst()
            } ?: return
            inFlight = pending
            try {
                val useV2 = selectedProtocol == RelayV2Protocol.SIGNAL_PROTOCOL
                val payload = if (useV2) {
                    RelayV2Protocol.fromV1(JSONObject(pending.raw)).toString()
                } else {
                    pending.raw
                }
                val message = Message.obtain(null, if (useV2) RelayV2Protocol.MSG_INGEST_V2 else MSG_INGEST)
                message.replyTo = replies
                message.data = Bundle().apply { putString(KEY_EVENT_JSON, payload) }
                inFlightSentAtElapsedMs = SystemClock.elapsedRealtime()
                target.send(message)
                RelayRuntimeDiagnostics.markSentAwaitingAck(pending.eventId, durableWaitingCount())
                scheduleAckTimeout(pending)
            } catch (t: Throwable) {
                inFlight = null
                inFlightSentAtElapsedMs = 0L
                val reason = "Cortex send failed: ${t.javaClass.simpleName}; retry scheduled"
                Log.w(TAG, reason)
                RelayRuntimeDiagnostics.markRetry(pending.eventId, reason)
                resetBindingAndRetry()
            }
        } finally {
            draining.set(false)
        }
    }

    private fun handleIngestAck(wireEventId: String, status: String, signalId: Long) {
        val pending = inFlight
        if (pending == null || pending.wireEventId != wireEventId) {
            RelayRuntimeDiagnostics.markFailure("Unexpected Cortex ACK for $wireEventId; current=${pending?.wireEventId ?: "none"}")
            return
        }
        val sentAt = inFlightSentAtElapsedMs
        cancelAckTimeout()
        if (!removeDurableHead(pending, "ACK")) return
        inFlight = null
        inFlightSentAtElapsedMs = 0L
        retryAttempt.set(0)
        if (sentAt > 0L) RelayV2OperationalMetrics.markAckLatency(SystemClock.elapsedRealtime() - sentAt)
        val waiting = synchronized(queueLock) { refillMemoryQueueLocked() }
        RelayRuntimeDiagnostics.markForwarded(pending.eventId, waiting, status, signalId)
        drain()
    }

    private fun handleIngestError(wireEventId: String, status: String, detail: String) {
        val pending = inFlight
        if (pending == null || pending.wireEventId != wireEventId) {
            RelayRuntimeDiagnostics.markFailure("Unexpected Cortex ERROR for $wireEventId · $status")
            return
        }
        cancelAckTimeout()
        inFlight = null
        inFlightSentAtElapsedMs = 0L
        if (terminalRejection(status)) {
            if (!removeDurableHead(pending, "terminal rejection")) return
            val waiting = synchronized(queueLock) { refillMemoryQueueLocked() }
            RelayRuntimeDiagnostics.markRejected(pending.eventId, waiting, status, detail)
            drain()
        } else {
            RelayRuntimeDiagnostics.markRetry(
                pending.eventId,
                "Cortex $status${if (detail.isNotBlank()) ": $detail" else ""}; retry scheduled",
            )
            resetBindingAndRetry()
        }
    }

    private fun handleActionRequest(message: Message) {
        val context = appContext ?: return
        val responder = message.replyTo ?: remote ?: return
        val raw = message.data?.getString(RelayV2Protocol.KEY_REQUEST_JSON).orEmpty()
        val resultJson = try {
            if (!endpointReady) error("Cortex endpoint is not ready")
            val request = RelayV2Protocol.parseActionRequest(raw)
            RelayActionRuntimeRegistry.execute(context, request).toJson()
        } catch (t: Throwable) {
            JSONObject().apply {
                put("status", "INVALID_ACTION_REQUEST")
                put("detail", "${t.javaClass.simpleName}${t.message?.let { ": $it" }.orEmpty()}")
            }
        }
        sendControlResult(responder, RelayV2Protocol.MSG_ACTION_RESULT, resultJson)
    }

    private fun handlePolicyUpdate(message: Message) {
        val context = appContext ?: return
        val responder = message.replyTo ?: remote ?: return
        val raw = message.data?.getString(RelayV2Protocol.KEY_REQUEST_JSON).orEmpty()
        val result = RelayMechanicalPolicyStore.forContext(context).applyUpdate(raw)
        sendControlResult(responder, RelayV2Protocol.MSG_POLICY_RESULT, RelayV2Protocol.policyResultJson(result))
    }

    private fun sendControlResult(target: Messenger, what: Int, json: JSONObject) {
        runCatching {
            val result = Message.obtain(null, what)
            result.replyTo = replies
            result.data = Bundle().apply { putString(RelayV2Protocol.KEY_RESULT_JSON, json.toString()) }
            target.send(result)
        }.onFailure { error ->
            RelayRuntimeDiagnostics.markFailure("Could not return Relay control result: ${error.javaClass.simpleName}")
        }
    }

    private fun removeDurableHead(pending: PendingEvent, reason: String): Boolean {
        val store = synchronized(queueLock) { outbox }
        try {
            store?.remove(pending.eventId)
            synchronized(queueLock) {
                val head = queue.peekFirst()
                if (head != null && head.eventId == pending.eventId) queue.removeFirst()
            }
            updateOutboxMetrics(store?.loadAll()?.entries.orEmpty())
            return true
        } catch (t: Throwable) {
            inFlight = null
            inFlightSentAtElapsedMs = 0L
            val errorMessage = "Could not retire durable outbox entry after $reason: ${t.javaClass.simpleName}; same event retained"
            Log.e(TAG, errorMessage, t)
            RelayRuntimeDiagnostics.markRetry(pending.eventId, errorMessage)
            resetBindingAndRetry()
            return false
        }
    }

    private fun terminalRejection(status: String): Boolean = status in setOf(
        "INVALID_EVENT",
        "IDENTITY_MISMATCH",
        "POLICY_BLOCKED",
        "EMPTY",
        "UNKNOWN_MESSAGE",
    )

    private fun scheduleAckTimeout(pending: PendingEvent) {
        cancelAckTimeout()
        mainHandler.postDelayed({
            if (inFlight !== pending) return@postDelayed
            inFlight = null
            inFlightSentAtElapsedMs = 0L
            RelayRuntimeDiagnostics.markRetry(pending.eventId, "Cortex ACK timeout; same durable event retained for retry")
            resetBindingAndRetry()
        }, ackTimeoutToken, ACK_TIMEOUT_MS)
    }

    private fun cancelAckTimeout() {
        mainHandler.removeCallbacksAndMessages(ackTimeoutToken)
    }

    private fun resetBindingAndRetry() {
        cancelAckTimeout()
        endpointReady = false
        selectedProtocol = PROTOCOL_V1
        remote = null
        inFlight = null
        inFlightSentAtElapsedMs = 0L
        RelayV2OperationalMetrics.markProtocol(PROTOCOL_V1)
        RelayRuntimeDiagnostics.markConnection(RelayConnectionState.DISCONNECTED)
        val context = appContext
        if (bindActive.getAndSet(false) && context != null) runCatching { context.unbindService(connection) }
        activeEndpoint = null
        scheduleReconnect()
    }

    private fun markFailureForPending(reason: String) {
        val pending = inFlight ?: synchronized(queueLock) { queue.peekFirst() }
        if (pending != null) {
            RelayRuntimeDiagnostics.markRetry(pending.eventId, reason)
        } else {
            RelayRuntimeDiagnostics.markFailure(reason)
        }
    }

    private fun scheduleReconnect() {
        if (!hasPending()) return
        val attempt = retryAttempt.getAndIncrement().coerceAtMost(5)
        val delay = (1_000L shl attempt).coerceAtMost(MAX_RETRY_DELAY_MS)
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delay)
    }

    private fun hasPending(): Boolean = durableWaitingCount() > 0

    /** Fill only a bounded RAM window; excess pending work remains safely on disk. */
    private fun refillMemoryQueueLocked(): Int {
        val store = outbox ?: return queue.size
        val loaded = store.loadAll()
        val known = queue.asSequence().map { it.eventId }.toMutableSet()
        loaded.entries.asSequence()
            .filterNot { it.eventId in known }
            .take((MAX_MEMORY_PENDING - queue.size).coerceAtLeast(0))
            .forEach { entry ->
                queue.addLast(PendingEvent(entry.eventId, entry.raw))
                known += entry.eventId
            }
        updateOutboxMetrics(loaded.entries)
        return loaded.entries.size
    }

    private fun durableWaitingCount(): Int = synchronized(queueLock) { durableWaitingCountLocked() }

    private fun durableWaitingCountLocked(): Int {
        val entries = outbox?.loadAll()?.entries.orEmpty()
        updateOutboxMetrics(entries)
        return if (outbox == null) queue.size else entries.size
    }

    private fun updateOutboxMetrics(entries: List<DurableOutboxEntry>) {
        val now = System.currentTimeMillis()
        val oldestAge = entries.minOfOrNull { it.enqueuedAtEpochMs }?.let { (now - it).coerceAtLeast(0L) }
        RelayV2OperationalMetrics.markOutbox(entries.size, oldestAge)
    }

    private fun buildNotificationEvent(command: CaptureCommand.Notification, storedEventId: String): JSONObject {
        val metadata = parseMetadata(command.metadataJson)
        val full = eventJson(
            command = command,
            storedEventId = storedEventId,
            metadata = metadata,
            messages = command.messages,
            title = command.title.orEmpty(),
            text = command.body.orEmpty(),
            expandedText = command.expandedText.orEmpty(),
            conversationTitle = command.conversationTitle.orEmpty(),
        )
        if (sizeBytes(full) <= MAX_PAYLOAD_BYTES) return full

        val compactMetadata = compactMetadata(metadata)
        val compactMessages = command.messages.takeLast(12).map { item ->
            item.copy(sender = item.sender?.take(512), text = item.text.take(2_048))
        }
        val compact = eventJson(
            command = command,
            storedEventId = storedEventId,
            metadata = compactMetadata,
            messages = compactMessages,
            title = command.title.orEmpty().take(1_024),
            text = command.body.orEmpty().take(4_096),
            expandedText = command.expandedText.orEmpty().take(8_192),
            conversationTitle = command.conversationTitle.orEmpty().take(1_024),
        ).apply { put("payload_truncated", true) }
        if (sizeBytes(compact) <= MAX_PAYLOAD_BYTES) return compact

        return eventJson(
            command = command,
            storedEventId = storedEventId,
            metadata = compactMetadata,
            messages = compactMessages.takeLast(4).map { it.copy(text = it.text.take(1_024)) },
            title = command.title.orEmpty().take(512),
            text = command.body.orEmpty().take(2_048),
            expandedText = command.expandedText.orEmpty().take(2_048),
            conversationTitle = command.conversationTitle.orEmpty().take(512),
        ).apply { put("payload_truncated", true) }
    }

    private fun eventJson(
        command: CaptureCommand.Notification,
        storedEventId: String,
        metadata: JSONObject,
        messages: List<CaptureCommand.NotificationMessage>,
        title: String,
        text: String,
        expandedText: String,
        conversationTitle: String,
    ): JSONObject {
        val messageJson = JSONArray().apply {
            messages.forEach { item ->
                put(JSONObject().apply {
                    put("sender", item.sender ?: JSONObject.NULL)
                    put("text", item.text)
                    put("timestamp", item.timestamp?.toEpochMilli() ?: JSONObject.NULL)
                })
            }
        }
        return JSONObject().apply {
            put("protocol", PROTOCOL_V1)
            put("event_id", "sb_$storedEventId")
            put("connector_id", CONNECTOR_ID)
            put("source_type", "NOTIFICATION")
            put("source_package", command.packageName)
            put("occurred_at", command.occurredAt.toEpochMilli())
            put("notification_key", command.notificationKey)
            put("title", title)
            put("text", text)
            put("expanded_text", expandedText)
            put("conversation_title", conversationTitle)
            put("ongoing", metadata.optBoolean("isOngoing", false))
            put("messages", messageJson)
            put("metadata", metadata)
        }
    }

    private fun parseMetadata(raw: String?): JSONObject = try {
        raw?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun compactMetadata(source: JSONObject): JSONObject = JSONObject().apply {
        listOf(
            "id",
            "tag",
            "uid",
            "androidUserId",
            "groupKey",
            "isGroup",
            "isGroupSummary",
            "isOngoing",
            "category",
            "channelId",
            "shortcutId",
            "replyable",
            "hasContentIntent",
        ).forEach { key ->
            if (source.has(key)) {
                if (source.isNull(key)) put(key, JSONObject.NULL) else put(key, source.opt(key))
            }
        }

        source.optJSONObject("relay_normalization")?.let { normalization ->
            put("relay_normalization", JSONObject(normalization.toString()))
        }
        source.optJSONObject("relay_semantic_v2")?.let { semantic ->
            put("relay_semantic_v2", JSONObject(semantic.toString()))
        }
        source.optJSONArray("relay_action_capabilities_v1")?.let { actions ->
            put("relay_action_capabilities_v1", JSONArray(actions.toString()))
        }
        source.optJSONArray("relay_entities")?.let { entities ->
            put("relay_entities", JSONArray().apply {
                for (index in 0 until minOf(entities.length(), 64)) {
                    val entity = entities.optJSONObject(index) ?: continue
                    put(JSONObject().apply {
                        listOf("type", "value", "source_field", "start", "end_exclusive", "confidence").forEach { key ->
                            if (entity.has(key)) {
                                val value = entity.opt(key)
                                if (value is String) put(key, value.take(2_048)) else put(key, value)
                            }
                        }
                    })
                }
            })
        }
        put("payload_truncated", true)
        put("relay_entities_truncated", (source.optJSONArray("relay_entities")?.length() ?: 0) > 64)
    }

    private fun sizeBytes(value: JSONObject): Int = value.toString().toByteArray(StandardCharsets.UTF_8).size
}
