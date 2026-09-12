package com.kareem.khojlocal

class BrainAnswerEngine(
    private val search: LocalSearchEngine,
    private val cloud: CloudAiClient,
    private val settings: AiSettings,
) {
    data class Answer(val text: String, val evidence: List<SearchHit>, val usedCloud: Boolean)

    fun answer(question: String): Answer {
        val evidence = search.search(question, 8)
        if (evidence.isEmpty()) {
            return Answer("I couldn't find enough evidence in your local memory yet.", emptyList(), false)
        }
        if (settings.isConfigured()) {
            try {
                return Answer(cloud.answer(question, evidence), evidence, true)
            } catch (t: Throwable) {
                val fallback = extractiveAnswer(question, evidence)
                return Answer("$fallback\n\nAI endpoint unavailable, so this answer was generated locally from retrieved evidence.", evidence, false)
            }
        }
        return Answer(extractiveAnswer(question, evidence), evidence, false)
    }

    private fun extractiveAnswer(question: String, evidence: List<SearchHit>): String {
        val q = tokens(question)
        val scored = mutableListOf<Pair<String, Double>>()
        evidence.take(6).forEach { hit ->
            val sentences = hit.memory.body
                .replace("\r", "\n")
                .split(Regex("(?<=[.!?؟])\\s+|\\n+"))
                .map { it.trim() }
                .filter { it.length in 20..700 }
                .take(80)
            sentences.forEach { sentence ->
                val st = tokens(sentence)
                val overlap = if (q.isEmpty()) 0.0 else q.count { it in st }.toDouble() / q.size
                val score = overlap * 0.75 + hit.score * 0.25
                if (score > 0.10) scored += "${sentence.take(420)} [memory:${hit.memory.id}]" to score
            }
        }
        val best = scored.sortedByDescending { it.second }.map { it.first }.distinct().take(5)
        if (best.isEmpty()) {
            val top = evidence.first().memory
            return "The closest memory I found is “${top.title.ifBlank { "Memory ${top.id}" }}”: ${top.body.take(600)} [memory:${top.id}]"
        }
        return best.joinToString(separator = "\n\n• ", prefix = "• ")
    }

    private fun tokens(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
        .toSet()
}
