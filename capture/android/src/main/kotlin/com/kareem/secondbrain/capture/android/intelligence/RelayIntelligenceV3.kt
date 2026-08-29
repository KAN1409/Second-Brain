package com.kareem.secondbrain.capture.android.intelligence

import android.content.Context
import com.kareem.secondbrain.capture.android.notification.NotificationLifecycleDecision
import com.kareem.secondbrain.capture.android.notification.NotificationSignalAnalysis
import com.kareem.secondbrain.capture.android.notification.RelayActionCapabilityDescriptor
import com.kareem.secondbrain.capture.android.notification.RelayConversationContinuity
import com.kareem.secondbrain.domain.CaptureCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Cortex Relay Intelligence V3.
 *
 * This layer is deliberately perception-only. It may organize, correlate and score the quality of
 * Android evidence, but it never assigns personal importance, creates long-term memory or decides
 * what the user should do. Cortex remains the sole reasoning/attention authority.
 */
class RelayIntelligenceV3 private constructor(private val stateFile: File) {
    companion object {
        const val SCHEMA = "CORTEX_RELAY_INTELLIGENCE_V3"
        const val GRAPH_SCHEMA = "CORTEX_RELAY_EVIDENCE_GRAPH_V1"
        const val EPISODE_SCHEMA = "CORTEX_RELAY_EPISODE_V1"
        const val SCREEN_SCHEMA = "CORTEX_RELAY_SCREEN_STATE_V1"
        private const val TTL_MS = 72L * 60L * 60L * 1000L
        private const val EPISODE_GAP_MS = 8L * 60L * 1000L
        private const val MAX_OBSERVATIONS = 320
        private const val MAX_EPISODES = 80
        private const val MAX_NODES = 500
        private const val MAX_EDGES = 900
        private val instances = ConcurrentHashMap<String, RelayIntelligenceV3>()

        fun forContext(context: Context): RelayIntelligenceV3 {
            val file = File(context.noBackupFilesDir, "cortex-relay-intelligence-v3/state.json")
            return instances.getOrPut(file.absolutePath) { RelayIntelligenceV3(file) }
        }

        /** Pure deterministic helper used by tests and replay tooling. */
        fun classifyScreen(text: String, className: String? = null): JSONObject {
            val lower = text.lowercase(Locale.ROOT)
            val classLower = className.orEmpty().lowercase(Locale.ROOT)
            val type = when {
                containsAny(lower, "reply", "send", "message", "typing", "online", "last seen", "رسالة", "إرسال") -> "CHAT"
                containsAny(lower, "subject", "inbox", "reply all", "forward", "cc", "bcc", "البريد") -> "EMAIL_THREAD"
                containsAny(lower, "order", "tracking", "out for delivery", "delivered", "shipment", "طلب", "شحنة") -> "ORDER_DETAIL"
                containsAny(lower, "calendar", "appointment", "meeting", "event", "موعد", "تقويم") -> "CALENDAR"
                containsAny(lower, "settings", "permissions", "accessibility", "notification access", "إعدادات", "الأذونات") -> "SETTINGS"
                containsAny(lower, "document", "page ", "pdf", "sheet", "spreadsheet", "مستند") || classLower.contains("pdf") -> "DOCUMENT"
                else -> "GENERIC"
            }
            val actions = JSONArray().apply {
                if (containsAny(lower, "send", "إرسال")) put("SEND")
                if (containsAny(lower, "reply", "رد")) put("REPLY")
                if (containsAny(lower, "attach", "attachment", "مرفق")) put("ATTACH")
                if (containsAny(lower, "call", "اتصال")) put("CALL")
                if (containsAny(lower, "save", "حفظ")) put("SAVE")
            }
            return JSONObject().apply {
                put("schema", SCREEN_SCHEMA)
                put("screen_type", type)
                put("visible_action_hints", actions)
                put("classification_source", "deterministic_accessibility_text")
                put("classification_confidence", if (type == "GENERIC") 0.50 else 0.76)
            }
        }

        private fun containsAny(value: String, vararg needles: String): Boolean = needles.any(value::contains)
    }

