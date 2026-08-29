package com.kareem.secondbrain.capture.android.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Short-lived perception layer for Relay. It links grounded phone observations without creating
 * personal memory, importance, priority or relationship claims. All identity links are explicit
 * deterministic candidates with their matching basis and confidence.
 */
class RelayEvidenceIntelligence private constructor(private val file: File) {
    companion object {
        const val SCHEMA = "CORTEX_RELAY_EVIDENCE_INTELLIGENCE_V1"
        private const val MAX_OBSERVATIONS = 320
        private const val MAX_ENTITIES = 320
        private const val OBSERVATION_TTL_MS = 6L * 60L * 60L * 1000L
        private const val ENTITY_TTL_MS = 72L * 60L * 60L * 1000L
        private const val EPISODE_GAP_MS = 5L * 60L * 1000L
        private const val INTERACTION_WINDOW_MS = 10L * 60L * 1000L
        private val instances = ConcurrentHashMap<String, RelayEvidenceIntelligence>()

        fun forContext(context: Context): RelayEvidenceIntelligence {
            val target = File(context.noBackupFilesDir, "cortex-relay-evidence-intelligence-v1.json")
            return instances.getOrPut(target.absolutePath) { RelayEvidenceIntelligence(target) }
        }
    }

    @Synchronized
    fun observeAppActivity(packageName: String, atEpochMs: Long, enteredForeground: Boolean) {
        if (packageName.isBlank()) return
        mutate(atEpochMs) { state ->
            appendObservation(
                state,
                Observation(
                    id = stableId("obs", "APP", packageName, atEpochMs.toString(), enteredForeground.toString()),
                    kind = if (enteredForeground) "APP_FOREGROUND" else "APP_BACKGROUND",
                    packageName = packageName,
                    at = atEpochMs,
                    episodeId = episodeFor(state, atEpochMs, packageName),
                ),
            )
        }
    }

    @Synchronized
    fun observeScreen(packageName: String, text: String, atEpochMs: Long) {
        if (packageName.isBlank() || text.isBlank()) return
        val anchors = screenAnchors(text)
        mutate(atEpochMs) { state ->
            appendObservation(
                state,
                Observation(
                    id = stableId("obs", "SCREEN", packageName, atEpochMs.toString(), stableId("text", text.take(4000))),
                    kind = "SCREEN",
                    packageName = packageName,
                    at = atEpochMs,
                    episodeId = episodeFor(state, atEpochMs, packageName),
                    anchors = anchors,
                ),
            )
        }
    }

    @Synchronized
    fun notificationEnvelope(
        command: com.kareem.secondbrain.domain.CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
        lifecycle: NotificationLifecycleDecision,
    ): JSONObject {
        val now = command.occurredAt.toEpochMilli()
        var output = JSONObject()
        mutate(now) { state ->
            val entities = entityObservations(command, analysis)
            val episodeId = episodeFor(state, now, command.packageName)
            val observation = Observation(
                id = stableId("obs", "NOTIFICATION", analysis.logicalSignalId, lifecycle.sequence.toString()),
                kind = "NOTIFICATION",
                packageName = command.packageName,
                at = now,
                episodeId = episodeId,
                conversationIdentity = analysis.conversationIdentity,
                entityIds = entities.map { it.id },
            )
            appendObservation(state, observation)
            entities.forEach { mergeEntity(state, it, now, command.packageName) }

            val episodeMembers = state.observations.filter { it.episodeId == episodeId }
            val recentSamePackage = state.observations.filter {
                it.packageName == command.packageName && now - it.at in 0..INTERACTION_WINDOW_MS
            }
            val personLabels = buildList {
                command.conversationTitle?.takeIf(String::isNotBlank)?.let(::add)
                command.messages.mapNotNullTo(this) { it.sender?.takeIf(String::isNotBlank) }
                command.title?.takeIf(String::isNotBlank)?.let(::add)
            }.distinct()
            val anchorHashes = personLabels.map { stableId("anchor", normalize(it)) }.toSet()
            val screenMatch = recentSamePackage
                .filter { it.kind == "SCREEN" }
                .filter { item -> item.anchors.any(anchorHashes::contains) }
                .maxByOrNull { it.at }
            val foreground = recentSamePackage
                .filter { it.kind == "APP_FOREGROUND" }
                .maxByOrNull { it.at }

            val resolvedEntities = entities.mapNotNull { entity -> state.entities[entity.id] }
            output = JSONObject().apply {
                put("schema", SCHEMA)
                put("episode", episodeJson(episodeId, episodeMembers, now))
                put("conversation_state", JSONObject().apply {
                    put("conversation_identity", analysis.conversationIdentity)
                    put("notification_update_sequence", lifecycle.sequence)
                    put("lifecycle_state", lifecycle.state.name)
                    put("new_message_count", analysis.newMessageFingerprints.size)
                    put("message_count_in_signal", command.messages.size)
                    put("screen_open_observed", screenMatch != null)
                    putNullable("screen_open_observed_at", screenMatch?.at)
                    put("app_foreground_observed", foreground != null)
                    putNullable("app_foreground_observed_at", foreground?.at)
                    put("observation_basis", "exact package/time + exact normalized visible anchor hashes; no relationship inference")
                })
                put("change_intelligence", JSONObject().apply {
                    put("change", analysis.change.name)
                    put("reason", analysis.changeReason)
                    put("lifecycle_generation", lifecycle.generation)
                    put("update_sequence", lifecycle.sequence)
                    put("new_message_fingerprint_count", analysis.newMessageFingerprints.size)
                    put("state_transition", "${lifecycle.state.name}:${analysis.change.name}")
                })
                put("entity_candidates", JSONArray().apply { resolvedEntities.forEach { put(entityJson(it)) } })
                put("evidence_quality", qualityJson(command, analysis, screenMatch != null, foreground != null))
                put("graph", graphJson(observation, episodeMembers, resolvedEntities, analysis))
            }
        }
        return output
    }

