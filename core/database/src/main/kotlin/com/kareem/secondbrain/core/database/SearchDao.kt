package com.kareem.secondbrain.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

@Dao
abstract class SearchDao {
    @Query("SELECT * FROM memory WHERE id = :memoryId LIMIT 1")
    abstract suspend fun memory(memoryId: String): MemoryEntity?

    @Query("SELECT id FROM memory ORDER BY started_at DESC")
    abstract suspend fun allMemoryIds(): List<String>

    @Query("""
        SELECT * FROM memory
        WHERE (:fromMs IS NULL OR started_at >= :fromMs)
          AND (:toMs IS NULL OR started_at <= :toMs)
          AND (:pinnedOnly = 0 OR pinned = 1)
        ORDER BY started_at DESC
    """)
    abstract suspend fun searchableMemories(
        fromMs: Long?,
        toMs: Long?,
        pinnedOnly: Boolean,
    ): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memory_chunk WHERE memory_id = :memoryId")
    abstract suspend fun chunkCount(memoryId: String): Int

    @Query("SELECT * FROM memory_chunk WHERE memory_id IN (:memoryIds) ORDER BY memory_id, ordinal")
    abstract suspend fun chunksForMemories(memoryIds: List<String>): List<MemoryChunkEntity>

    @Query("SELECT * FROM memory_chunk WHERE memory_id = :memoryId ORDER BY ordinal")
    abstract suspend fun chunksForMemory(memoryId: String): List<MemoryChunkEntity>

    @Query("DELETE FROM memory_chunk WHERE memory_id = :memoryId")
    protected abstract suspend fun deleteChunksForMemory(memoryId: String)

    @Query("DELETE FROM memory_chunk")
    abstract suspend fun deleteAllChunks()

    @Upsert
    protected abstract suspend fun upsertChunks(chunks: List<MemoryChunkEntity>)

    @Upsert
    abstract suspend fun upsertEmbeddings(embeddings: List<MemoryEmbeddingEntity>)

    @Query("""
        SELECT * FROM memory_embedding
        WHERE model_signature = :modelSignature
          AND chunk_id IN (:chunkIds)
    """)
    abstract suspend fun embeddingsForChunks(
        chunkIds: List<String>,
        modelSignature: String,
    ): List<MemoryEmbeddingEntity>

    @Query("""
        UPDATE memory_chunk
        SET embedding_model_signature = :modelSignature,
            index_state = 'READY'
        WHERE id IN (:chunkIds)
    """)
    abstract suspend fun markEmbedded(chunkIds: List<String>, modelSignature: String)

    @Transaction
    open suspend fun replaceChunks(memoryId: String, chunks: List<MemoryChunkEntity>) {
        deleteChunksForMemory(memoryId)
        if (chunks.isNotEmpty()) upsertChunks(chunks)
    }
}
