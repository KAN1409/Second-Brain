package com.kareem.secondbrain.capture.android.connector

import android.content.Context
import com.kareem.secondbrain.capture.android.notification.NotificationMeaningfulChange
import com.kareem.secondbrain.capture.android.notification.NotificationNoiseFacts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

enum class RelayMechanicalNoiseRule {
    MACHINE_CHURN,
    GROUP_SUMMARY,
    SYSTEM_CHARGING,
    MEDIA_TRANSPORT,
}

data class RelayMechanicalPolicy(
    val version: Long,
    val disabledNoiseRules: Set<RelayMechanicalNoiseRule>,
    val forensicRetentionHours: Int,
) {
    val forensicRetentionMs: Long get() = forensicRetentionHours.toLong() * 60L * 60L * 1000L
}

data class RelayPolicyUpdateResult(
    val accepted: Boolean,
    val status: String,
    val detail: String,
    val policy: RelayMechanicalPolicy,
)

/**
 * Cortex -> Relay feedback deliberately limited to mechanical capture behavior.
 *
 * Exact duplicate snapshots are outside this policy because they contain no new evidence and are
 * discarded before a stored Relay signal exists. For stored evidence, policy may only DISABLE an
 * existing confirmed-noise suppression (preserve more evidence) and choose 24–72h forensic expiry.
 */
class RelayMechanicalPolicyStore private constructor(private val file: File) {
    companion object {
        const val SCHEMA = "CORTEX_RELAY_MECHANICAL_POLICY_V1"
        private val instances = ConcurrentHashMap<String, RelayMechanicalPolicyStore>()

        fun forContext(context: Context): RelayMechanicalPolicyStore {
            val file = File(context.applicationContext.noBackupFilesDir, "cortex-relay-mechanical-policy-v1.json")
            return instances.getOrPut(file.absolutePath) { RelayMechanicalPolicyStore(file) }
        }
    }

    @Volatile private var cached: RelayMechanicalPolicy? = null

    @Synchronized
    fun current(): RelayMechanicalPolicy {
        cached?.let { return it }
        val loaded = if (file.exists()) runCatching { parse(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() else null
        return (loaded ?: RelayMechanicalPolicy(0L, emptySet(), 72)).also {
            cached = it
            RelayV2OperationalMetrics.markPolicyVersion(it.version)
        }
    }

    @Synchronized
    fun applyUpdate(raw: String): RelayPolicyUpdateResult {
        val before = current()
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return RelayPolicyUpdateResult(false, "INVALID_POLICY", "Policy is not valid JSON", before)
        }
        val allowed = setOf("schema", "version", "disabled_noise_rules", "forensic_retention_hours")
        val unknown = buildList {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in allowed) add(key)
            }
        }
        if (unknown.isNotEmpty()) {
            return RelayPolicyUpdateResult(
                false,
                "UNSUPPORTED_POLICY_FIELDS",
                "Relay refuses unsupported/personal policy fields: ${unknown.sorted().joinToString()}",
                before,
            )
        }
        if (json.optString("schema", SCHEMA) != SCHEMA) {
            return RelayPolicyUpdateResult(false, "UNSUPPORTED_POLICY_SCHEMA", "Expected $SCHEMA", before)
        }
        val requestedVersion = json.optLong("version", before.version + 1L)
        if (requestedVersion <= before.version) {
            return RelayPolicyUpdateResult(false, "STALE_POLICY", "Policy version must increase beyond ${before.version}", before)
        }
        val disabled = mutableSetOf<RelayMechanicalNoiseRule>()
        val disabledJson = json.optJSONArray("disabled_noise_rules") ?: JSONArray()
        for (index in 0 until disabledJson.length()) {
            val name = disabledJson.optString(index)
            val rule = runCatching { RelayMechanicalNoiseRule.valueOf(name) }.getOrNull()
                ?: return RelayPolicyUpdateResult(false, "UNKNOWN_NOISE_RULE", "Unknown mechanical noise rule: $name", before)
            disabled += rule
        }
        val retention = json.optInt("forensic_retention_hours", before.forensicRetentionHours)
        if (retention !in 24..72) {
            return RelayPolicyUpdateResult(false, "INVALID_RETENTION", "Forensic retention must stay within 24–72 hours", before)
        }
        val next = RelayMechanicalPolicy(requestedVersion, disabled, retention)
        write(next)
        cached = next
        RelayV2OperationalMetrics.markPolicyVersion(next.version)
        return RelayPolicyUpdateResult(
            true,
            "ACCEPTED",
            "Mechanical Relay policy applied. It can only preserve more evidence, never encode personal importance.",
            next,
        )
    }

    /** Apply policy only by undoing an existing confirmed-noise DROP. */
    fun adjust(base: RelayFilterDecision, facts: NotificationNoiseFacts): RelayFilterDecision {
        if (base.state != RelayFilterState.DROP_CONFIRMED_NOISE) return base
        val rule = ruleFor(facts) ?: return base
        if (rule !in current().disabledNoiseRules) return base
        return RelayFilterDecision(
            state = RelayFilterState.FORWARD,
            reason = "Mechanical policy disabled $rule suppression; preserving evidence for Cortex",
        )
    }

    private fun ruleFor(facts: NotificationNoiseFacts): RelayMechanicalNoiseRule? = when {
        facts.meaningfulChange == NotificationMeaningfulChange.MACHINE_CHURN_ONLY -> RelayMechanicalNoiseRule.MACHINE_CHURN
        facts.isGroupSummary -> RelayMechanicalNoiseRule.GROUP_SUMMARY
        facts.packageName == "com.android.systemui" && facts.isOngoing -> RelayMechanicalNoiseRule.SYSTEM_CHARGING
        facts.isOngoing && facts.category.equals("transport", ignoreCase = true) -> RelayMechanicalNoiseRule.MEDIA_TRANSPORT
        else -> null
    }

    private fun parse(json: JSONObject): RelayMechanicalPolicy {
        val disabled = buildSet {
            val array = json.optJSONArray("disabled_noise_rules") ?: JSONArray()
            for (index in 0 until array.length()) {
                runCatching { RelayMechanicalNoiseRule.valueOf(array.optString(index)) }.getOrNull()?.let(::add)
            }
        }
        return RelayMechanicalPolicy(
            version = json.optLong("version", 0L),
            disabledNoiseRules = disabled,
            forensicRetentionHours = json.optInt("forensic_retention_hours", 72).coerceIn(24, 72),
        )
    }

    private fun write(policy: RelayMechanicalPolicy) {
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("schema", SCHEMA)
            put("version", policy.version)
            put("disabled_noise_rules", JSONArray().apply { policy.disabledNoiseRules.map { it.name }.sorted().forEach(::put) })
            put("forensic_retention_hours", policy.forensicRetentionHours)
        }.toString(2)
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}