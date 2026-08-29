package com.kareem.secondbrain.capture.android.connector

import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisFacts
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisMessage
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisPerson
import com.kareem.secondbrain.capture.android.notification.NotificationLifecycleDecision
import com.kareem.secondbrain.capture.android.notification.NotificationLifecycleState
import com.kareem.secondbrain.capture.android.notification.NotificationMeaningfulChange
import com.kareem.secondbrain.capture.android.notification.NotificationNoiseClassifier
import com.kareem.secondbrain.capture.android.notification.NotificationNoiseFacts
import com.kareem.secondbrain.capture.android.notification.NotificationSignalAnalyzer
import com.kareem.secondbrain.capture.android.notification.RelaySignalType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class RelayReplayResult(
    val eventId: String,
    val success: Boolean,
    val originalSignalType: String?,
    val replayedSignalType: String?,
    val originalFilterState: String?,
    val replayedFilterState: String?,
    val originalEntityTypes: Set<String>,
    val replayedEntityTypes: Set<String>,
    val detail: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("success", success)
        put("original_signal_type", originalSignalType ?: JSONObject.NULL)
        put("replayed_signal_type", replayedSignalType ?: JSONObject.NULL)
        put("original_filter_state", originalFilterState ?: JSONObject.NULL)
        put("replayed_filter_state", replayedFilterState ?: JSONObject.NULL)
        put("original_entity_types", JSONArray().apply { originalEntityTypes.sorted().forEach(::put) })
        put("replayed_entity_types", JSONArray().apply { replayedEntityTypes.sorted().forEach(::put) })
        put("detail", detail)
    }
}

/**
 * Re-runs deterministic normalization/classification over locally buffered evidence.
 * Replay never writes to the capture repository and never sends anything to Cortex.
 */
object RelayReplayEngine {
    const val SCHEMA = "CORTEX_RELAY_REPLAY_V2"

    fun replay(record: RelayForensicRecord): RelayReplayResult {
        return try {
            val root = record.json
            val facts = factsFromJson(root.getJSONObject("facts"))
            val messageFingerprints = facts.messages.map(NotificationSignalAnalyzer::messageFingerprint).toSet()
            val lifecycle = NotificationLifecycleDecision(
                notificationIdentity = NotificationSignalAnalyzer.notificationIdentity(facts),
                state = NotificationLifecycleState.POSTED,
                generation = 1,
                sequence = 0,
                instanceStartedAtEpochMs = root.optLong("captured_at", System.currentTimeMillis()),
                visibleFingerprint = NotificationSignalAnalyzer.visibleFingerprint(facts),
                stableChurnFingerprint = NotificationSignalAnalyzer.stableChurnFingerprint(facts),
                newMessageFingerprints = messageFingerprints,
                unchanged = false,
                stableChurnOnly = false,
                isNewInstance = true,
            )
            val analysis = NotificationSignalAnalyzer.analyze(facts, lifecycle)
            val command = root.getJSONObject("command")
            val metadata = runCatching { JSONObject(command.optString("metadata_json", "{}")) }.getOrDefault(JSONObject())
            val filter = NotificationNoiseClassifier.classify(
                NotificationNoiseFacts(
                    packageName = facts.packageName,
                    title = facts.title,
                    body = facts.body,
                    expandedText = facts.expandedText,
                    isOngoing = facts.isOngoing,
                    category = facts.category,
                    channelId = facts.channelId,
                    isGroupSummary = metadata.optBoolean("isGroupSummary", false),
                    meaningfulChange = NotificationMeaningfulChange.NEW_POST,
                    signalType = analysis.signalType,
                ),
            )
            val originalAnalysis = root.optJSONObject("analysis")
            val originalFilter = root.optJSONObject("filter")
            val originalEntityTypes = buildSet {
                val array = originalAnalysis?.optJSONArray("entities")
                if (array != null) for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.optString("type")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
            val replayEntityTypes = analysis.entities.mapTo(mutableSetOf()) { it.type }
            val originalType = originalAnalysis?.optString("signal_type")?.takeIf(String::isNotBlank)
            val originalFilterState = originalFilter?.optString("state")?.takeIf(String::isNotBlank)
            val same = originalType == analysis.signalType.name &&
                originalFilterState == filter.state.name &&
                originalEntityTypes == replayEntityTypes
            val result = RelayReplayResult(
                eventId = record.eventId,
                success = same,
                originalSignalType = originalType,
                replayedSignalType = analysis.signalType.name,
                originalFilterState = originalFilterState,
                replayedFilterState = filter.state.name,
                originalEntityTypes = originalEntityTypes,
                replayedEntityTypes = replayEntityTypes,
                detail = if (same) {
                    "Deterministic replay matched original signal type, entity types and mechanical filter state"
                } else {
                    "Replay differs from the originally captured analysis; inspect this as a normalizer/classifier regression or intentional code change"
                },
            )
            RelayV2OperationalMetrics.markReplay(result.success)
            result
        } catch (t: Throwable) {
            RelayV2OperationalMetrics.markReplay(false)
            RelayReplayResult(
                eventId = record.eventId,
                success = false,
                originalSignalType = null,
                replayedSignalType = null,
                originalFilterState = null,
                replayedFilterState = null,
                originalEntityTypes = emptySet(),
                replayedEntityTypes = emptySet(),
                detail = "Replay failed: ${t.javaClass.simpleName}${t.message?.let { ": $it" }.orEmpty()}",
            )
        }
    }

    private fun factsFromJson(json: JSONObject): NotificationAnalysisFacts = NotificationAnalysisFacts(
        packageName = json.getString("package_name"),
        notificationKey = json.getString("notification_key"),
        androidUserId = json.optInt("android_user_id", 0),
        uid = json.optInt("uid", 0),
        tag = nullableString(json, "tag"),
        shortcutId = nullableString(json, "shortcut_id"),
        channelId = nullableString(json, "channel_id"),
        category = nullableString(json, "category"),
        isOngoing = json.optBoolean("ongoing", false),
        title = nullableString(json, "title"),
        body = nullableString(json, "body"),
        expandedText = nullableString(json, "expanded_text"),
        conversationTitle = nullableString(json, "conversation_title"),
        messages = buildList {
            val array = json.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").takeIf(String::isNotBlank) ?: continue
                add(
                    NotificationAnalysisMessage(
                        sender = nullableString(item, "sender"),
                        text = text,
                        timestamp = item.optLong("timestamp", 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                    ),
                )
            }
        },
        people = buildList {
            val array = json.optJSONArray("people") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    NotificationAnalysisPerson(
                        name = nullableString(item, "name"),
                        key = nullableString(item, "key"),
                        uri = nullableString(item, "uri"),
                    ),
                )
            }
        },
        replyable = json.optBoolean("replyable", false),
    )

    private fun nullableString(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null else json.optString(key).takeIf(String::isNotBlank)
}