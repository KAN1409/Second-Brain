package com.kareem.secondbrain.domain

import com.kareem.secondbrain.core.model.Memory
import com.kareem.secondbrain.core.model.SearchHit
import com.kareem.secondbrain.core.model.SearchRequest
import com.kareem.secondbrain.core.model.TimelineRequest
import kotlinx.coroutines.flow.Flow

interface MemoryRepository {
    fun observeTimeline(request: TimelineRequest): Flow<List<Memory>>
    suspend fun getMemory(id: String): Memory?
    suspend fun pin(id: String, pinned: Boolean)
    suspend fun delete(id: String)
}

interface MemorySearchRepository {
    suspend fun search(request: SearchRequest): List<SearchHit>
    suspend fun index(memoryId: String)
    suspend fun rebuildIndex()
}
