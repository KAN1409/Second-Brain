package com.kareem.secondbrain.core.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.sqrt

data class ChunkDraft(
    val ordinal: Int,
    val text: String,
    val contentHash: String,
)

object MemoryChunker {
    const val TARGET_TOKENS = 350
    const val OVERLAP_TOKENS = 50
    const val HARD_MAX_TOKENS = 480

    fun chunk(title: String?, body: String, summary: String?): List<ChunkDraft> {
        val text = listOfNotNull(
            title?.trim()?.takeIf(String::isNotEmpty),
            body.trim().takeIf(String::isNotEmpty),
            summary?.trim()?.takeIf(String::isNotEmpty),
        ).distinct().joinToString("\n")
        if (text.isBlank()) return emptyList()

        val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.size <= HARD_MAX_TOKENS) {
            return listOf(ChunkDraft(0, text, sha256(text)))
        }

        val result = mutableListOf<ChunkDraft>()
        var start = 0
        var ordinal = 0
        while (start < tokens.size) {
            val end = minOf(start + TARGET_TOKENS, tokens.size)
            val chunkText = tokens.subList(start, end).joinToString(" ")
            result += ChunkDraft(ordinal++, chunkText, sha256(chunkText))
            if (end == tokens.size) break
            start = maxOf(0, end - OVERLAP_TOKENS)
        }
        return result
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

object SearchTextScorer {
    fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun lexicalScore(query: String, text: String): Double {
        val q = normalize(query)
        val haystack = normalize(text)
        if (q.isBlank() || haystack.isBlank()) return 0.0
        val terms = q.split(' ').filter(String::isNotBlank).distinct()
        if (terms.isEmpty()) return 0.0

        var matched = 0
        var occurrences = 0
        for (term in terms) {
            val regex = Regex("(?:^| )${Regex.escape(term)}(?: |$)")
            val count = regex.findAll(haystack).count()
            if (count > 0) matched++
            occurrences += count.coerceAtMost(4)
        }
        if (matched == 0) return 0.0

        val coverage = matched.toDouble() / terms.size
        val frequency = (occurrences.toDouble() / (terms.size * 2.0)).coerceAtMost(1.0)
        val phraseBoost = if (haystack.contains(q)) 0.35 else 0.0
        return (coverage * 0.75 + frequency * 0.25 + phraseBoost).coerceAtMost(1.35)
    }
}

object VectorCodec {
    const val ENCODING = "f32le"

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray, dimensions: Int): FloatArray {
        require(bytes.size == dimensions * Float.SIZE_BYTES) { "Vector byte length does not match dimensions" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buffer.float }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}

data class SearchCandidate(
    val memoryId: String,
    val chunkId: String,
    val snippet: String,
    val lexicalScore: Double,
    val semanticScore: Double?,
    val startedAtMs: Long,
    val importance: Double,
    val sourceMatched: Boolean,
)

data class RankedChunk(
    val memoryId: String,
    val chunkId: String,
    val snippet: String,
    val score: Double,
)

object HybridRanker {
    private const val RRF_K = 60.0
    private const val TOP_PER_CHANNEL = 40
    private const val MAX_CHUNKS_PER_MEMORY = 2

    fun rank(candidates: List<SearchCandidate>, nowMs: Long, limit: Int): List<RankedChunk> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()

        val lexicalRanks = candidates
            .filter { it.lexicalScore > 0.0 }
            .sortedByDescending { it.lexicalScore }
            .take(TOP_PER_CHANNEL)
            .mapIndexed { index, candidate -> candidate.chunkId to index + 1 }
            .toMap()
        val semanticRanks = candidates
            .filter { (it.semanticScore ?: 0.0) > 0.0 }
            .sortedByDescending { it.semanticScore }
            .take(TOP_PER_CHANNEL)
            .mapIndexed { index, candidate -> candidate.chunkId to index + 1 }
            .toMap()

        val fused = candidates.mapNotNull { candidate ->
            val lexicalRank = lexicalRanks[candidate.chunkId]
            val semanticRank = semanticRanks[candidate.chunkId]
            if (lexicalRank == null && semanticRank == null) return@mapNotNull null
            val rrf = (lexicalRank?.let { 1.0 / (RRF_K + it) } ?: 0.0) +
                (semanticRank?.let { 1.0 / (RRF_K + it) } ?: 0.0)
            candidate to rrf
        }
        val maxRrf = fused.maxOfOrNull { it.second }?.takeIf { it > 0.0 } ?: return emptyList()

        val ranked = fused.map { (candidate, rrf) ->
            val ageDays = ((nowMs - candidate.startedAtMs).coerceAtLeast(0L) / 86_400_000.0)
            val timeScore = exp(-ageDays / 45.0)
            val importance = candidate.importance.coerceIn(0.0, 1.0)
            val source = if (candidate.sourceMatched) 1.0 else 0.0
            val finalScore =
                0.70 * (rrf / maxRrf) +
                0.15 * timeScore +
                0.10 * importance +
                0.05 * source
            RankedChunk(candidate.memoryId, candidate.chunkId, candidate.snippet, finalScore)
        }.sortedByDescending { it.score }

        val perMemory = mutableMapOf<String, Int>()
        return ranked.filter { hit ->
            val count = perMemory[hit.memoryId] ?: 0
            if (count >= MAX_CHUNKS_PER_MEMORY) false
            else {
                perMemory[hit.memoryId] = count + 1
                true
            }
        }.take(limit)
    }
}
