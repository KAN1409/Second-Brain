package com.kareem.secondbrain.capture.android.notification

import android.content.Context
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded forensic truth for Android notification callbacks before dedupe/filtering.
 *
 * This is operational evidence, not personal memory. It exists so a processed EvidenceEnvelope can
 * always point back to the exact raw callback that produced it, including callbacks later suppressed
 * as exact duplicates or mechanical churn.
 */
class RelayRawSourceLedger private constructor(private val file: File) {
    companion object {
        const val SCHEMA = "CORTEX_RELAY_RAW_SOURCE_LEDGER_V1"
        const val RETENTION_MS = 72L * 60L * 60L * 1000L
        const val MAX_RECORDS = 1_200
        private val instances = ConcurrentHashMap<String, RelayRawSourceLedger>()

        fun forContext(context: Context): RelayRawSourceLedger {
            val file = File(context.applicationContext.noBackupFilesDir, "cortex-relay-raw-source-v1.jsonl")
            return instances.getOrPut(file.absolutePath) { RelayRawSourceLedger(file) }
        }
    }

    @Synchronized
    fun recordNotification(
        command: CaptureCommand.Notification,
        notificationIdentity: String,
        lifecycle: NotificationLifecycleDecision,
        observedAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        val rawId = stableId("raw", notificationIdentity, lifecycle.generation.toString(), lifecycle.sequence.toString())
        val androidMetadata = RelayV2EvidenceBuilder.parseObject(command.metadataJson)
        val record = JSONObject().apply {
            put("schema", SCHEMA)
            put("raw_schema", RelayEvidenceGatewayV1.RAW_SCHEMA)
            put("raw_id", rawId)
            put("adapter", "ANDROID_NOTIFICATION")
            put("mechanism", "NOTIFICATION_LISTENER")
            put("source_package", command.packageName)
            put("notification_key", command.notificationKey)
            put("observed_at", observedAtEpochMs)
            put("occurred_at", command.occurredAt.toEpochMilli())
            put("lifecycle_generation", lifecycle.generation)
            put("revision", lifecycle.sequence)
            put("payload", JSONObject().apply {
                val keys = androidMetadata.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.startsWith("relay_")) continue
                    put(key, androidMetadata.opt(key) ?: JSONObject.NULL)
                }
            })
        }
        append(record, observedAtEpochMs)
        return rawId
    }

    @Synchronized
    fun prune(nowEpochMs: Long = System.currentTimeMillis()) {
        if (!file.exists()) return
        val cutoff = nowEpochMs - RETENTION_MS
        val retained = readRecords()
            .filter { it.optLong("observed_at", 0L) >= cutoff }
            .takeLast(MAX_RECORDS)
        rewrite(retained)
    }

    @Synchronized
    fun stats(nowEpochMs: Long = System.currentTimeMillis()): JSONObject {
        prune(nowEpochMs)
        val records = readRecords()
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("record_count", records.size)
            put("bytes", if (file.exists()) file.length() else 0L)
            put("retention_hours", RETENTION_MS / (60L * 60L * 1000L))
            put("max_records", MAX_RECORDS)
        }
    }

    private fun append(record: JSONObject, nowEpochMs: Long) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(record.toString()).append('\n')
        }
        val records = readRecords()
        val cutoff = nowEpochMs - RETENTION_MS
        val oldest = records.firstOrNull()?.optLong("observed_at", nowEpochMs) ?: nowEpochMs
        if (records.size > MAX_RECORDS || oldest < cutoff) {
            rewrite(records.filter { it.optLong("observed_at", 0L) >= cutoff }.takeLast(MAX_RECORDS))
        }
    }

    private fun readRecords(): List<JSONObject> = runCatching {
        file.takeIf(File::exists)?.readLines(Charsets.UTF_8).orEmpty().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun rewrite(records: List<JSONObject>) {
        file.parentFile?.mkdirs()
        if (records.isEmpty()) {
            if (file.exists()) file.writeText("", Charsets.UTF_8)
            return
        }
        file.writeText(records.joinToString("\n", postfix = "\n") { it.toString() }, Charsets.UTF_8)
    }

    private fun stableId(prefix: String, vararg values: String): String {
        val joined = values.joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return prefix + "_" + digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
    }
}