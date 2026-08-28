package com.kareem.secondbrain.core.search

data class SemanticIndexDocument(
    val chunkId: String,
    val memoryId: String,
    val text: String,
    val vector: FloatArray,
    val modelSignature: String,
)

interface SemanticAccelerationIndex {
    suspend fun isSupported(): Boolean
    suspend fun upsert(documents: List<SemanticIndexDocument>): Boolean
    suspend fun candidateChunkIds(
        queryVector: FloatArray,
        modelSignature: String,
        limit: Int,
    ): Set<String>?
}
