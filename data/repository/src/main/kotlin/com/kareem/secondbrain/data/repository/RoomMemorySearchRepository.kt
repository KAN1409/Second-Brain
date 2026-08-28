package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.ai.api.Embedder
import com.kareem.secondbrain.core.database.MemoryChunkEntity
import com.kareem.secondbrain.core.database.MemoryEmbeddingEntity
import com.kareem.secondbrain.core.database.MemoryEntity
import com.kareem.secondbrain.core.database.SearchDao
import com.kareem.secondbrain.core.model.SearchHit
import com.kareem.secondbrain.core.model.SearchRequest
import com.kareem.secondbrain.core.search.HybridRanker
import com.kareem.secondbrain.core.search.MemoryChunker
import com.kareem.secondbrain.core.search.SearchCandidate
import com.kareem.secondbrain.core.search.SearchTextScorer
import com.kareem.secondbrain.core.search.VectorCodec
import com.kareem.secondbrain.domain.MemorySearchRepository
import java.time.Clock

class RoomMemorySearchRepository(
    private val dao: SearchDao,
    private val embedder: Embedder? = null,
    private val clock: Clock = Clock.systemUTC(),
) : MemorySearchRepository {

    override suspend fun search(request: SearchRequest): List<SearchHit> {
        val query = request.query.trim()
        if (query.isBlank()) return emptyList()

        val filteredMemories = dao.searchableMemories(
            fromMs = request.from?.toEpochMilli(),
            toMs = request.to?.toEpochMilli(),
            pinnedOnly = request.pinnedOnly,
        ).filter { memory ->
            (request.appPackages.isEmpty() || memory.source_package in request.appPackages) &&
                (request.kinds.isEmpty() || request.kinds.any { it.name == memory.kind })
        }
        if (filteredMemories.isEmpty()) return emptyList()

        filteredMemories.forEach { memory ->
            if (dao.chunkCount(memory.id) == 0) indexEntity(memory)
        }

        val memoryById = filteredMemories.associateBy(MemoryEntity::id)
        val chunks = dao.chunksForMemories(filteredMemories.map(MemoryEntity::id))
        if (chunks.isEmpty()) return emptyList()

        val activeEmbedder = embedder
        val queryVector = activeEmbedder?.let { engine ->
            runCatching { engine.embed(listOf(query)).singleOrNull() }.getOrNull()
        }
        val embeddingByChunk = if (activeEmbedder != null && queryVector != null) {
            dao.embeddingsForChunks(chunks.map(MemoryChunkEntity::id), activeEmbedder.signature)
                .associateBy(MemoryEmbeddingEntity::chunk_id)
        } else {
            emptyMap()
        }

        val hasSourceFilter = request.appPackages.isNotEmpty() || request.kinds.isNotEmpty()
        val candidates = chunks.mapNotNull { chunk ->
            val memory = memoryById[chunk.memory_id] ?: return@mapNotNull null
            val lexical = SearchTextScorer.lexicalScore(query, chunk.text)
            val semantic = if (queryVector != null) {
                embeddingByChunk[chunk.id]?.let { stored ->
                    runCatching {
                        VectorCodec.cosine(queryVector, VectorCodec.decode(stored.vector_blob, stored.dimensions))
                    }.getOrNull()
                }
            } else null
            if (lexical <= 0.0 && (semantic ?: 0.0) <= 0.0) return@mapNotNull null
            SearchCandidate(
                memoryId = memory.id,
                chunkId = chunk.id,
                snippet = chunk.text.take(600),
                lexicalScore = lexical,
                semanticScore = semantic,
                startedAtMs = memory.started_at,
                importance = memory.importance,
                sourceMatched = hasSourceFilter,
            )
        }

        return HybridRanker.rank(candidates, clock.millis(), request.limit)
            .map { ranked ->
                SearchHit(
                    memoryId = ranked.memoryId,
                    chunkId = ranked.chunkId,
                    snippet = ranked.snippet,
                    score = ranked.score,
                )
            }
    }

    override suspend fun index(memoryId: String) {
        dao.memory(memoryId)?.let { indexEntity(it) }
    }

    override suspend fun rebuildIndex() {
        dao.deleteAllChunks()
        dao.allMemoryIds().forEach { memoryId ->
            dao.memory(memoryId)?.let { indexEntity(it) }
        }
    }

    private suspend fun indexEntity(memory: MemoryEntity) {
        val drafts = MemoryChunker.chunk(memory.title, memory.body, memory.summary)
        val chunks = drafts.map { draft ->
            MemoryChunkEntity(
                id = "${memory.id}:${draft.ordinal}:${draft.contentHash.take(16)}",
                memory_id = memory.id,
                ordinal = draft.ordinal,
                text = draft.text,
                content_hash = draft.contentHash,
                embedding_model_signature = null,
                index_state = "READY",
            )
        }
        dao.replaceChunks(memory.id, chunks)
        if (chunks.isEmpty()) return

        val activeEmbedder = embedder ?: return
        val vectors = runCatching { activeEmbedder.embed(chunks.map(MemoryChunkEntity::text)) }.getOrNull() ?: return
        if (vectors.size != chunks.size || vectors.any { it.isEmpty() }) return
        val now = clock.millis()
        dao.upsertEmbeddings(
            chunks.zip(vectors).map { (chunk, vector) ->
                MemoryEmbeddingEntity(
                    chunk_id = chunk.id,
                    model_signature = activeEmbedder.signature,
                    dimensions = vector.size,
                    encoding = VectorCodec.ENCODING,
                    vector_blob = VectorCodec.encode(vector),
                    created_at = now,
                )
            },
        )
        dao.markEmbedded(chunks.map(MemoryChunkEntity::id), activeEmbedder.signature)
    }
}
