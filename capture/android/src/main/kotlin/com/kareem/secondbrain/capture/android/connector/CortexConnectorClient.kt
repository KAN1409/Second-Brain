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
import android.util.Log
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional one-way live tunnel into Cortex.
 *
 * Second Brain remains authoritative for capture. Events are offered to Cortex only after local
 * storage succeeds, and connector failure can never roll back or block that local capture.
 *
 * Wire compatibility is intentionally frozen at Local Bus V1 / CORTEX_INGEST_V1.
 */
object CortexConnectorClient {
    private const val TAG = "CortexConnector"
    private const val CORTEX_PACKAGE = "com.kareem.cortex"
    private const val CORTEX_SERVICE = "com.kareem.cortex.CortexLocalBusService"
    private const val ACTION_BIND = "com.kareem.cortex.LOCAL_BUS_V1"
    private const val PROTOCOL = "CORTEX_INGEST_V1"
    private const val CONNECTOR_ID = "second_brain"

    private const val MSG_HELLO = 1
    private const val MSG_INGEST = 2
    private const val MSG_ACK = 100
    private const val MSG_ERROR = 101

    private const val MAX_PENDING = 128
    private const val MAX_PAYLOAD_BYTES = 128 * 1024
    private const val MAX_RETRY_DELAY_MS = 30_000L

    private const val KEY_CONNECTOR_ID = "connector_id"
    private const val KEY_CAPABILITIES_JSON = "capabilities_json"
    private const val KEY_EVENT_JSON = "event_json"

    private data class PendingEvent(val raw: String)

    private val queueLock: Any = Any()
    private val queue: ArrayDeque<PendingEvent> = ArrayDeque()
    private val bindActive: AtomicBoolean = AtomicBoolean(false)
    private val draining: AtomicBoolean = AtomicBoolean(false)
    private val retryAttempt: AtomicInteger = AtomicInteger(0)
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    @Volatile private var remote: Messenger? = null
    @Volatile private var appContext: Context? = null

    private lateinit var connection: ServiceConnection

    private val retryRunnable: Runnable = Runnable {
        val context = appContext ?: return@Runnable
        if (!hasPending() || remote != null) return@Runnable

        // A bind can become stale without delivering a usable Messenger. Reset it before retrying.
        if (bindActive.getAndSet(false)) {
            runCatching { context.unbindService(connection) }
        }
        ensureBound(context)
    }

    private val replies: Messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            // V1 ACK/ERROR messages are intentionally accepted without changing the wire contract.
            // V1 has no required per-event correlation field, so send acceptance remains the queue
            // removal point. A future protocol revision can add durable ACK correlation explicitly.
            MSG_ACK, MSG_ERROR -> true
            else -> false
        }
    })

    init {
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = service?.let(::Messenger)
                retryAttempt.set(0)
                mainHandler.removeCallbacks(retryRunnable)
                sendHello()
                drain()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                scheduleReconnect()
            }

            override fun onBindingDied(name: ComponentName?) {
                resetBindingAndRetry()
            }

            override fun onNullBinding(name: ComponentName?) {
                resetBindingAndRetry()
            }
        }
    }

    fun enqueueNotification(context: Context, command: CaptureCommand.Notification, storedEventId: String) {
        val raw = buildNotificationEvent(command, storedEventId).toString()
        synchronized(queueLock) {
            while (queue.size >= MAX_PENDING) {
                queue.removeFirst()
                Log.w(TAG, "Tunnel queue full; dropped oldest delivery copy (local capture retained)")
            }
            queue.addLast(PendingEvent(raw))
        }
        appContext = context.applicationContext
        ensureBound(context.applicationContext)
        drain()
    }

    private fun ensureBound(context: Context) {
        if (remote != null || !bindActive.compareAndSet(false, true)) return
        try {
            val intent = Intent(ACTION_BIND).apply {
                component = ComponentName(CORTEX_PACKAGE, CORTEX_SERVICE)
            }
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bindActive.set(false)
                scheduleReconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cortex bind failed: ${t.javaClass.simpleName}")
            bindActive.set(false)
            remote = null
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
                putString(KEY_CAPABILITIES_JSON, JSONArray().put("NOTIFICATIONS").toString())
            }
            target.send(message)
        } catch (t: Throwable) {
            Log.w(TAG, "Cortex hello failed: ${t.javaClass.simpleName}")
            resetBindingAndRetry()
        }
    }

    /** Serializes all sends so two notification coroutines cannot transmit the same queue head. */
    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            while (true) {
                val target = remote ?: run {
                    appContext?.let(::ensureBound)
                    scheduleReconnect()
                    return
                }
                val pending = synchronized(queueLock) { queue.peekFirst() } ?: return
                try {
                    val message = Message.obtain(null, MSG_INGEST)
                    message.replyTo = replies
                    message.data = Bundle().apply { putString(KEY_EVENT_JSON, pending.raw) }
                    target.send(message)
                    synchronized(queueLock) {
                        if (queue.peekFirst() === pending) queue.removeFirst()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Cortex send failed: ${t.javaClass.simpleName}")
                    resetBindingAndRetry()
                    return
                }
            }
        } finally {
            draining.set(false)
            if (remote != null && hasPending()) drain()
        }
    }

    private fun resetBindingAndRetry() {
        remote = null
        val context = appContext
        if (bindActive.getAndSet(false) && context != null) {
            runCatching { context.unbindService(connection) }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!hasPending()) return
        val attempt = retryAttempt.getAndIncrement().coerceAtMost(5)
        val delay = (1_000L shl attempt).coerceAtMost(MAX_RETRY_DELAY_MS)
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delay)
    }

    private fun hasPending(): Boolean = synchronized(queueLock) { queue.isNotEmpty() }

    private fun buildNotificationEvent(
        command: CaptureCommand.Notification,
        storedEventId: String,
    ): JSONObject {
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
            item.copy(
                sender = item.sender?.take(512),
                text = item.text.take(2_048),
            )
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

        // Defensive final cap for pathological third-party notification payloads.
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
            put("protocol", PROTOCOL)
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
        listOf("id", "tag", "groupKey", "isGroup", "isOngoing", "category", "channelId").forEach { key ->
            if (source.has(key)) {
                if (source.isNull(key)) put(key, JSONObject.NULL) else put(key, source.opt(key))
            }
        }
        put("payload_truncated", true)
    }

    private fun sizeBytes(value: JSONObject): Int =
        value.toString().toByteArray(StandardCharsets.UTF_8).size
}