    @Synchronized
    fun markOutcome(logicalSignalId: String, outcome: String, atEpochMs: Long = System.currentTimeMillis()) {
        if (logicalSignalId.isBlank() || outcome.isBlank()) return
        mutate(atEpochMs) { state ->
            val signal = state.observations.lastOrNull { it.id.contains(logicalSignalId.take(12)) }
            appendObservation(
                state,
                Observation(
                    id = stableId("obs", "OUTCOME", logicalSignalId, outcome, atEpochMs.toString()),
                    kind = "OUTCOME:$outcome",
                    packageName = signal?.packageName.orEmpty(),
                    at = atEpochMs,
                    episodeId = signal?.episodeId ?: episodeFor(state, atEpochMs, signal?.packageName.orEmpty()),
                    conversationIdentity = signal?.conversationIdentity,
                ),
            )
        }
    }

    @Synchronized
    fun stats(): JSONObject {
        val state = readState()
        val episodes = state.observations.map { it.episodeId }.filter(String::isNotBlank).distinct().size
        val crossApp = state.observations.groupBy { it.episodeId }.count { (_, items) -> items.map { it.packageName }.filter(String::isNotBlank).distinct().size > 1 }
        return JSONObject().apply {
            put("observations", state.observations.size)
            put("entity_candidates", state.entities.size)
            put("episodes", episodes)
            put("cross_app_episodes", crossApp)
        }
    }

    private fun episodeJson(id: String, members: List<Observation>, now: Long) = JSONObject().apply {
        val packages = members.map { it.packageName }.filter(String::isNotBlank).distinct()
        put("episode_id", id)
        put("started_at", members.minOfOrNull { it.at } ?: now)
        put("last_observed_at", members.maxOfOrNull { it.at } ?: now)
        put("observation_count", members.size)
        put("packages", JSONArray(packages))
        put("cross_app", packages.size > 1)
        put("observation_types", JSONArray(members.map { it.kind }.distinct()))
        put("assembly_basis", "bounded temporal continuity <=${EPISODE_GAP_MS}ms; descriptive only")
    }

    private fun graphJson(
        current: Observation,
        episodeMembers: List<Observation>,
        entities: List<EntityCandidate>,
        analysis: NotificationSignalAnalysis,
    ) = JSONObject().apply {
        put("schema", "CORTEX_RELAY_EVIDENCE_GRAPH_V1")
        put("ttl_hours", 72)
        put("nodes", JSONArray().apply {
            put(node(current.id, "OBSERVATION"))
            put(node(current.episodeId, "EPISODE"))
            put(node("app:${current.packageName}", "APP"))
            put(node("conversation:${analysis.conversationIdentity}", "CONVERSATION"))
            entities.forEach { put(node(it.id, "ENTITY_CANDIDATE")) }
        })
        put("edges", JSONArray().apply {
            put(edge(current.id, current.episodeId, "BELONGS_TO_EPISODE", 1.0, "temporal episode assembly"))
            put(edge(current.id, "app:${current.packageName}", "OBSERVED_IN_APP", 1.0, "Android packageName"))
            put(edge(current.id, "conversation:${analysis.conversationIdentity}", "IN_CONVERSATION", 0.98, analysis.conversationIdentityBasis))
            entities.forEach { put(edge(current.id, it.id, "MENTIONS_ENTITY", it.confidence, it.matchBasis)) }
            episodeMembers.filter { it.id != current.id }.takeLast(12).forEach { prior ->
                put(edge(current.id, prior.id, "TEMPORALLY_NEAR", 0.80, "same bounded episode"))
            }
        })
    }

