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
import android.os.RemoteException
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional one-way live tunnel into Cortex. Second Brain remains authoritative for its own capture:
 * connector failure never blocks or rolls back local storage.
 */
object CortexConnectorClient {
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

    private const val KEY_CONNECTOR_ID = "connector_id"
    private const val KEY_CAPABILITIES_JSON = "capabilities_json"
    private const val KEY_EVENT_JSON = "event_json"

    private val queue = ConcurrentLinkedQueue<String>()
    private val binding = AtomicBoolean(false)
    @Volatile private var remote: Messenger? = null
    @Volatile private var appContext: Context? = null

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MSG_ACK, MSG_ERROR -> true
            else -> false
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service?.let(::Messenger)
            binding.set(false)
            sendHello()
            drain()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding.set(false)
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            binding.set(false)
        }

        override fun onNullBinding(name: ComponentName?) {
            remote = null
            binding.set(false)
        }
    }

    fun enqueueNotification(context: Context, command: CaptureCommand.Notification, storedEventId: String) {
        val event = buildNotificationEvent(command, storedEventId)
        while (queue.size >= MAX_PENDING) queue.poll()
        queue.offer(event.toString())
        appContext = context.applicationContext
        ensureBound(context.applicationContext)
        drain()
    }

    private fun ensureBound(context: Context) {
        if (remote != null || !binding.compareAndSet(false, true)) return
        try {
            val intent = Intent(ACTION_BIND).apply {
                component = ComponentName(CORTEX_PACKAGE, CORTEX_SERVICE)
            }
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding.set(false)
            }
        } catch (_: Throwable) {
            binding.set(false)
            remote = null
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
        } catch (_: Throwable) {
            remote = null
        }
    }

    private fun drain() {
        val target = remote ?: run {
            appContext?.let(::ensureBound)
            return
        }
        while (true) {
            val raw = queue.peek() ?: return
            try {
                val message = Message.obtain(null, MSG_INGEST)
                message.replyTo = replies
                message.data = Bundle().apply { putString(KEY_EVENT_JSON, raw) }
                target.send(message)
                queue.poll()
            } catch (_: RemoteException) {
                remote = null
                binding.set(false)
                appContext?.let(::ensureBound)
                return
            } catch (_: Throwable) {
                queue.poll()
            }
        }
    }

    private fun buildNotificationEvent(command: CaptureCommand.Notification, storedEventId: String): JSONObject {
        val metadata = try {
            command.metadataJson?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
        val messages = JSONArray().apply {
            command.messages.forEach { item ->
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
            put("title", command.title ?: "")
            put("text", command.body ?: "")
            put("expanded_text", command.expandedText ?: "")
            put("conversation_title", command.conversationTitle ?: "")
            put("ongoing", metadata.optBoolean("isOngoing", false))
            put("messages", messages)
            put("metadata", metadata)
        }
    }
}
