package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.ai.api.AiProvider
import com.kareem.secondbrain.ai.api.AiQueryPlanRequest
import com.kareem.secondbrain.core.model.MemoryKind
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Open-world query planning for Ask.
 *
 * Only metadata constraints explicitly present in the question become hard filters.
 * Everything else remains a semantic clue. A model may add query variants and soft kind hints,
 * but those hints never exclude memory types or manufacture facts.
 */
internal data class AskSearchPlan(
    val queries: List<String>,
    val from: Instant? = null,
    val to: Instant? = null,
    val softKindHints: Set<MemoryKind> = emptySet(),
    val relationHints: Set<String> = emptySet(),
    val usedModelPlanner: Boolean = false,
)

internal object AskQueryPlanner {
    private val twelveHourTime = Regex("(?i)\\b(1[0-2]|0?[1-9])(?::([0-5]\\d))?\\s*(am|pm)\\b")
    private val twentyFourHourTime = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    private val yesterdayWords = Regex("(?i)\\b(yesterday)\\b|(?:أمبارح|امبارح)")
    private val todayWords = Regex("(?i)\\b(today)\\b|(?:النهاردة|اليوم)")

    // Remove only question scaffolding and temporal syntax. Action words remain semantic clues.
    private val scaffoldingWords = setOf(
        "what", "did", "does", "do", "when", "where", "who", "which", "was", "were", "is", "are",
        "at", "around", "about", "a", "an", "the", "to", "me", "my", "i", "he", "she", "they",
        "today", "yesterday",
        "ايه", "إيه", "امتى", "إمتى", "فين", "مين", "اللي", "في", "على", "الساعة", "حوالي",
        "النهاردة", "اليوم", "أمبارح", "امبارح",
    )

    suspend fun plan(
        question: String,
        clock: Clock,
        aiProvider: AiProvider? = null,
        allowModelPlanning: Boolean = false,
    ): AskSearchPlan {
        val trimmed = question.trim()
        val temporal = explicitTemporalWindow(trimmed, clock)
        val anchorQuery = meaningfulQuery(trimmed)

        val modelPlan = if (allowModelPlanning && aiProvider != null) {
            runCatching {
                aiProvider.planQuery(
                    AiQueryPlanRequest(
                        question = trimmed,
                        nowEpochMs = clock.millis(),
                        zoneId = clock.zone.id,
                    ),
                )
            }.getOrNull()
        } else {
            null
        }

        val queries = buildList {
            modelPlan?.semanticQueries.orEmpty().forEach { query ->
                query.trim().takeIf(String::isNotBlank)?.let(::add)
            }
            anchorQuery.takeIf(String::isNotBlank)?.let(::add)
            trimmed.takeIf(String::isNotBlank)?.let(::add)
        }
            .distinctBy { it.lowercase() }
            .take(MAX_QUERY_VARIANTS)

        val softKinds = modelPlan?.softKindHints.orEmpty()
            .mapNotNull { hint -> runCatching { MemoryKind.valueOf(hint) }.getOrNull() }
            .toSet()
        val relations = modelPlan?.relationHints.orEmpty().map(String::uppercase).toSet()

        return AskSearchPlan(
            queries = queries.ifEmpty { listOf(trimmed) },
            from = temporal?.from,
            to = temporal?.to,
            softKindHints = softKinds,
            relationHints = relations,
            usedModelPlanner = modelPlan != null,
        )
    }

    private fun explicitTemporalWindow(question: String, clock: Clock): TemporalWindow? {
        val now = ZonedDateTime.now(clock)
        val time = parseTime(question)?.time
        val explicitDate = when {
            yesterdayWords.containsMatchIn(question) -> now.toLocalDate().minusDays(1)
            todayWords.containsMatchIn(question) -> now.toLocalDate()
            else -> null
        }

        if (time != null) {
            val date = explicitDate ?: nearestPastDate(now, time)
            val target = ZonedDateTime.of(date, time, now.zone)
            return TemporalWindow(
                from = target.minus(TIME_RADIUS).toInstant(),
                to = target.plus(TIME_RADIUS).toInstant(),
            )
        }

        if (explicitDate != null) {
            val start = explicitDate.atStartOfDay(now.zone)
            return TemporalWindow(start.toInstant(), start.plusDays(1).toInstant())
        }

        return null
    }

    private fun nearestPastDate(now: ZonedDateTime, time: LocalTime): LocalDate {
        val todayTarget = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
        return if (todayTarget.isAfter(now.plus(FUTURE_TOLERANCE))) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
    }

    private fun meaningfulQuery(question: String): String {
        var cleaned = twelveHourTime.replace(question, " ")
        cleaned = twentyFourHourTime.replace(cleaned, " ")
        cleaned = yesterdayWords.replace(cleaned, " ")
        cleaned = todayWords.replace(cleaned, " ")
        return Regex("[\\p{L}\\p{N}_@#.+/-]+")
            .findAll(cleaned)
            .map { it.value }
            .filter { token -> token.lowercase() !in scaffoldingWords }
            .distinctBy(String::lowercase)
            .joinToString(" ")
    }

    private fun parseTime(question: String): ParsedTime? {
        twelveHourTime.find(question)?.let { match ->
            val hour12 = match.groupValues[1].toInt()
            val minute = match.groupValues[2].takeIf(String::isNotBlank)?.toInt() ?: 0
            val suffix = match.groupValues[3].lowercase()
            val hour24 = when {
                suffix == "am" && hour12 == 12 -> 0
                suffix == "pm" && hour12 != 12 -> hour12 + 12
                else -> hour12
            }
            return ParsedTime(LocalTime.of(hour24, minute))
        }
        twentyFourHourTime.find(question)?.let { match ->
            return ParsedTime(LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()))
        }
        return null
    }

    private data class ParsedTime(val time: LocalTime)
    private data class TemporalWindow(val from: Instant, val to: Instant)

    private val TIME_RADIUS: Duration = Duration.ofMinutes(90)
    private val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(30)
    private const val MAX_QUERY_VARIANTS = 6
}
