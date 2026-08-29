package com.kareem.secondbrain.capture.android.connector

import android.content.Context
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisFacts
import com.kareem.secondbrain.capture.android.notification.NotificationSignalAnalysis
import com.kareem.secondbrain.capture.android.notification.RelayActionCapabilityDescriptor
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class RelayForensicStats(
    val recordCount: Int,
    val totalBytes: Long,
    val oldestCapturedAtEpochMs: Long?,
    val newestCapturedAtEpochMs: Long?,
)

data class RelayForensicRecord(
    val eventId: String,
    val capturedAtEpochMs: Long,
    val json: JSONObject,
)

/** Short-lived local operational evidence buffer, never long-term personal memory. */
class RelayForensicBuffer private constructor(private val directory: File) {
    companion object {
        const val SCHEMA = "CORTEX_RELAY_FORENSIC_V2"
        const val MIN_RETENTION_MS = 24L * 60L * 60L * 1000L
        const val RETENTION_MS = 72L * 60L * 60L * 1000L
        const val MAX_RECORDS = 2_000
        const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
        private const val SUFFIX = ".json"
        private val instances = ConcurrentHashMap<String, RelayForensicBuffer>()

        fun forContext(context: Context): RelayForensicBuffer {
            val path = File(context.applicationContext.noBackupFilesDir, "cortex-relay-forensic-v2")
            return instances.getOrPut(path.absolutePath) { RelayForensicBuffer(path) }
        }
    }

    @Synchronized
    fun recordNotification(
        eventId: String,
        command: CaptureCommand.Notification,
        facts: NotificationAnalysisFacts,
        analysis: NotificationSignalAnalysis,
        filterDecision: RelayFilterDecision,
        actionCapabilities: List<RelayActionCapabilityDescriptor>,
        capturedAtEpochMs: Long = System.currentTimeMillis(),
        retentionMs: Long = RETENTION_MS,
    ) {
        ensureDirectory()
        val root = JSONObject().apply {
            put("schema", SCHEMA)
            put("event_id", eventId)
            put("captured_at", capturedAtEpochMs)
            put("command", commandJson(command))
            put("facts", factsJson(facts))
            put("analysis", JSONObject().apply {
                put("source_profile_identity", analysis.sourceProfileIdentity)
                put("notification_identity", analysis.notificationIdentity)
                put("notification_instance_identity", analysis.notificationInstanceIdentity)
                put("conversation_identity", analysis.conversationIdentity)
                put("conversation_identity_basis", analysis.conversationIdentityBasis)
                put("logical_signal_id", analysis.logicalSignalId)
                put("signal_type", analysis.signalType.name)
                put("change", analysis.change.name)
                put("change_reason", analysis.changeReason)
                put("new_message_fingerprints", JSONArray().apply { analysis.newMessageFingerprints.sorted().forEach(::put) })
                put("entities", JSONArray().apply {
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
            })
            put("filter", JSONObject().apply {
                put("state", filterDecision.state.name)
                put("reason", filterDecision.reason)
            })
            put("action_capabilities", JSONArray().apply {
                actionCapabilities.forEach { capability ->
                    put(JSONObject().apply {
                        put("capability_id", capability.capabilityId)
                        put("kind", capability.kind)
                        putNullable("label", capability.label)
                        putNullable("semantic_action", capability.semanticAction)
                        put("requires_text_input", capability.requiresTextInput)
                        put("source", capability.source)
                    })
                }
            })
        }
        writeCrashSafe(fileFor(eventId), root.toString())
        prune(capturedAtEpochMs, retentionMs)
        RelayV2OperationalMetrics.markForensic(stats())
    }

    @Synchronized
    fun get(eventId: String): RelayForensicRecord? {
        ensureDirectory()
        val file = fileFor(eventId)
        if (!file.exists()) return null
        return readRecord(file)
    }

    @Synchronized
    fun recent(limit: Int = 100): List<RelayForensicRecord> {
        ensureDirectory()
        return filesNewestFirst().take(limit.coerceIn(0, MAX_RECORDS)).mapNotNull(::readRecord)
    }

    @Synchronized
    fun stats(): RelayForensicStats {
        ensureDirectory()
        val records = filesNewestFirst().mapNotNull(::readRecord)
        return RelayForensicStats(
            recordCount = records.size,
            totalBytes = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SUFFIX) }.sumOf(File::length),
            oldestCapturedAtEpochMs = records.minOfOrNull { it.capturedAtEpochMs },
            newestCapturedAtEpochMs = records.maxOfOrNull { it.capturedAtEpochMs },
        )
    }

