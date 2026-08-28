package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.core.model.AppCapturePolicy
import com.kareem.secondbrain.core.model.Memory
import com.kareem.secondbrain.core.model.MemoryKind
import com.kareem.secondbrain.core.model.SearchHit
import com.kareem.secondbrain.core.model.SearchRequest
import com.kareem.secondbrain.core.model.TimelineRequest
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.MemoryRepository
import com.kareem.secondbrain.domain.MemorySearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GroundedAskOpenWorldRetrievalTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-28T12:30:00Z"), ZoneOffset.ofHours(3))

    @Test
    fun actionWordsNeverBecomeExclusiveMemoryKindFilters() = runBlocking {
        val memory = Memory(
            id = "m1",
            kind = MemoryKind.NOTIFICATION,
            title = "Kareem Abdel Nasser",
            body = "Kareem Abdel Nasser\nSB_M4_REAL_001",
            summary = null,
            sourcePackage = "com.whatsapp",
            startedAt = Instant.parse("2026-08-28T12:00:00Z"),
            endedAt = null,
            importance = 0.0,
            pinned = false,
            longTerm = false,
            expiresAt = null,
        )
        val search = RecordingSearchRepository(
            SearchHit(memoryId = memory.id, chunkId = "c1", snippet = memory.body, score = 0.9),
        )
        val repository = GroundedAskRepository(
            searchRepository = search,
            memoryRepository = FakeMemoryRepository(mapOf(memory.id to memory)),
            policyRepository = FakePolicyRepository(),
            cloudAiEnabled = { false },
            clock = clock,
        )

        val answer = repository.ask("what did Kareem send at 3pm")

        assertTrue(search.requests.size >= 2)
        assertTrue(search.requests.all { it.kinds.isEmpty() })
        assertTrue(search.requests.all { it.from == Instant.parse("2026-08-28T10:30:00Z") })
        assertTrue(search.requests.all { it.to == Instant.parse("2026-08-28T13:30:00Z") })
        assertTrue(search.requests.any { it.query.contains("Kareem", ignoreCase = true) })
        assertFalse(answer.evidence.isEmpty())
        assertTrue(answer.evidence.first().sourcePackage == "com.whatsapp")
    }

    private class RecordingSearchRepository(private val hit: SearchHit) : MemorySearchRepository {
        val requests = mutableListOf<SearchRequest>()
        override suspend fun search(request: SearchRequest): List<SearchHit> {
            requests += request
            return listOf(hit)
        }
        override suspend fun index(memoryId: String) = Unit
        override suspend fun rebuildIndex() = Unit
    }

    private class FakeMemoryRepository(private val memories: Map<String, Memory>) : MemoryRepository {
        override fun observeTimeline(request: TimelineRequest): Flow<List<Memory>> = flowOf(memories.values.toList())
        override suspend fun getMemory(id: String): Memory? = memories[id]
        override suspend fun pin(id: String, pinned: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private class FakePolicyRepository : CapturePolicyRepository {
        override fun observePolicies(): Flow<List<AppCapturePolicy>> = flowOf(emptyList())
        override suspend fun get(packageName: String): AppCapturePolicy = AppCapturePolicy(packageName)
        override suspend fun set(policy: AppCapturePolicy) = Unit
    }
}
