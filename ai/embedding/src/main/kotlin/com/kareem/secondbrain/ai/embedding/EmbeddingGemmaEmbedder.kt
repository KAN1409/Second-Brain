package com.kareem.secondbrain.ai.embedding

import android.content.Context
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.kareem.secondbrain.ai.api.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

class EmbeddingGemmaEmbedder(
    context: Context,
    private val modelFile: File = File(context.filesDir, MODEL_RELATIVE_PATH),
) : Embedder {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var engine: TextEmbedder? = null

    override val signature: String = SIGNATURE

    override suspend fun embed(texts: List<String>): List<FloatArray> = embedDocuments(texts)

    override suspend fun embedQuery(text: String): FloatArray =
        embedPrepared("task: search result | query: ${text.trim()}")

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        texts.map { text -> embedPrepared("title: none | text: ${text.trim()}") }

    private suspend fun embedPrepared(text: String): FloatArray = withContext(Dispatchers.Default) {
        require(text.isNotBlank()) { "Embedding input cannot be blank" }
        synchronized(lock) {
            val result = getOrCreateEngine().embed(text)
            val raw = result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                ?: error("EmbeddingGemma returned no float embedding")
            EmbeddingGemmaVector.truncateAndNormalize(raw, OUTPUT_DIMENSIONS)
        }
    }

    private fun getOrCreateEngine(): TextEmbedder {
        engine?.let { return it }
        if (!modelFile.isFile || modelFile.length() <= 0L) {
            throw EmbeddingModelUnavailableException(modelFile)
        }
        return TextEmbedder.createFromFile(appContext, modelFile).also { engine = it }
    }

    companion object {
        const val OUTPUT_DIMENSIONS = 256
        const val SIGNATURE = "embeddinggemma:mediapipe:256:v1"
        const val MODEL_RELATIVE_PATH = "models/embeddinggemma.task"
    }
}

class EmbeddingModelUnavailableException(modelFile: File) :
    IllegalStateException("EmbeddingGemma model is unavailable at ${modelFile.absolutePath}")

object EmbeddingGemmaVector {
    fun truncateAndNormalize(source: FloatArray, dimensions: Int): FloatArray {
        require(dimensions > 0) { "dimensions must be positive" }
        require(source.size >= dimensions) {
            "Embedding has ${source.size} dimensions, expected at least $dimensions"
        }
        val result = source.copyOf(dimensions)
        var squaredNorm = 0.0
        for (value in result) squaredNorm += value.toDouble() * value.toDouble()
        val norm = sqrt(squaredNorm)
        require(norm > 0.0 && norm.isFinite()) { "Embedding norm must be finite and non-zero" }
        for (index in result.indices) result[index] = (result[index] / norm).toFloat()
        return result
    }
}
