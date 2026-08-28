package com.kareem.secondbrain.capture.android.notification

import android.app.Notification
import android.app.Person
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import com.kareem.secondbrain.capture.android.connector.CortexConnectorClient
import com.kareem.secondbrain.capture.android.connector.RelayFilterState
import com.kareem.secondbrain.capture.android.connector.RelayMessageSnapshot
import com.kareem.secondbrain.capture.android.connector.RelayRuntimeDiagnostics
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

        val ranking = Ranking()
        val importance = if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance else null
        val command = sbn.toCaptureCommand(importance)
        val noiseFacts = sbn.toNoiseFacts(command)

        serviceScope.launch {
            when (val result = captureRepository.ingest(command)) {
                is CaptureResult.Stored -> {
                    val eventId = result.eventId
                    RelayRuntimeDiagnostics.markCaptured(
                        eventId = eventId,
                        packageName = command.packageName,
                        occurredAt = command.occurredAt,
                        title = command.title ?: command.conversationTitle,
                        preview = command.diagnosticPreview(),
                        body = command.body,
                        expandedText = command.expandedText,
                        conversationTitle = command.conversationTitle,
                        messages = command.messages.map { item ->
                            RelayMessageSnapshot(
                                sender = item.sender,
                                text = item.text,
                                occurredAt = item.timestamp,
                            )
                        },
                        metadataJson = command.metadataJson,
                    )
                    val filterDecision = NotificationNoiseClassifier.classify(noiseFacts)
                    RelayRuntimeDiagnostics.markFilterDecision(
                        eventId = eventId,
                        packageName = command.packageName,
                        decision = filterDecision,
                    )

                    if (filterDecision.state != RelayFilterState.DROP_CONFIRMED_NOISE) {
                        CortexConnectorClient.enqueueNotification(
                            applicationContext,
                            command,
                            eventId,
                        )
                    }
                }
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

private fun CaptureCommand.Notification.diagnosticPreview(): String? {
    val message = messages.lastOrNull()?.let { item ->
        listOfNotNull(item.sender?.takeIf(String::isNotBlank), item.text.takeIf(String::isNotBlank))
            .joinToString(": ")
    }
    return sequenceOf(message, expandedText, body, conversationTitle, title)
        .filterNotNull()
        .map { value -> value.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull(String::isNotBlank)
        ?.take(240)
}

private fun StatusBarNotification.toNoiseFacts(command: CaptureCommand.Notification) = NotificationNoiseFacts(
    packageName = packageName,
    title = command.title,
    body = command.body,
    expandedText = command.expandedText,
    isOngoing = isOngoing,
    category = notification.category,
    channelId = notification.channelId,
)

private fun StatusBarNotification.toCaptureCommand(importance: Int?): CaptureCommand.Notification {
    val extras = notification.extras
    val rawMessages = Notification.MessagingStyle.Message
        .getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))

    val messages = rawMessages.mapNotNull { message ->
        val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CaptureCommand.NotificationMessage(
            sender = message.senderPerson?.name?.toString(),
            text = text,
            timestamp = message.timestamp.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
        )
    }

    @Suppress("DEPRECATION")
    val people = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST).orEmpty()
    val actions = notification.actions.orEmpty()
    val replyable = actions.any { action ->
        action.remoteInputs.orEmpty().any { remoteInput -> remoteInput.allowFreeFormInput }
    }

    val metadata = JSONObject().apply {
        put("id", id)
        put("tag", tag ?: JSONObject.NULL)
        put("uid", uid)
        put("androidUserId", userId)
        put("groupKey", groupKey ?: JSONObject.NULL)
        put("overrideGroupKey", overrideGroupKey ?: JSONObject.NULL)
        put("isGroup", isGroup)
        put("isOngoing", isOngoing)
        put("isClearable", isClearable)
        put("category", notification.category ?: JSONObject.NULL)
        put("channelId", notification.channelId ?: JSONObject.NULL)
        put("shortcutId", notification.shortcutId ?: JSONObject.NULL)
        put("sortKey", notification.sortKey ?: JSONObject.NULL)
        put("group", notification.group ?: JSONObject.NULL)
        put("importance", importance ?: JSONObject.NULL)
        put("flags", notification.flags)
        put("notificationWhen", notification.`when`)
        put("postTime", postTime)
        put("replyable", replyable)
        put("actions", JSONArray().apply {
            actions.forEach { action ->
                put(JSONObject().apply {
                    put("title", action.title?.toString() ?: JSONObject.NULL)
                    put("semanticAction", action.semanticAction)
                    put("contextual", action.isContextual)
                    put("remoteInputCount", action.remoteInputs.orEmpty().size)
                    put("replyable", action.remoteInputs.orEmpty().any { it.allowFreeFormInput })
                })
            }
        })
        put("people", JSONArray().apply {
            people.forEach { person ->
                put(JSONObject().apply {
                    put("name", person.name?.toString() ?: JSONObject.NULL)
                    put("key", person.key ?: JSONObject.NULL)
                    put("uri", person.uri ?: JSONObject.NULL)
                    put("bot", person.isBot)
                    put("important", person.isImportant)
                })
            }
        })
        put("messages", JSONArray().apply {
            rawMessages.forEach { message ->
                val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                val sender = message.senderPerson
                put(JSONObject().apply {
                    put("sender", sender?.name?.toString() ?: JSONObject.NULL)
                    put("senderKey", sender?.key ?: JSONObject.NULL)
                    put("senderUri", sender?.uri ?: JSONObject.NULL)
                    put("senderBot", sender?.isBot ?: false)
                    put("senderImportant", sender?.isImportant ?: false)
                    put("text", text)
                    put("timestamp", message.timestamp.takeIf { it > 0L } ?: JSONObject.NULL)
                    put("dataMimeType", message.dataMimeType ?: JSONObject.NULL)
                    put("dataUri", message.dataUri?.toString() ?: JSONObject.NULL)
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
