package com.kareem.secondbrain.capture.android.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kareem.secondbrain.capture.android.connector.RelayV2OperationalMetrics
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class RelayActionRequest(
    val requestId: String,
    val logicalSignalId: String,
    val capabilityId: String,
    val inputText: String? = null,
)

data class RelayActionResult(
    val requestId: String,
    val logicalSignalId: String,
    val capabilityId: String,
    val status: String,
    val detail: String,
    val executedAt: Instant = Instant.now(),
) {
    val success: Boolean get() = status == "EXECUTED"

    fun toJson(): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("logical_signal_id", logicalSignalId)
        put("capability_id", capabilityId)
        put("status", status)
        put("detail", detail)
        put("executed_at", executedAt.toString())
    }
}

private enum class RuntimeActionKind { PENDING_INTENT, REPLY, DISMISS }

private data class RuntimeCapability(
    val descriptor: RelayActionCapabilityDescriptor,
    val kind: RuntimeActionKind,
    val pendingIntent: PendingIntent? = null,
    val remoteInputs: Array<RemoteInput> = emptyArray(),
)

private data class RuntimeNotificationActions(
    val notificationKey: String,
    val service: WeakReference<NotificationListenerService>,
    val capabilities: Map<String, RuntimeCapability>,
)

/**
 * Runtime-only registry of Android action handles.
 *
 * PendingIntent/RemoteInput objects never enter Relay's durable evidence or wire payload. Cortex sees
 * only stable capability descriptors and can ask Relay to execute one of those currently live IDs.
 */
object RelayActionRuntimeRegistry {
    private const val MAX_INPUT_CHARS = 4_000
    private val byLogicalSignal = ConcurrentHashMap<String, RuntimeNotificationActions>()
    private val logicalByNotificationKey = ConcurrentHashMap<String, MutableSet<String>>()

    fun register(
        service: NotificationListenerService,
        sbn: StatusBarNotification,
        logicalSignalId: String,
    ): List<RelayActionCapabilityDescriptor> {
        val capabilities = linkedMapOf<String, RuntimeCapability>()
        val notification = sbn.notification

        notification.contentIntent?.let { contentIntent ->
            val descriptor = descriptor(
                logicalSignalId = logicalSignalId,
                token = "content",
                kind = "OPEN",
                label = "Open",
                semanticAction = null,
                requiresText = false,
                source = "Android Notification.contentIntent",
            )
            capabilities[descriptor.capabilityId] = RuntimeCapability(
                descriptor = descriptor,
                kind = RuntimeActionKind.PENDING_INTENT,
                pendingIntent = contentIntent,
            )
        }

        notification.actions.orEmpty().forEachIndexed { index, action ->
            val actionIntent = action.actionIntent ?: return@forEachIndexed
            val freeFormInputs = action.remoteInputs.orEmpty().filter(RemoteInput::getAllowFreeFormInput).toTypedArray()
            val isReply = freeFormInputs.isNotEmpty()
            val kind = if (isReply) "REPLY" else semanticKind(action.semanticAction)
            val descriptor = descriptor(
                logicalSignalId = logicalSignalId,
                token = "action:$index:${action.semanticAction}:${action.title}",
                kind = kind,
                label = action.title?.toString(),
                semanticAction = action.semanticAction,
                requiresText = isReply,
                source = "Android Notification.Action[$index]",
            )
            capabilities[descriptor.capabilityId] = RuntimeCapability(
                descriptor = descriptor,
                kind = if (isReply) RuntimeActionKind.REPLY else RuntimeActionKind.PENDING_INTENT,
                pendingIntent = actionIntent,
                remoteInputs = freeFormInputs,
            )
        }

        if (sbn.isClearable) {
            val descriptor = descriptor(
                logicalSignalId = logicalSignalId,
                token = "dismiss:${sbn.key}",
                kind = "DISMISS",
                label = "Dismiss",
                semanticAction = null,
                requiresText = false,
                source = "Android NotificationListenerService.cancelNotification",
            )
            capabilities[descriptor.capabilityId] = RuntimeCapability(
                descriptor = descriptor,
                kind = RuntimeActionKind.DISMISS,
            )
        }

        val entry = RuntimeNotificationActions(
            notificationKey = sbn.key,
            service = WeakReference(service),
            capabilities = capabilities,
        )
        byLogicalSignal[logicalSignalId] = entry
        logicalByNotificationKey.compute(sbn.key) { _, current ->
            (current ?: linkedSetOf()).apply { add(logicalSignalId) }
        }
        return capabilities.values.map { it.descriptor }
    }

    fun unregisterNotification(notificationKey: String) {
        val logicalIds = logicalByNotificationKey.remove(notificationKey).orEmpty().toList()
        logicalIds.forEach(byLogicalSignal::remove)
    }

    fun descriptors(logicalSignalId: String): List<RelayActionCapabilityDescriptor> =
        byLogicalSignal[logicalSignalId]?.capabilities?.values?.map { it.descriptor }.orEmpty()

