package com.kareem.secondbrain.ai.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EmbeddingGemmaVectorTest {
    @Test
    fun truncateAndNormalize_returnsRequestedDimensionsAndUnitNorm() {
        val source = FloatArray(768) { index -> (index + 1).toFloat() }

        val reduced = EmbeddingGemmaVector.truncateAndNormalize(source, 256)

        assertEquals(256, reduced.size)
        val norm = sqrt(reduced.sumOf { it.toDouble() * it.toDouble() })
        assertTrue(kotlin.math.abs(norm - 1.0) < 1e-5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncateAndNormalize_rejectsTooSmallEmbedding() {
        EmbeddingGemmaVector.truncateAndNormalize(FloatArray(128) { 1f }, 256)
    }
}
