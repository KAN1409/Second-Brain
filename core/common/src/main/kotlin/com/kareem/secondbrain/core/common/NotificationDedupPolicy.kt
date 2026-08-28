package com.kareem.secondbrain.core.common

import kotlin.math.abs

/**
 * Conservative notification-update coalescing.
 *
 * Android apps may repost the same physical notification moments later with richer
 * MessagingStyle metadata. We only coalesce when the repost is very close in time and
 * introduces no new semantic tokens, so genuinely new message content remains a new memory.
 */
object NotificationDedupPolicy {
    const val ENRICHMENT_WINDOW_MS = 5_000L

    private val semanticTokenRegex = Regex("[\\p{L}\\p{N}_@#.+/-]+")

    fun shouldCoalesceEnrichment(
        previousNormalized: String,
        previousOccurredAtMs: Long,
        currentNormalized: String,
        currentOccurredAtMs: Long,
    ): Boolean {
        if (abs(currentOccurredAtMs - previousOccurredAtMs) > ENRICHMENT_WINDOW_MS) return false

        val previousTokens = semanticTokens(previousNormalized)
        val currentTokens = semanticTokens(currentNormalized)
        if (previousTokens.isEmpty() || currentTokens.isEmpty()) return false

        return previousTokens == currentTokens
    }

    private fun semanticTokens(text: String): Set<String> =
        semanticTokenRegex.findAll(text.lowercase())
            .map { it.value }
            .filter { it.isNotBlank() }
            .toSet()
}