    fun execute(context: Context, request: RelayActionRequest): RelayActionResult {
        val entry = byLogicalSignal[request.logicalSignalId]
            ?: return finish(context, request, "STALE_SIGNAL", "No live Android notification action registry exists for this logical signal")
        val capability = entry.capabilities[request.capabilityId]
            ?: return finish(context, request, "UNKNOWN_CAPABILITY", "Capability is not exposed for this live notification")

        if (capability.descriptor.requiresTextInput && request.inputText.isNullOrBlank()) {
            return finish(context, request, "INPUT_REQUIRED", "This Android action requires reply text")
        }
        if ((request.inputText?.length ?: 0) > MAX_INPUT_CHARS) {
            return finish(context, request, "INPUT_TOO_LONG", "Reply text exceeds $MAX_INPUT_CHARS characters")
        }

        val result = try {
            when (capability.kind) {
                RuntimeActionKind.DISMISS -> {
                    val service = entry.service.get()
                        ?: return finish(context, request, "STALE_SIGNAL", "Notification listener instance is no longer alive")
                    service.cancelNotification(entry.notificationKey)
                    RelayActionResult(request.requestId, request.logicalSignalId, request.capabilityId, "EXECUTED", "Android notification dismissed")
                }
                RuntimeActionKind.PENDING_INTENT -> {
                    val pending = capability.pendingIntent
                        ?: return finish(context, request, "STALE_CAPABILITY", "Android PendingIntent is unavailable")
                    pending.send()
                    RelayActionResult(
                        request.requestId,
                        request.logicalSignalId,
                        request.capabilityId,
                        "EXECUTED",
                        "Android ${capability.descriptor.kind} action PendingIntent sent",
                    )
                }
                RuntimeActionKind.REPLY -> {
                    val pending = capability.pendingIntent
                        ?: return finish(context, request, "STALE_CAPABILITY", "Android reply PendingIntent is unavailable")
                    val text = request.inputText.orEmpty()
                    val fillIn = Intent()
                    val results = Bundle().apply {
                        capability.remoteInputs.forEach { input -> putCharSequence(input.resultKey, text) }
                    }
                    RemoteInput.addResultsToIntent(capability.remoteInputs, fillIn, results)
                    pending.send(context, 0, fillIn)
                    RelayActionResult(
                        request.requestId,
                        request.logicalSignalId,
                        request.capabilityId,
                        "EXECUTED",
                        "Reply submitted through Android RemoteInput",
                    )
                }
            }
        } catch (t: PendingIntent.CanceledException) {
            RelayActionResult(request.requestId, request.logicalSignalId, request.capabilityId, "STALE_CAPABILITY", "Android PendingIntent was cancelled")
        } catch (t: Throwable) {
            RelayActionResult(
                request.requestId,
                request.logicalSignalId,
                request.capabilityId,
                "EXECUTION_FAILED",
                "${t.javaClass.simpleName}${t.message?.let { ": $it" }.orEmpty()}",
            )
        }
        RelayActionAuditStore.forContext(context).append(result)
        RelayV2OperationalMetrics.markActionResult(result.success)
        return result
    }

    private fun finish(context: Context, request: RelayActionRequest, status: String, detail: String): RelayActionResult {
        val result = RelayActionResult(request.requestId, request.logicalSignalId, request.capabilityId, status, detail)
        RelayActionAuditStore.forContext(context).append(result)
        RelayV2OperationalMetrics.markActionResult(false)
        return result
    }

    private fun descriptor(
        logicalSignalId: String,
        token: String,
        kind: String,
        label: String?,
        semanticAction: Int?,
        requiresText: Boolean,
        source: String,
    ) = RelayActionCapabilityDescriptor(
        capabilityId = stableId("action", logicalSignalId, token),
        kind = kind,
        label = label?.takeIf(String::isNotBlank),
        semanticAction = semanticAction,
        requiresTextInput = requiresText,
        source = source,
    )

    private fun semanticKind(value: Int): String = when (value) {
        Notification.Action.SEMANTIC_ACTION_REPLY -> "REPLY"
        Notification.Action.SEMANTIC_ACTION_MARK_AS_READ -> "MARK_AS_READ"
        Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD -> "MARK_AS_UNREAD"
        Notification.Action.SEMANTIC_ACTION_DELETE -> "DELETE"
        Notification.Action.SEMANTIC_ACTION_ARCHIVE -> "ARCHIVE"
        Notification.Action.SEMANTIC_ACTION_MUTE -> "MUTE"
        Notification.Action.SEMANTIC_ACTION_UNMUTE -> "UNMUTE"
        Notification.Action.SEMANTIC_ACTION_THUMBS_UP -> "THUMBS_UP"
        Notification.Action.SEMANTIC_ACTION_THUMBS_DOWN -> "THUMBS_DOWN"
        Notification.Action.SEMANTIC_ACTION_CALL -> "CALL"
        else -> "ANDROID_ACTION"
    }

    private fun stableId(prefix: String, vararg values: String): String {
        val joined = values.joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return prefix + "_" + digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Small bounded audit trail for execution results; request text is never stored. */
private class RelayActionAuditStore private constructor(private val file: File) {
    companion object {
        private const val MAX_LINES = 500
        private val instances = ConcurrentHashMap<String, RelayActionAuditStore>()

        fun forContext(context: Context): RelayActionAuditStore {
            val file = File(context.applicationContext.noBackupFilesDir, "cortex-relay-action-audit-v1.jsonl")
            return instances.getOrPut(file.absolutePath) { RelayActionAuditStore(file) }
        }
    }

    @Synchronized
    fun append(result: RelayActionResult) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(result.toJson().toString()).append('\n')
        }
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        if (lines.size > MAX_LINES) {
            file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }
}