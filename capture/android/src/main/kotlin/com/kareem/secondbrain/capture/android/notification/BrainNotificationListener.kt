package com.kareem.secondbrain.capture.android.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kareem.secondbrain.capture.android.connector.CortexConnectorClient
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureHealthRepository
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class BrainNotificationListener : NotificationListenerService() {
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var healthRepository: CaptureHealthRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var captureRunning = false

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            captureRepository.observeCaptureState().collectLatest { state ->
                captureRunning = state.mode == CaptureMode.RUNNING
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceScope.launch { healthRepository.setNotificationListenerConnected(true) }
    }

    override fun onListenerDisconnected() {
        serviceScope.launch { healthRepository.setNotificationListenerConnected(false) }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!captureRunning || sbn.packageName == packageName) return
        val command = sbn.toCaptureCommand()
        serviceScope.launch {
            when (val result = captureRepository.ingest(command)) {
                is CaptureResult.Stored -> CortexConnectorClient.enqueueNotification(
                    applicationContext,
                    command,
                    result.eventId,
                )
                else -> Unit
            }
        }
    }

    override fun onDestroy() {
        serviceScope.launch { healthRepository.setNotificationListenerConnected(false) }
            .invokeOnCompletion { serviceScope.cancel() }
        super.onDestroy()
    }
}

private fun StatusBarNotification.toCaptureCommand(): CaptureCommand.Notification {
    val extras = notification.extras
    val messages = Notification.MessagingStyle.Message
        .getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        .mapNotNull { message ->
            val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CaptureCommand.NotificationMessage(
                sender = message.senderPerson?.name?.toString(),
                text = text,
                timestamp = message.timestamp.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
            )
        }

    val metadata = JSONObject().apply {
        put("id", id)
        put("tag", tag ?: JSONObject.NULL)
        put("groupKey", groupKey ?: JSONObject.NULL)
        put("isGroup", isGroup)
        put("isOngoing", isOngoing)
        put("category", notification.category ?: JSONObject.NULL)
        put("channelId", notification.channelId ?: JSONObject.NULL)
        put("messages", JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().apply {
                    put("sender", message.sender ?: JSONObject.NULL)
                    put("text", message.text)
                    put("timestamp", message.timestamp?.toEpochMilli() ?: JSONObject.NULL)
                })
            }
        })
    }.toString()

    return CaptureCommand.Notification(
        occurredAt = Instant.ofEpochMilli(postTime),
        packageName = packageName,
        notificationKey = key,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        expandedText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
        messages = messages,
        metadataJson = metadata,
    )
}