    private fun qualityJson(
        command: com.kareem.secondbrain.domain.CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
        screenMatched: Boolean,
        foregroundObserved: Boolean,
    ) = JSONObject().apply {
        val fields = JSONArray()
        fun q(field: String, score: Double, source: String) {
            fields.put(JSONObject().apply { put("field", field); put("quality", score); put("source", source) })
        }
        q("notification_identity", 1.0, "StatusBarNotification key/user/uid evidence")
        q("conversation_identity", 0.96, analysis.conversationIdentityBasis)
        if (!command.title.isNullOrBlank()) q("title", 0.95, "Android Notification extras")
        if (!command.body.isNullOrBlank()) q("body", 0.95, "Android Notification extras")
        if (command.messages.isNotEmpty()) q("messages", 0.98, "Android MessagingStyle")
        analysis.entities.forEach { q("entity:${it.type}", it.confidence.coerceIn(0.0, 1.0), it.sourceField) }
        if (screenMatched) q("screen_interaction", 0.86, "Accessibility exact normalized visible anchor")
        if (foregroundObserved) q("foreground_interaction", 0.92, "Accessibility/Usage foreground transition")
        var sum = 0.0
        for (i in 0 until fields.length()) sum += fields.getJSONObject(i).getDouble("quality")
        put("overall", if (fields.length() == 0) 0.0 else sum / fields.length())
        put("fields", fields)
        put("meaning", "evidence/provenance quality only; never personal importance")
    }

    private fun entityObservations(
        command: com.kareem.secondbrain.domain.CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
    ): List<EntityCandidate> {
        val items = mutableListOf<EntityCandidate>()
        analysis.entities.forEach { e ->
            val normalized = normalize(e.value)
            if (normalized.isNotBlank()) {
                items += EntityCandidate(
                    id = stableId("entity", e.type, normalized),
                    type = e.type,
                    normalized = normalized,
                    display = e.value.take(180),
                    confidence = e.confidence.coerceIn(0.0, 1.0),
                    matchBasis = "EXACT_NORMALIZED_${e.type}",
                    firstSeen = 0L,
                    lastSeen = 0L,
                    observations = 0,
                    packages = emptySet(),
                )
            }
        }
        val names = buildList {
            command.messages.mapNotNullTo(this) { it.sender?.takeIf(String::isNotBlank) }
            command.conversationTitle?.takeIf(String::isNotBlank)?.let(::add)
        }.distinct()
        names.forEach { value ->
            val normalized = normalize(value)
            if (normalized.isNotBlank()) {
                items += EntityCandidate(
                    id = stableId("entity", "PERSON_LABEL", normalized),
                    type = "PERSON_LABEL",
                    normalized = normalized,
                    display = value.take(180),
                    confidence = 0.72,
                    matchBasis = "EXACT_NORMALIZED_VISIBLE_PERSON_LABEL",
                    firstSeen = 0L,
                    lastSeen = 0L,
                    observations = 0,
                    packages = emptySet(),
                )
            }
        }
        return items.distinctBy { it.id }
    }

    private fun mergeEntity(state: State, incoming: EntityCandidate, now: Long, packageName: String) {
        val old = state.entities[incoming.id]
        state.entities[incoming.id] = incoming.copy(
            firstSeen = old?.firstSeen?.takeIf { it > 0L } ?: now,
            lastSeen = max(old?.lastSeen ?: 0L, now),
            observations = (old?.observations ?: 0) + 1,
            confidence = max(old?.confidence ?: 0.0, incoming.confidence),
            packages = (old?.packages.orEmpty() + packageName).filter(String::isNotBlank).toSet(),
        )
    }

    private fun entityJson(entity: EntityCandidate) = JSONObject().apply {
        put("entity_candidate_id", entity.id)
        put("type", entity.type)
        put("display_value", entity.display)
        put("match_basis", entity.matchBasis)
        put("link_confidence", entity.confidence)
        put("first_seen_at", entity.firstSeen)
        put("last_seen_at", entity.lastSeen)
        put("observation_count", entity.observations)
        put("packages", JSONArray(entity.packages.sorted()))
        put("cross_app_observed", entity.packages.size > 1)
        put("identity_claim", false)
    }

    private fun node(id: String, type: String) = JSONObject().apply { put("id", id); put("type", type) }
    private fun edge(from: String, to: String, relation: String, confidence: Double, basis: String) = JSONObject().apply {
        put("from", from); put("to", to); put("relation", relation); put("confidence", confidence); put("basis", basis)
    }