    @Synchronized
    fun prune(
        nowEpochMs: Long = System.currentTimeMillis(),
        retentionMs: Long = RETENTION_MS,
    ) {
        ensureDirectory()
        val boundedRetention = retentionMs.coerceIn(MIN_RETENTION_MS, RETENTION_MS)
        val cutoff = nowEpochMs - boundedRetention
        filesNewestFirst().forEach { file ->
            val captured = runCatching { JSONObject(file.readText()).optLong("captured_at", 0L) }.getOrDefault(0L)
            if (captured <= 0L || captured < cutoff) file.delete()
        }

        var files = filesNewestFirst()
        if (files.size > MAX_RECORDS) {
            files.drop(MAX_RECORDS).forEach(File::delete)
            files = filesNewestFirst()
        }

        var total = files.sumOf(File::length)
        if (total > MAX_TOTAL_BYTES) {
            files.asReversed().forEach { file ->
                if (total <= MAX_TOTAL_BYTES) return@forEach
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    private fun commandJson(command: CaptureCommand.Notification) = JSONObject().apply {
        put("occurred_at", command.occurredAt.toEpochMilli())
        put("package_name", command.packageName)
        put("notification_key", command.notificationKey)
        putNullable("title", command.title)
        putNullable("body", command.body)
        putNullable("expanded_text", command.expandedText)
        putNullable("conversation_title", command.conversationTitle)
        put("messages", JSONArray().apply {
            command.messages.forEach { message ->
                put(JSONObject().apply {
                    putNullable("sender", message.sender)
                    put("text", message.text)
                    putNullable("timestamp", message.timestamp?.toEpochMilli())
                })
            }
        })
        put("metadata_json", command.metadataJson ?: "{}")
    }

    private fun factsJson(facts: NotificationAnalysisFacts) = JSONObject().apply {
        put("package_name", facts.packageName)
        put("notification_key", facts.notificationKey)
        put("android_user_id", facts.androidUserId)
        put("uid", facts.uid)
        putNullable("tag", facts.tag)
        putNullable("shortcut_id", facts.shortcutId)
        putNullable("channel_id", facts.channelId)
        putNullable("category", facts.category)
        put("ongoing", facts.isOngoing)
        putNullable("title", facts.title)
        putNullable("body", facts.body)
        putNullable("expanded_text", facts.expandedText)
        putNullable("conversation_title", facts.conversationTitle)
        put("replyable", facts.replyable)
        put("messages", JSONArray().apply {
            facts.messages.forEach { message ->
                put(JSONObject().apply {
                    putNullable("sender", message.sender)
                    put("text", message.text)
                    putNullable("timestamp", message.timestamp?.toEpochMilli())
                })
            }
        })
        put("people", JSONArray().apply {
            facts.people.forEach { person ->
                put(JSONObject().apply {
                    putNullable("name", person.name)
                    putNullable("key", person.key)
                    putNullable("uri", person.uri)
                })
            }
        })
    }

    private fun readRecord(file: File): RelayForensicRecord? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        RelayForensicRecord(json.getString("event_id"), json.getLong("captured_at"), json)
    }.getOrNull()

    private fun filesNewestFirst(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(SUFFIX) }
        .sortedByDescending(File::lastModified)

    private fun fileFor(eventId: String) = File(directory, "${sha256(eventId)}$SUFFIX")

    private fun writeCrashSafe(target: File, text: String) {
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) error("Could not create forensic directory")
        check(directory.isDirectory) { "Forensic path is not a directory" }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}