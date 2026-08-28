package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.ai.api.AiAnswerRequest
import com.kareem.secondbrain.ai.api.AiAnswerResponse
import com.kareem.secondbrain.ai.api.AiProvider
import com.kareem.secondbrain.ai.api.AiQueryPlanRequest
import com.kareem.secondbrain.ai.api.AiQueryPlanResponse
import com.kareem.secondbrain.ai.api.SummaryRequest
import com.kareem.secondbrain.ai.api.SummaryResponse
import com.kareem.secondbrain.core.model.MemoryKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AskQueryPlannerTest {
    private val cairoSummer = ZoneOffset.ofHours(3)
    private val clock = Clock.fixed(Instant.parse("2026-08-28T12:30:00Z"), cairoSummer)

    @Test
    fun sendAt3pm_keepsEventTypeOpenAndUsesOnlyExplicitTimeAsHardConstraint() = runBlocking {
        val plan = AskQueryPlanner.plan("what did Kareem send at 3pm the grill", clock)

        assertTrue(plan.softKindHints.isEmpty())
        assertTrue(plan.queries.any { it.contains("Kareem", ignoreCase = true) })
        assertTrue(plan.queries.any { it.contains("send", ignoreCase = true) })
        assertFalse(plan.queries.first().contains("3pm", ignoreCase = true))
        assertEquals(Instant.parse("2026-08-28T10:30:00Z"), plan.from)
        assertEquals(Instant.parse("2026-08-28T13:30:00Z"), plan.to)
    }

    @Test
    fun futureTimeWithoutDate_usesNearestPastOccurrence() = runBlocking {
        val plan = AskQueryPlanner.plan("what did Kareem do at 11pm", clock)

        assertEquals(Instant.parse("2026-08-27T18:30:00Z"), plan.from)
        assertEquals(Instant.parse("2026-08-27T21:30:00Z"), plan.to)
    }

    @Test
    fun ordinaryQuestionWithoutMetadataFilters_staysBroad() = runBlocking {
        val plan = AskQueryPlanner.plan("Where did I save the camera receipt?", clock)

        assertEquals(null, plan.from)
        assertEquals(null, plan.to)
        assertTrue(plan.queries.any { it.contains("save", ignoreCase = true) })
        assertTrue(plan.queries.any { it.contains("camera", ignoreCase = true) })
        assertTrue(plan.queries.any { it.contains("receipt", ignoreCase = true) })
    }

    @Test
    fun modelHints_expandSearchButRemainSoft() = runBlocking {
        val provider = PlanningProvider(
            AiQueryPlanResponse(
                semanticQueries = listOf("Kareem activity", "Kareem shared item"),
                softKindHints = listOf("NOTIFICATION", "NOTE", "SCREEN_CONTEXT"),
                relationHints = listOf("AROUND"),
            ),
        )

        val plan = AskQueryPlanner.plan(
            question = "what did Kareem do around 3pm",
            clock = clock,
            aiProvider = provider,
            allowModelPlanning = true,
        )

        assertTrue(plan.usedModelPlanner)
        assertTrue(plan.queries.contains("Kareem activity"))
        assertEquals(setOf(MemoryKind.NOTIFICATION, MemoryKind.NOTE, MemoryKind.SCREEN_CONTEXT), plan.softKindHints)
        assertEquals(setOf("AROUND"), plan.relationHints)
        assertEquals(1, provider.planCalls)
    }

    private class PlanningProvider(private val response: AiQueryPlanResponse) : AiProvider {
        var planCalls = 0

        override suspend fun planQuery(request: AiQueryPlanRequest): AiQueryPlanResponse {
            planCalls += 1
            return response
        }

        override suspend fun answer(request: AiAnswerRequest): AiAnswerResponse =
            AiAnswerResponse(emptyList(), insufficientEvidence = true)

        override suspend fun summarize(request: SummaryRequest): SummaryResponse = SummaryResponse("", emptyList())
    }
}
