package com.kareem.secondbrain.capture.android.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureHealthRepository
import com.kareem.secondbrain.domain.CaptureRepository
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
        if (!captureRunning || sbn.packageName == packageName || sbn.isMechanicalProgressNoise()) return
        val command = sbn.toCaptureCommand()
        serviceScope.launch { captureRepository.ingest(command) }
    }

    override fun onDestroy() {
        serviceScope.launch { healthRepository.setNotificationListenerConnected(false) }
            .invokeOnCompletion { serviceScope.cancel() }
        super.onDestroy()
    }
}

/**
 * Relay may remove only deterministic machine progress noise. It must not decide personal
 * importance: semantic relevance remains Cortex's responsibility. Requiring both an operation
 * marker and a concrete progress counter/percentage keeps this gate deliberately conservative.
 */
private fun StatusBarNotification.isMechanicalProgressNoise(): Boolean {
    val extras = notification.extras
    val text = listOfNotNull(
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
    ).joinToString(" ").replace(Regex("\\s+"), " ").trim().lowercase()

    if (text.isEmpty()) return false

    val operation = MECHANICAL_PROGRESS_OPERATIONS.any(text::contains)
    if (!operation) return false

    return PROGRESS_COUNTER.containsMatchIn(text) ||
        PROGRESS_PERCENT.containsMatchIn(text) ||
        text.contains("items remaining") ||
        text.contains("remaining items") ||
        text.contains(" progress")
}

private val PROGRESS_COUNTER = Regex("(?i)\\b\\d+\\s+(?:of|/)\\s*\\d+\\b")
private val PROGRESS_PERCENT = Regex("(?i)\\b\\d{1,3}%\\b")
private val MECHANICAL_PROGRESS_OPERATIONS = listOf(
    "deleting item",
    "deleting ",
    "uploading ",
    "downloading ",
    "syncing ",
    "processing ",
    "importing ",
    "exporting ",
    "backing up ",
    "restoring ",
    "scanning ",
    "optimizing ",
    "moving item",
    "copying item",
    "preparing ",
)

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