    private fun episodeFor(state: State, at: Long, packageName: String): String {
        val previous = state.observations.maxByOrNull { it.at }
        if (previous != null && at >= previous.at && at - previous.at <= EPISODE_GAP_MS) return previous.episodeId
        return stableId("episode", at.toString(), packageName.ifBlank { "unknown" })
    }

    private fun appendObservation(state: State, item: Observation) {
        state.observations.removeAll { it.id == item.id }
        state.observations += item
        if (state.observations.size > MAX_OBSERVATIONS) {
            state.observations.sortBy { it.at }
            while (state.observations.size > MAX_OBSERVATIONS) state.observations.removeAt(0)
        }
    }

    private fun mutate(now: Long, block: (State) -> Unit) {
        val state = readState()
        state.observations.removeAll { now - it.at > OBSERVATION_TTL_MS }
        state.entities.entries.removeAll { now - it.value.lastSeen > ENTITY_TTL_MS }
        block(state)
        if (state.entities.size > MAX_ENTITIES) {
            state.entities.values.sortedBy { it.lastSeen }.take(state.entities.size - MAX_ENTITIES).forEach { state.entities.remove(it.id) }
        }
        writeState(state)
    }

    private fun readState(): State {
        if (!file.exists()) return State()
        return runCatching {
            val root = JSONObject(file.readText())
            val observations = mutableListOf<Observation>()
            val arr = root.optJSONArray("observations") ?: JSONArray()
            for (i in 0 until arr.length()) observations += Observation.fromJson(arr.getJSONObject(i))
            val entities = linkedMapOf<String, EntityCandidate>()
            val entityArr = root.optJSONArray("entities") ?: JSONArray()
            for (i in 0 until entityArr.length()) EntityCandidate.fromJson(entityArr.getJSONObject(i)).let { entities[it.id] = it }
            State(observations, entities)
        }.getOrElse { State() }
    }

    private fun writeState(state: State) {
        file.parentFile?.mkdirs()
        val root = JSONObject().apply {
            put("schema", SCHEMA)
            put("observations", JSONArray().apply { state.observations.forEach { put(it.toJson()) } })
            put("entities", JSONArray().apply { state.entities.values.forEach { put(it.toJson()) } })
        }
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun screenAnchors(text: String): Set<String> {
        val raw = text.lineSequence().map(::normalize).filter { it.length in 2..120 }.take(120).toList()
        val tokens = raw.flatMap { line -> line.split(' ').filter { it.length >= 2 }.take(24) }
        return (raw + tokens).distinct().take(180).map { stableId("anchor", it) }.toSet()
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}@.+_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stableId(prefix: String, vararg values: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(values.joinToString("\u001f").toByteArray(Charsets.UTF_8))
        return prefix + "_" + bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }

    private data class State(
        val observations: MutableList<Observation> = mutableListOf(),
        val entities: MutableMap<String, EntityCandidate> = linkedMapOf(),
    )

    private data class Observation(
        val id: String,
        val kind: String,
        val packageName: String,
        val at: Long,
        val episodeId: String,
        val conversationIdentity: String? = null,
        val entityIds: List<String> = emptyList(),
        val anchors: Set<String> = emptySet(),
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("kind", kind); put("package", packageName); put("at", at); put("episode_id", episodeId)
            put("conversation_identity", conversationIdentity ?: JSONObject.NULL)
            put("entity_ids", JSONArray(entityIds)); put("anchors", JSONArray(anchors.toList()))
        }
        companion object {
            fun fromJson(j: JSONObject) = Observation(
                j.optString("id"), j.optString("kind"), j.optString("package"), j.optLong("at"), j.optString("episode_id"),
                j.optString("conversation_identity").takeIf { it.isNotBlank() && it != "null" },
                j.optJSONArray("entity_ids").strings(), j.optJSONArray("anchors").strings().toSet(),
            )
        }
    }

    private data class EntityCandidate(
        val id: String,
        val type: String,
        val normalized: String,
        val display: String,
        val confidence: Double,
        val matchBasis: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val observations: Int,
        val packages: Set<String>,
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("type", type); put("normalized", normalized); put("display", display); put("confidence", confidence)
            put("match_basis", matchBasis); put("first_seen", firstSeen); put("last_seen", lastSeen); put("observations", observations)
            put("packages", JSONArray(packages.toList()))
        }
        companion object {
            fun fromJson(j: JSONObject) = EntityCandidate(
                j.optString("id"), j.optString("type"), j.optString("normalized"), j.optString("display"), j.optDouble("confidence"),
                j.optString("match_basis"), j.optLong("first_seen"), j.optLong("last_seen"), j.optInt("observations"),
                j.optJSONArray("packages").strings().toSet(),
            )
        }
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) optString(i).takeIf(String::isNotBlank)?.let(out::add)
    return out
}
