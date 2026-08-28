package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.ai.api.AiAnswerRequest
import com.kareem.secondbrain.ai.api.AiAnswerResponse
import com.kareem.secondbrain.ai.api.AiClaim
import com.kareem.secondbrain.ai.api.AiProvider
import com.kareem.secondbrain.ai.api.SummaryRequest
import com.kareem.secondbrain.ai.api.SummaryResponse
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GroundedAskPrivacyTest {
    @Test
    fun ask_uploadsOnlyEvidenceWhoseAppPolicyAllowsAi() = runBlocking {
        val blocked = memory("blocked", "com.private.app", "Private planning note about Thursday.")
        val allowed = memory("allowed", "com.allowed.app", "Project review is Wednesday at 3 PM.")
        val provider = RecordingProvider()
        val repository = GroundedAskRepository(
            searchRepository = FakeSearchRepository(
                listOf(
                    SearchHit("blocked", "chunk-blocked", blocked.body, 0.95),
                    SearchHit("allowed", "chunk-allowed", allowed.body, 0.90),
                ),
            ),
            memoryRepository = FakeMemoryRepository(mapOf(blocked.id to blocked, allowed.id to allowed)),
            policyRepository = FakePolicyRepository(
                mapOf(
                    "com.private.app" to AppCapturePolicy("com.private.app", allowAiUpload = false),
                    "com.allowed.app" to AppCapturePolicy("com.allowed.app", allowAiUpload = true),
                ),
            ),
            aiProvider = provider,
            cloudAiEnabled = { true },
        )

        val answer = repository.ask("When is the project review?")

        val uploaded = provider.lastRequest?.evidence.orEmpty()
        assertEquals(1, uploaded.size)
        assertEquals("allowed", uploaded.single().memoryId)
        assertFalse(uploaded.any { it.memoryId == "blocked" })
        assertTrue(answer.evidence.any { it.memoryId == "blocked" && !it.cloudEligible })
        assertTrue(answer.note.orEmpty().contains("stayed local"))
    }

    @Test
    fun ask_neverCallsProviderWhenAllMatchingAppEvidenceIsLocalOnly() = runBlocking {
        val blocked = memory("blocked", "com.private.app", "Private planning note about Thursday.")
        val provider = RecordingProvider()
        val repository = GroundedAskRepository(
            searchRepository = FakeSearchRepository(listOf(SearchHit("blocked", "chunk-blocked", blocked.body, 0.95))),
            memoryRepository = FakeMemoryRepository(mapOf(blocked.id to blocked)),
            policyRepository = FakePolicyRepository(
                mapOf("com.private.app" to AppCapturePolicy("com.private.app", allowAiUpload = false)),
            ),
            aiProvider = provider,
            cloudAiEnabled = { true },
        )

        val answer = repository.ask("What did I save?")

        assertEquals(0, provider.callCount)
        assertTrue(answer.answer.contains("keep them local"))
        assertTrue(answer.evidence.single().cloudEligible.not())
    }

    @Test
    fun ask_neverCallsProviderWhenGlobalCloudConsentIsOff() = runBlocking {
        val allowed = memory("allowed", "com.allowed.app", "Project review is Wednesday at 3 PM.")
        val provider = RecordingProvider()
        val repository = GroundedAskRepository(
            searchRepository = FakeSearchRepository(listOf(SearchHit("allowed", "chunk-allowed", allowed.body, 0.90))),
            memoryRepository = FakeMemoryRepository(mapOf(allowed.id to allowed)),
            policyRepository = FakePolicyRepository(
                mapOf("com.allowed.app" to AppCapturePolicy("com.allowed.app", allowAiUpload = true)),
            ),
            aiProvider = provider,
            cloudAiEnabled = { false },
        )

        val answer = repository.ask("When is the project review?")

        assertEquals(0, provider.callCount)
        assertTrue(answer.answer.contains("Cloud synthesis is off"))
        assertFalse(answer.evidence.single().cloudEligible)
    }

    private fun memory(id: String, packageName: String, body: String) = Memory(
        id = id,
        kind = MemoryKind.NOTE,
        title = null,
        body = body,
        summary = null,
        sourcePackage = packageName,
        startedAt = Instant.parse("2026-08-28T10:00:00Z"),
        endedAt = null,
        importance = 0.5,
        pinned = false,
        longTerm = false,
        expiresAt = null,
    )

    private class RecordingProvider : AiProvider {
        var lastRequest: AiAnswerRequest? = null
        var callCount = 0

        override suspend fun answer(request: AiAnswerRequest): AiAnswerResponse {
            callCount += 1
            lastRequest = request
            val evidence = request.evidence.single()
            return AiAnswerResponse(
                claims = listOf(AiClaim("Project review is Wednesday at 3 PM.", listOf(evidence.evidenceId))),
                insufficientEvidence = false,
            )
        }

        override suspend fun summarize(request: SummaryRequest): SummaryResponse = SummaryResponse("", emptyList())
    }

    private class FakeSearchRepository(private val hits: List<SearchHit>) : MemorySearchRepository {
        override suspend fun search(request: SearchRequest): List<SearchHit> = hits
        override suspend fun index(memoryId: String) = Unit
        override suspend fun rebuildIndex() = Unit
    }

    private class FakeMemoryRepository(private val memories: Map<String, Memory>) : MemoryRepository {
        override fun observeTimeline(request: TimelineRequest): Flow<List<Memory>> = flowOf(memories.values.toList())
        override suspend fun getMemory(id: String): Memory? = memories[id]
        override suspend fun pin(id: String, pinned: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private class FakePolicyRepository(private val policies: Map<String, AppCapturePolicy>) : CapturePolicyRepository {
        override fun observePolicies(): Flow<List<AppCapturePolicy>> = flowOf(policies.values.toList())
        override suspend fun get(packageName: String): AppCapturePolicy =
            policies[packageName] ?: AppCapturePolicy(packageName)
        override suspend fun set(policy: AppCapturePolicy) = Unit
    }
}
