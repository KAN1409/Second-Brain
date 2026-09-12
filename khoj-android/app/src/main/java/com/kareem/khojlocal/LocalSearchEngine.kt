package com.kareem.khojlocal

import kotlin.math.ln
import kotlin.math.sqrt

class LocalSearchEngine(private val store: LocalBrainStore) {
    fun search(query: String, limit: Int = 20): List<SearchHit> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return store.listRecent(limit).map {
            SearchHit(it, 0.25, 0.0, 0.0)
        }

        val lexical = store.lexicalSearch(trimmed, 100)
        val pool = LinkedHashMap<Long, Memory>()
        lexical.forEach { pool[it.id] = it }
        store.listAllForVector(500).forEach { pool.putIfAbsent(it.id, it) }

        val queryTokens = tokens(trimmed)
        val qVector = vectorize(trimmed)
        val now = System.currentTimeMillis()

        return pool.values.map { memory ->
            val text = buildString {
                append(memory.title).append(' ')
                append(memory.tags).append(' ')
                append(memory.body)
            }
            val docTokens = tokens(text)
            val lexicalScore = lexicalScore(queryTokens, docTokens, memory.title, memory.tags)
            val vectorScore = cosine(qVector, vectorize(text.take(12_000)))
            val ageDays = ((now - memory.updatedAt).coerceAtLeast(0L) / 86_400_000.0)
            val recency = 1.0 / (1.0 + ln(1.0 + ageDays))
            val score = (0.52 * lexicalScore) + (0.38 * vectorScore) + (0.10 * recency)
            SearchHit(memory, score, lexicalScore, vectorScore)
        }
            .filter { it.score > 0.08 || lexical.any { m -> m.id == it.memory.id } }
            .sortedByDescending { it.score }
            .take(limit.coerceIn(1, 100))
    }

    private fun lexicalScore(queryTokens: Set<String>, docTokens: Set<String>, title: String, tags: String): Double {
        if (queryTokens.isEmpty()) return 0.0
        val overlap = queryTokens.count { it in docTokens }.toDouble() / queryTokens.size
        val titleTokens = tokens(title)
        val tagTokens = tokens(tags)
        val titleBoost = queryTokens.count { it in titleTokens }.toDouble() / queryTokens.size
        val tagBoost = queryTokens.count { it in tagTokens }.toDouble() / queryTokens.size
        return (overlap * 0.65 + titleBoost * 0.25 + tagBoost * 0.10).coerceIn(0.0, 1.0)
    }

    private fun tokens(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= 2 }
        .take(4000)
        .toSet()

    private fun vectorize(text: String): FloatArray {
        val dimension = 384
        val vector = FloatArray(dimension)
        val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()
        val features = ArrayList<String>()
        features.addAll(
            normalized.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 2 }
                .take(2500)
        )
        val compact = normalized.take(18_000)
        if (compact.length >= 3) {
            for (i in 0..compact.length - 3 step 2) {
                val tri = compact.substring(i, i + 3)
                if (!tri.isBlank()) features += "#$tri"
            }
        }
        features.forEach { feature ->
            val hash = feature.hashCode()
            val index = (hash and Int.MAX_VALUE) % dimension
            val sign = if ((hash ushr 31) == 0) 1f else -1f
            vector[index] += sign
        }
        var norm = 0.0
        vector.forEach { norm += (it * it).toDouble() }
        val root = sqrt(norm).toFloat()
        if (root > 0f) for (i in vector.indices) vector[i] /= root
        return vector
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) sum += (a[i] * b[i]).toDouble()
        return sum.coerceIn(0.0, 1.0)
    }
}
