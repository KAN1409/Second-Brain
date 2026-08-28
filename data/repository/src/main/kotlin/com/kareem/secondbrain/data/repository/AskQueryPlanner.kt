package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.core.model.MemoryKind
import com.kareem.secondbrain.core.model.SearchRequest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Deterministic, local query planning for Ask.
 *
 * Ask questions frequently contain metadata constraints ("at 3pm", "yesterday", "what did X send")
 * that are not part of the captured memory body. Convert those constraints into SearchRequest filters
 * before lexical/semantic ranking so generic app text cannot outrank the intended memory.
 */
internal object AskQueryPlanner {
    private val twelveHourTime = Regex("(?i)\\b(1[0-2]|0?[1-9])(?::([0-5]\\d))?\\s*(am|pm)\\b")
    private val twentyFourHourTime = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    private val yesterdayWords = Regex("(?i)\\b(yesterday)\\b|(?:أمبارح|امبارح)")
    private val todayWords = Regex("(?i)\\b(today)\\b|(?:النهاردة|اليوم)")
    private val notificationIntent = Regex(
        "(?i)\\b(send|sent|message|messages|text|texted|reply|replied|wrote|write|said|whatsapp|notification|notifications)\\b|(?:بعت|بعتلي|بعتله|رسالة|رسايل|واتساب|قال|رد)",
    )

    private val stopWords = setOf(
        "what", "did", "does", "do", "when", "where", "who", "which", "was", "were", "is", "are",
        "at", "around", "about", "a", "an", "the", "to", "me", "my", "i", "he", "she", "they",
        "send", "sent", "message", "messages", "text", "texted", "reply", "replied", "wrote", "write",
        "said", "whatsapp", "notification", "notifications", "today", "yesterday",
        "ايه", "إيه", "امتى", "إمتى", "فين", "مين", "اللي", "في", "على", "الساعة", "حوالي",
        "بعت", "بعتلي", "بعتله", "رسالة", "رسايل", "واتساب", "قال", "رد", "النهاردة", "اليوم", "أمبارح", "امبارح",
    )

    fun plan(question: String, clock: Clock): SearchRequest {
        val trimmed = question.trim()
        val now = ZonedDateTime.now(clock)
        val timeMatch = parseTime(trimmed)
        val date = when {
            yesterdayWords.containsMatchIn(trimmed) -> now.toLocalDate().minusDays(1)
            todayWords.containsMatchIn(trimmed) -> now.toLocalDate()
            timeMatch != null -> nearestPastDate(now, timeMatch.time)
            else -> null
        }

        val target = if (date != null && timeMatch != null) {
            ZonedDateTime.of(date, timeMatch.time, now.zone)
        } else {
            null
        }

        val hasNotificationIntent = notificationIntent.containsMatchIn(trimmed)
        val searchQuery = meaningfulQuery(trimmed)

        return SearchRequest(
            query = searchQuery.ifBlank { trimmed },
            from = target?.minus(TIME_RADIUS)?.toInstant(),
            to = target?.plus(TIME_RADIUS)?.toInstant(),
            kinds = if (hasNotificationIntent) setOf(MemoryKind.NOTIFICATION) else emptySet(),
        )
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
            .filter { token -> token.lowercase() !in stopWords }
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

    private val TIME_RADIUS: Duration = Duration.ofMinutes(75)
    private val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(30)
}