    private var state: JSONObject = loadState()

    @Synchronized
    fun observeNotification(
        command: CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
        lifecycle: NotificationLifecycleDecision,
        continuity: RelayConversationContinuity?,
        actions: List<RelayActionCapabilityDescriptor>,
    ): JSONObject {
        val now = command.occurredAt.toEpochMilli()
        prune(now)

        val entityNodes = JSONArray().apply {
            analysis.entities.forEach { entity ->
                put(entityCandidate(
                    type = entity.type,
                    value = entity.value,
                    source = entity.sourceField,
                    confidence = entity.confidence,
                ))
            }
            command.messages.mapNotNull { it.sender?.trim()?.takeIf(String::isNotBlank) }
                .distinctBy(::normalize)
                .forEach { sender -> put(entityCandidate("PERSON", sender, "Android MessagingStyle sender", 0.96)) }
        }

        val observationId = stableId("obs", analysis.logicalSignalId + ":" + lifecycle.sequence)
        val observation = JSONObject().apply {
            put("id", observationId)
            put("kind", "NOTIFICATION")
            put("at", now)
            put("package", command.packageName)
            put("logical_signal_id", analysis.logicalSignalId)
            put("conversation_identity", analysis.conversationIdentity)
            put("signal_type", analysis.signalType.name)
            put("change", analysis.change.name)
            put("entities", entityNodes)
        }
        appendObservation(observation)

        val episode = attachToEpisode(
            observationId = observationId,
            at = now,
            packageName = command.packageName,
            conversationIdentity = analysis.conversationIdentity,
            entities = entityNodes,
        )
        val graph = upsertEvidenceGraph(observation, episode, entityNodes)
        val outcomes = deriveInteractionOutcomes(command.packageName, analysis.conversationIdentity, now)
        rememberPendingNotification(command.packageName, analysis.conversationIdentity, observationId, now)

        val quality = notificationQuality(command, analysis, lifecycle)
        val actionGraph = JSONArray().apply {
            actions.forEach { action ->
                put(JSONObject().apply {
                    put("capability_id", action.capabilityId)
                    put("kind", action.kind)
                    put("label", action.label ?: JSONObject.NULL)
                    put("requires_text_input", action.requiresTextInput)
                    put("source", action.source)
                    put("runtime_validity", "LIVE_HANDLE_REQUIRED")
                    put("execution_authority", "CORTEX_AUTHORIZED_USER_CONTROLLED")
                    put("requires_explicit_confirmation", true)
                })
            }
        }

        persist()
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("episode", episode)
            put("evidence_graph", graph)
            put("entity_resolution", JSONObject().apply {
                put("candidates", entityNodes)
                put("policy", "grounded_candidates_only_no_identity_invention")
            })
            put("evidence_quality", quality)
            put("interaction_outcomes", outcomes)
            put("action_capability_graph", actionGraph)
            put("adaptive_capture_hints", adaptiveCaptureHints(analysis.signalType.name, quality))
            put("conversation_state", JSONObject().apply {
                put("conversation_identity", analysis.conversationIdentity)
                put("identity_basis", analysis.conversationIdentityBasis)
                put("observation_sequence", continuity?.observationSequence ?: JSONObject.NULL)
                put("first_seen_at", continuity?.firstSeenAtEpochMs ?: JSONObject.NULL)
                put("last_seen_at", continuity?.lastSeenAtEpochMs ?: JSONObject.NULL)
                put("latest_change", analysis.change.name)
                put("latest_message_count", command.messages.size)
                put("latest_sender", command.messages.lastOrNull()?.sender ?: JSONObject.NULL)
            })
        }
    }

    @Synchronized
    fun observeScreen(
        packageName: String,
        accessibleText: String,
        className: String?,
        eventType: Int,
        occurredAtEpochMs: Long,
    ): JSONObject {
        val now = occurredAtEpochMs
        prune(now)
        val screen = classifyScreen(accessibleText, className)
        val entities = extractScreenEntities(accessibleText)
        val observationId = stableId("screen", "$packageName:$now:${accessibleText.take(120)}")
        val observation = JSONObject().apply {
            put("id", observationId)
            put("kind", "SCREEN")
            put("at", now)
            put("package", packageName)
            put("screen_type", screen.optString("screen_type"))
            put("event_type", eventType)
            put("entities", entities)
        }
        appendObservation(observation)
        val episode = attachToEpisode(observationId, now, packageName, null, entities)
        val graph = upsertEvidenceGraph(observation, episode, entities)
        val outcomes = markAppInteraction(packageName, now, screen.optString("screen_type"))
        persist()
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("structured_screen_state", screen)
            put("episode", episode)
            put("evidence_graph", graph)
            put("entity_resolution", JSONObject().apply {
                put("candidates", entities)
                put("policy", "screen_candidates_are_observations_not_personal_facts")
            })
            put("evidence_quality", JSONObject().apply {
                put("overall", 0.90)
                put("source", "AccessibilityNodeInfo tree")
                put("direct_android_evidence", true)
                put("structured_screen_classification", screen.optDouble("classification_confidence", 0.50))
            })
            put("interaction_outcomes", outcomes)
        }
    }

    @Synchronized
    fun observeAppActivity(packageName: String, enteredForeground: Boolean, atEpochMs: Long): JSONObject {
        prune(atEpochMs)
        val apps = state.optJSONArray("recent_apps") ?: JSONArray().also { state.put("recent_apps", it) }
        apps.put(JSONObject().apply {
            put("package", packageName)
            put("entered", enteredForeground)
            put("at", atEpochMs)
        })
        trimArray(apps, 40)
        val outcomes = if (enteredForeground) markAppInteraction(packageName, atEpochMs, "APP_FOREGROUND") else JSONArray()
        persist()
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("interaction_outcomes", outcomes)
            put("recent_app_path", recentAppPath())
        }
    }

    @Synchronized
    fun diagnostics(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("observation_count", state.optJSONArray("observations")?.length() ?: 0)
        put("episode_count", state.optJSONArray("episodes")?.length() ?: 0)
        val graph = state.optJSONObject("graph") ?: JSONObject()
        put("graph_nodes", graph.optJSONArray("nodes")?.length() ?: 0)
        put("graph_edges", graph.optJSONArray("edges")?.length() ?: 0)
        put("recent_app_path", recentAppPath())
        put("retention_hours", 72)
    }

    private fun notificationQuality(
        command: CaptureCommand.Notification,
        analysis: NotificationSignalAnalysis,
        lifecycle: NotificationLifecycleDecision,
    ): JSONObject {
        val fields = JSONArray()
        fun field(name: String, source: String, confidence: Double, direct: Boolean = true) {
            fields.put(JSONObject().apply {
                put("field", name)
                put("source", source)
                put("confidence", confidence)
                put("direct_android_evidence", direct)
            })
        }
        if (!command.title.isNullOrBlank()) field("title", "Notification.EXTRA_TITLE", 1.0)
        if (!command.body.isNullOrBlank()) field("body", "Notification.EXTRA_TEXT", 1.0)
        if (!command.expandedText.isNullOrBlank()) field("expanded_text", "Notification.EXTRA_BIG_TEXT", 1.0)
        if (command.messages.isNotEmpty()) field("messages", "Notification.MessagingStyle", 1.0)
        field("lifecycle", "NotificationListenerService", 1.0)
        field("conversation_identity", analysis.conversationIdentityBasis, 0.94, false)
        analysis.entities.forEach { field("entity:${it.type}", it.sourceField, it.confidence, false) }

        val directSignals = listOf(command.title, command.body, command.expandedText).count { !it.isNullOrBlank() } +
            if (command.messages.isNotEmpty()) 1 else 0
        val overall = when {
            directSignals >= 2 && lifecycle.sequence > 0 -> 0.98
            directSignals >= 1 -> 0.94
            else -> 0.78
        }
        return JSONObject().apply {
            put("overall", overall)
            put("fields", fields)
            put("truncated", false)
            put("personal_importance_scored", false)
        }
    }

    private fun adaptiveCaptureHints(signalType: String, quality: JSONObject): JSONObject = JSONObject().apply {
        val q = quality.optDouble("overall", 0.0)
        put("mechanical_only", true)
        put("signal_type", signalType)
        put("suggested_forensic_retention_hours", when {
            signalType in setOf("SECURITY", "BANKING", "DELIVERY") -> 72
            q >= 0.95 -> 48
            else -> 24
        })
        put("preserve_rich_delta", signalType in setOf("HUMAN_MESSAGE", "EMAIL", "SMS", "DELIVERY", "SECURITY"))
        put("personal_priority", JSONObject.NULL)
    }

    private fun attachToEpisode(
        observationId: String,
        at: Long,
        packageName: String,
        conversationIdentity: String?,
        entities: JSONArray,
    ): JSONObject {
        val episodes = state.optJSONArray("episodes") ?: JSONArray().also { state.put("episodes", it) }
        val entityIds = jsonStrings(entities, "candidate_id")
        var chosen: JSONObject? = null
        for (i in episodes.length() - 1 downTo 0) {
            val candidate = episodes.optJSONObject(i) ?: continue
            val gap = at - candidate.optLong("last_seen_at", 0L)
            if (gap !in 0..EPISODE_GAP_MS) continue
            val sameConversation = !conversationIdentity.isNullOrBlank() &&
                conversationIdentity == candidate.optString("conversation_identity")
            val sharedEntity = jsonStrings(candidate.optJSONArray("entity_ids") ?: JSONArray()).any(entityIds::contains)
            val recentPackage = candidate.optJSONArray("packages")?.let { jsonStrings(it).contains(packageName) } == true
            if (sameConversation || sharedEntity || recentPackage) {
                chosen = candidate
                break
            }
        }
        if (chosen == null) {
            chosen = JSONObject().apply {
                put("schema", EPISODE_SCHEMA)
                put("episode_id", stableId("episode", "$at:$packageName:${conversationIdentity.orEmpty()}"))
                put("started_at", at)
                put("last_seen_at", at)
                put("conversation_identity", conversationIdentity ?: JSONObject.NULL)
                put("packages", JSONArray())
                put("entity_ids", JSONArray())
                put("observation_ids", JSONArray())
            }
            episodes.put(chosen)
            trimArray(episodes, MAX_EPISODES)
        }
        addUnique(chosen.getJSONArray("packages"), packageName)
        entityIds.forEach { addUnique(chosen.getJSONArray("entity_ids"), it) }
        addUnique(chosen.getJSONArray("observation_ids"), observationId)
        chosen.put("last_seen_at", at)
        chosen.put("recent_app_path", recentAppPath())
        chosen.put("cross_app", chosen.getJSONArray("packages").length() > 1)
        return JSONObject(chosen.toString())
    }

    private fun upsertEvidenceGraph(observation: JSONObject, episode: JSONObject, entities: JSONArray): JSONObject {
        val graph = state.optJSONObject("graph") ?: JSONObject().apply {
            put("schema", GRAPH_SCHEMA)
            put("nodes", JSONArray())
            put("edges", JSONArray())
        }.also { state.put("graph", it) }
        val nodes = graph.getJSONArray("nodes")
        val edges = graph.getJSONArray("edges")
        upsertNode(nodes, JSONObject().apply {
            put("id", observation.getString("id")); put("type", "OBSERVATION"); put("at", observation.optLong("at"))
        })
        val episodeId = episode.getString("episode_id")
        upsertNode(nodes, JSONObject().apply {
            put("id", episodeId); put("type", "EPISODE"); put("at", episode.optLong("last_seen_at"))
        })
        addEdge(edges, observation.getString("id"), episodeId, "PART_OF_EPISODE", 1.0)
        for (i in 0 until entities.length()) {
            val entity = entities.optJSONObject(i) ?: continue
            val id = entity.optString("candidate_id")
            if (id.isBlank()) continue
            upsertNode(nodes, JSONObject().apply {
                put("id", id); put("type", "ENTITY_CANDIDATE"); put("entity_type", entity.optString("type")); put("label", entity.optString("value")); put("at", observation.optLong("at"))
            })
            addEdge(edges, observation.getString("id"), id, "MENTIONS", entity.optDouble("confidence", 0.8))
        }
        trimArray(nodes, MAX_NODES)
        trimArray(edges, MAX_EDGES)
        return JSONObject().apply {
            put("schema", GRAPH_SCHEMA)
            put("episode_id", episodeId)
            put("observation_node_id", observation.getString("id"))
            put("entity_node_ids", JSONArray(jsonStrings(entities, "candidate_id")))
            put("node_count", nodes.length())
            put("edge_count", edges.length())
        }
    }

    private fun entityCandidate(type: String, value: String, source: String, confidence: Double): JSONObject {
        val normalized = normalize(value)
        return JSONObject().apply {
            put("candidate_id", stableId("entity", "${type.uppercase(Locale.ROOT)}:$normalized"))
            put("type", type.uppercase(Locale.ROOT))
            put("value", value)
            put("normalized", normalized)
            put("confidence", confidence.coerceIn(0.0, 1.0))
            put("source", source)
            put("resolution_state", "GROUNDED_CANDIDATE")
        }
    }

    private fun extractScreenEntities(text: String): JSONArray {
        val out = JSONArray()
        val seen = mutableSetOf<String>()
        val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        val phone = Regex("(?<!\\d)\\+?[0-9][0-9 ()-]{6,}[0-9](?!\\d)")
        val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        listOf("URL" to url, "PHONE" to phone, "EMAIL" to email).forEach { (type, regex) ->
            regex.findAll(text).take(16).forEach { match ->
                val key = "$type:${normalize(match.value)}"
                if (seen.add(key)) out.put(entityCandidate(type, match.value, "Accessibility visible text", 0.93))
            }
        }
        return out
    }

    private fun deriveInteractionOutcomes(packageName: String, conversationIdentity: String, now: Long): JSONArray {
        val outcomes = JSONArray()
        val pending = state.optJSONArray("pending_notifications") ?: return outcomes
        for (i in 0 until pending.length()) {
            val item = pending.optJSONObject(i) ?: continue
            if (item.optString("package") != packageName) continue
            if (now - item.optLong("at") !in 0..EPISODE_GAP_MS) continue
            if (item.optString("conversation_identity").isNotBlank() &&
                item.optString("conversation_identity") == conversationIdentity) {
                outcomes.put(outcome("SAME_CONVERSATION_CONTINUED", item.optString("observation_id"), now))
            }
        }
        return outcomes
    }

    private fun markAppInteraction(packageName: String, now: Long, observedAs: String): JSONArray {
        val outcomes = JSONArray()
        val pending = state.optJSONArray("pending_notifications") ?: return outcomes
        for (i in 0 until pending.length()) {
            val item = pending.optJSONObject(i) ?: continue
            if (item.optString("package") != packageName) continue
            val elapsed = now - item.optLong("at")
            if (elapsed !in 0..EPISODE_GAP_MS) continue
            if (!item.optBoolean("opened", false)) {
                item.put("opened", true)
                outcomes.put(outcome("APP_OPENED_AFTER_NOTIFICATION", item.optString("observation_id"), now).apply {
                    put("elapsed_ms", elapsed)
                    put("observed_as", observedAs)
                })
            }
        }
        return outcomes
    }

    private fun rememberPendingNotification(packageName: String, conversationIdentity: String, observationId: String, at: Long) {
        val pending = state.optJSONArray("pending_notifications") ?: JSONArray().also { state.put("pending_notifications", it) }
        pending.put(JSONObject().apply {
            put("package", packageName)
            put("conversation_identity", conversationIdentity)
            put("observation_id", observationId)
            put("at", at)
            put("opened", false)
        })
        trimArray(pending, 120)
    }

    private fun outcome(kind: String, sourceObservationId: String, at: Long) = JSONObject().apply {
        put("kind", kind)
        put("source_observation_id", sourceObservationId)
        put("observed_at", at)
        put("grounded", true)
    }

    private fun appendObservation(observation: JSONObject) {
        val observations = state.optJSONArray("observations") ?: JSONArray().also { state.put("observations", it) }
        observations.put(observation)
        trimArray(observations, MAX_OBSERVATIONS)
    }

    private fun recentAppPath(): JSONArray {
        val source = state.optJSONArray("recent_apps") ?: return JSONArray()
        val result = JSONArray()
        val start = maxOf(0, source.length() - 8)
        for (i in start until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            if (item.optBoolean("entered", false)) result.put(item.optString("package"))
        }
        return result
    }

    private fun prune(now: Long) {
        listOf("observations", "episodes", "pending_notifications", "recent_apps").forEach { key ->
            val array = state.optJSONArray(key) ?: return@forEach
            val kept = JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val at = when (key) {
                    "episodes" -> item.optLong("last_seen_at", 0L)
                    else -> item.optLong("at", 0L)
                }
                if (at == 0L || now - at <= TTL_MS) kept.put(item)
            }
            state.put(key, kept)
        }
        val graph = state.optJSONObject("graph") ?: return
        val nodes = graph.optJSONArray("nodes") ?: JSONArray()
        val keptNodes = JSONArray()
        val validIds = mutableSetOf<String>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (now - node.optLong("at", now) <= TTL_MS) {
                keptNodes.put(node)
                validIds += node.optString("id")
            }
        }
        graph.put("nodes", keptNodes)
        val edges = graph.optJSONArray("edges") ?: JSONArray()
        val keptEdges = JSONArray()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            if (edge.optString("from") in validIds && edge.optString("to") in validIds) keptEdges.put(edge)
        }
        graph.put("edges", keptEdges)
    }

    private fun upsertNode(nodes: JSONArray, node: JSONObject) {
        val id = node.optString("id")
        for (i in 0 until nodes.length()) {
            if (nodes.optJSONObject(i)?.optString("id") == id) {
                nodes.put(i, node)
                return
            }
        }
        nodes.put(node)
    }

    private fun addEdge(edges: JSONArray, from: String, to: String, relation: String, confidence: Double) {
        val id = stableId("edge", "$from:$relation:$to")
        for (i in 0 until edges.length()) if (edges.optJSONObject(i)?.optString("id") == id) return
        edges.put(JSONObject().apply {
            put("id", id)
            put("from", from)
            put("to", to)
            put("relation", relation)
            put("confidence", confidence.coerceIn(0.0, 1.0))
        })
    }

    private fun loadState(): JSONObject = try {
        if (!stateFile.isFile) emptyState() else JSONObject(stateFile.readText())
    } catch (_: Throwable) {
        emptyState()
    }

    private fun emptyState() = JSONObject().apply {
        put("schema", SCHEMA)
        put("observations", JSONArray())
        put("episodes", JSONArray())
        put("pending_notifications", JSONArray())
        put("recent_apps", JSONArray())
        put("graph", JSONObject().apply {
            put("schema", GRAPH_SCHEMA)
            put("nodes", JSONArray())
            put("edges", JSONArray())
        })
    }

    private fun persist() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(state.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}@+._]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun stableId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return prefix + "_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun addUnique(array: JSONArray, value: String) {
        for (i in 0 until array.length()) if (array.optString(i) == value) return
        array.put(value)
    }

    private fun jsonStrings(array: JSONArray): List<String> = buildList {
        for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun jsonStrings(array: JSONArray, field: String): List<String> = buildList {
        for (i in 0 until array.length()) array.optJSONObject(i)?.optString(field)?.takeIf(String::isNotBlank)?.let(::add)
    }

    private fun trimArray(array: JSONArray, max: Int) {
        while (array.length() > max) array.remove(0)
    }
}
