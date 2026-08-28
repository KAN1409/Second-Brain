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
import java.io.File
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class BrainNotificationListener : NotificationListenerService() {
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var healthRepository: CaptureHealthRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var captureRunning = false
    private lateinit var lifecycleStore: DurableNotificationLifecycleStore

    override fun onCreate() {
        super.onCreate()
        lifecycleStore = DurableNotificationLifecycleStore(
            File(noBackupFilesDir, "cortex-relay-notification-lifecycle-v1"),
        )
        lifecycleStore.pruneOlderThan(System.currentTimeMillis() - LIFECYCLE_RETENTION_MS)
        CortexConnectorClient.start(applicationContext)
        serviceScope.launch {
            captureRepository.observeCaptureState().collectLatest { state ->
                captureRunning = state.mode == CaptureMode.RUNNING
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        CortexConnectorClient.start(applicationContext)
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
        val observation = sbn.toObservation(importance)
        val baseCommand = observation.command
        val facts = observation.facts
        val notificationIdentity = NotificationSignalAnalyzer.notificationIdentity(facts)
        val lifecycle = lifecycleStore.observePosted(
            notificationIdentity = notificationIdentity,
            visibleFingerprint = NotificationSignalAnalyzer.visibleFingerprint(facts),
            stableChurnFingerprint = NotificationSignalAnalyzer.stableChurnFingerprint(facts),
            messageFingerprints = facts.messages.map(NotificationSignalAnalyzer::messageFingerprint),
            nowEpochMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
        val analysis = NotificationSignalAnalyzer.analyze(facts, lifecycle)
        RelayRuntimeDiagnostics.markLifecycle(lifecycle.state, analysis.change)

        // Exact repeats and deterministic machine-only churn are lifecycle observations, not new
        // evidence. They are intentionally not inserted into long-term capture or sent to Cortex.
        if (analysis.change == NotificationMeaningfulChange.EXACT_DUPLICATE ||
            analysis.change == NotificationMeaningfulChange.MACHINE_CHURN_ONLY
        ) return

        val enrichedCommand = baseCommand
            .withRelayAnalysis(analysis, lifecycle)
            .asMeaningfulDelta(analysis)
        val noiseFacts = sbn.toNoiseFacts(enrichedCommand, analysis)

        serviceScope.launch {
            when (val result = captureRepository.ingest(enrichedCommand)) {
                is CaptureResult.Stored -> {
                    val eventId = result.eventId
                    RelayRuntimeDiagnostics.markCaptured(
                        eventId = eventId,
                        packageName = enrichedCommand.packageName,
                        occurredAt = enrichedCommand.occurredAt,
                        title = enrichedCommand.title ?: enrichedCommand.conversationTitle,
                        preview = enrichedCommand.diagnosticPreview(),
                        body = enrichedCommand.body,
                        expandedText = enrichedCommand.expandedText,
                        conversationTitle = enrichedCommand.conversationTitle,
                        messages = enrichedCommand.messages.map { item ->
                            RelayMessageSnapshot(
                                sender = item.sender,
                                text = item.text,
                                occurredAt = item.timestamp,
                            )
                        },
                        metadataJson = enrichedCommand.metadataJson,
                        logicalSignalId = analysis.logicalSignalId,
                        notificationIdentity = analysis.notificationIdentity,
                        lifecycleState = lifecycle.state.name,
                        updateSequence = lifecycle.sequence,
                        signalType = analysis.signalType.name,
                    )
                    val filterDecision = NotificationNoiseClassifier.classify(noiseFacts)
                    RelayRuntimeDiagnostics.markFilterDecision(
                        eventId = eventId,
                        packageName = enrichedCommand.packageName,
                        decision = filterDecision,
                    )

                    if (filterDecision.state != RelayFilterState.DROP_CONFIRMED_NOISE) {
                        CortexConnectorClient.enqueueNotification(
                            applicationContext,
                            enrichedCommand,
                            eventId,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!captureRunning || sbn.packageName == packageName || !::lifecycleStore.isInitialized) return
        val identity = NotificationSignalAnalyzer.notificationIdentity(sbn.toMinimalAnalysisFacts())
        val lifecycle = lifecycleStore.markRemoved(identity, System.currentTimeMillis())
        RelayRuntimeDiagnostics.markLifecycle(lifecycle.state, null)
    }

    override fun onDestroy() {
        serviceScope.launch { healthRepository.setNotificationListenerConnected(false) }
            .invokeOnCompletion { serviceScope.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val LIFECYCLE_RETENTION_MS = 14L * 24L * 60L * 60L * 1000L
    }
}

private data class NotificationObservation(
    val command: CaptureCommand.Notification,
    val facts: NotificationAnalysisFacts,
)

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

private fun CaptureCommand.Notification.withRelayAnalysis(
    analysis: NotificationSignalAnalysis,
    lifecycle: NotificationLifecycleDecision,
): CaptureCommand.Notification {
    val root = try {
        metadataJson?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
    } catch (_: Throwable) {
        JSONObject()
    }
    root.put("relay_normalization", JSONObject().apply {
        put("source_profile_identity", analysis.sourceProfileIdentity)
        put("notification_identity", analysis.notificationIdentity)
        put("notification_instance_identity", analysis.notificationInstanceIdentity)
        put("conversation_identity", analysis.conversationIdentity)
        put("conversation_identity_basis", analysis.conversationIdentityBasis)
        put("logical_signal_id", analysis.logicalSignalId)
        put("lifecycle_state", lifecycle.state.name)
        put("lifecycle_generation", lifecycle.generation)
        put("update_sequence", lifecycle.sequence)
        put("meaningful_change", analysis.change.name)
        put("change_reason", analysis.changeReason)
        put("signal_type", analysis.signalType.name)
    })
    root.put("relay_entities", JSONArray().apply {
        analysis.entities.forEach { entity ->
            put(JSONObject().apply {
                put("type", entity.type)
                put("value", entity.value)
                put("source_field", entity.sourceField)
                put("start", entity.start)
                put("end_exclusive", entity.endExclusive)
                put("confidence", entity.confidence)
            })
        }
    })
    return copy(metadataJson = root.toString())
}

private fun CaptureCommand.Notification.asMeaningfulDelta(analysis: NotificationSignalAnalysis): CaptureCommand.Notification {
    if (analysis.change != NotificationMeaningfulChange.NEW_MESSAGES) return this
    val newMessages = messages.filter { message ->
        NotificationSignalAnalyzer.messageFingerprint(
            NotificationAnalysisMessage(message.sender, message.text, message.timestamp),
        ) in analysis.newMessageFingerprints
    }
    if (newMessages.isEmpty()) return this
    return copy(
        body = newMessages.last().text,
        expandedText = null,
        messages = newMessages,
    )
}

private fun StatusBarNotification.toNoiseFacts(
    command: CaptureCommand.Notification,
    analysis: NotificationSignalAnalysis,
) = NotificationNoiseFacts(
    packageName = packageName,
    title = command.title,
    body = command.body,
    expandedText = command.expandedText,
    isOngoing = isOngoing,
    category = notification.category,
    channelId = notification.channelId,
    meaningfulChange = analysis.change,
    signalType = analysis.signalType,
)

private fun StatusBarNotification.toMinimalAnalysisFacts() = NotificationAnalysisFacts(
    packageName = packageName,
    notificationKey = key,
    androidUserId = userId,
    uid = uid,
    tag = tag,
    shortcutId = notification.shortcutId,
    channelId = notification.channelId,
    category = notification.category,
    isOngoing = isOngoing,
    title = null,
    body = null,
    expandedText = null,
    conversationTitle = null,
    messages = emptyList(),
    people = emptyList(),
    replyable = false,
)

private fun StatusBarNotification.toObservation(importance: Int?): NotificationObservation {
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

    val command = CaptureCommand.Notification(
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

    return NotificationObservation(
        command = command,
        facts = NotificationAnalysisFacts(
            packageName = packageName,
            notificationKey = key,
            androidUserId = userId,
            uid = uid,
            tag = tag,
            shortcutId = notification.shortcutId,
            channelId = notification.channelId,
            category = notification.category,
            isOngoing = isOngoing,
            title = command.title,
            body = command.body,
            expandedText = command.expandedText,
            conversationTitle = command.conversationTitle,
            messages = messages.map { message ->
                NotificationAnalysisMessage(message.sender, message.text, message.timestamp)
            },
            people = people.map { person ->
                NotificationAnalysisPerson(
                    name = person.name?.toString(),
                    key = person.key,
                    uri = person.uri,
                )
            },
            replyable = replyable,
        ),
    )
}
