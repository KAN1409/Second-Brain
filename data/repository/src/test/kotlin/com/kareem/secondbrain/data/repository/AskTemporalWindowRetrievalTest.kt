package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.core.model.AppCapturePolicy
import com.kareem.secondbrain.core.model.Memory
import com.kareem.secondbrain.core.model.MemoryKind
import com.kareem.secondbrain.core.model.SearchHit
import com.kareem.secondbrain.core.model.SearchRequest
import com.kareem.secondbrain.core.model.TimelineRequest
import com.kareem.secondbrain.domain.AskSynthesisMode
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.MemoryRepository
import com.kareem.secondbrain.domain.MemorySearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AskTemporalWindowRetrievalTest {
    private val cairoSummer = ZoneOffset.ofHours(3)
    private val clock = Clock.fixed(Instant.parse("2026-08-28T12:47:00Z"), cairoSummer)

    @Test
    fun dottedPmForm_createsExplicitTemporalWindow() = runBlocking {
        val plan = AskQueryPlanner.plan("what did I do around 3:00 p.m.?", clock)

        assertEquals(Instant.parse("2026-08-28T10:30:00Z"), plan.from)
        assertEquals(Instant.parse("2026-08-28T13:30:00Z"), plan.to)
        // Keeping the raw question as one semantic query variant is intentional. The hard
        // temporal bounds above, not removal of time text from every variant, are the contract.
        assertTrue(plan.queries.isNotEmpty())
    }

    @Test
    fun timeOnlyActivityQuestion_retrievesTimelineEvidenceWhenTextSearchHasNoHits() = runBlocking {
        val activity = Memory(
            id = "activity-at-3pm",
            kind = MemoryKind.APP_ACTIVITY,
            title = "Foreground activity",
            body = "com.example.camera was in the foreground.",
            summary = null,
            sourcePackage = "com.example.camera",
            startedAt = Instant.parse("2026-08-28T12:02:00Z"),
            endedAt = Instant.parse("2026-08-28T12:07:00Z"),
            importance = 0.5,
            pinned = false,
            longTerm = false,
            expiresAt = null,
        )
        val memoryRepository = TimelineMemoryRepository(listOf(activity))
        val repository = GroundedAskRepository(
            searchRepository = EmptySearchRepository(),
            memoryRepository = memoryRepository,
            policyRepository = LocalPolicyRepository(),
            cloudAiEnabled = { false },
            clock = clock,
        )

        val answer = repository.ask("what did I do around 3:00 p.m.?")

        assertNotNull(memoryRepository.lastTimelineRequest)
        assertEquals(AskSynthesisMode.EVIDENCE_ONLY, answer.synthesisMode)
        assertFalse(answer.insufficientEvidence)
        assertTrue(answer.evidence.any { it.memoryId == activity.id })
        assertTrue(answer.evidence.any { it.text.contains("camera", ignoreCase = true) })
    }

    private class EmptySearchRepository : MemorySearchRepository {
        override suspend fun search(request: SearchRequest): List<SearchHit> = emptyList()
        override suspend fun index(memoryId: String) = Unit
        override suspend fun rebuildIndex() = Unit
    }

    private class TimelineMemoryRepository(
        memories: List<Memory>,
    ) : MemoryRepository {
        private val byId = memories.associateBy(Memory::id)
        var lastTimelineRequest: TimelineRequest? = null

        override fun observeTimeline(request: TimelineRequest): Flow<List<Memory>> {
            lastTimelineRequest = request
            val filtered = byId.values.filter { memory ->
                (request.from?.let { memory.startedAt >= it } ?: true) &&
                    (request.to?.let { memory.startedAt <= it } ?: true)
            }
            return flowOf(filtered)
        }

        override suspend fun getMemory(id: String): Memory? = byId[id]
        override suspend fun pin(id: String, pinned: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private class LocalPolicyRepository : CapturePolicyRepository {
        override fun observePolicies(): Flow<List<AppCapturePolicy>> = flowOf(emptyList())
        override suspend fun get(packageName: String): AppCapturePolicy = AppCapturePolicy(packageName)
        override suspend fun set(policy: AppCapturePolicy) = Unit
    }
}
