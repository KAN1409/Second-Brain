package com.kareem.secondbrain.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAlgorithmsTest {
    @Test
    fun chunker_respectsTargetAndOverlap() {
        val body = (1..900).joinToString(" ") { "word$it" }
        val chunks = MemoryChunker.chunk(null, body, null)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.text.split(' ').size <= MemoryChunker.HARD_MAX_TOKENS })
        val first = chunks[0].text.split(' ')
        val second = chunks[1].text.split(' ')
        assertEquals(first.takeLast(MemoryChunker.OVERLAP_TOKENS), second.take(MemoryChunker.OVERLAP_TOKENS))
    }

    @Test
    fun vectorCodec_roundTripsAndCosineIsOneForSameVector() {
        val source = floatArrayOf(0.25f, -0.5f, 0.75f)
        val decoded = VectorCodec.decode(VectorCodec.encode(source), source.size)
        assertTrue(source.contentEquals(decoded))
        assertEquals(1.0, VectorCodec.cosine(source, decoded), 1e-6)
    }

    @Test
    fun lexicalScore_prefersFullPhrase() {
        val phrase = SearchTextScorer.lexicalScore("camera lens", "I compared a camera lens yesterday")
        val partial = SearchTextScorer.lexicalScore("camera lens", "camera bag and charger")
        assertTrue(phrase > partial)
    }

    @Test
    fun ranker_limitsChunksPerMemory() {
        val candidates = (0 until 5).map { index ->
            SearchCandidate(
                memoryId = "memory-1",
                chunkId = "chunk-$index",
                snippet = "text $index",
                lexicalScore = 1.0 - index * 0.05,
                semanticScore = null,
                startedAtMs = 1_000L,
                importance = 0.0,
                sourceMatched = false,
            )
        }
        val ranked = HybridRanker.rank(candidates, nowMs = 1_000L, limit = 10)
        assertEquals(2, ranked.size)
    }
}
