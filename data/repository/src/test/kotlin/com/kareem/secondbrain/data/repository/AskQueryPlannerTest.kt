package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.core.model.MemoryKind
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
    fun sendAt3pm_becomesNotificationSearchWithTimeWindow() {
        val request = AskQueryPlanner.plan("what did Kareem send at 3pm the grill", clock)

        assertEquals(setOf(MemoryKind.NOTIFICATION), request.kinds)
        assertTrue(request.query.contains("Kareem", ignoreCase = true))
        assertFalse(request.query.contains("send", ignoreCase = true))
        assertFalse(request.query.contains("3pm", ignoreCase = true))
        assertEquals(Instant.parse("2026-08-28T10:45:00Z"), request.from)
        assertEquals(Instant.parse("2026-08-28T13:15:00Z"), request.to)
    }

    @Test
    fun futureTimeWithoutDate_usesNearestPastOccurrence() {
        val request = AskQueryPlanner.plan("what did Kareem send at 11pm", clock)

        assertEquals(Instant.parse("2026-08-27T18:45:00Z"), request.from)
        assertEquals(Instant.parse("2026-08-27T21:15:00Z"), request.to)
    }

    @Test
    fun ordinaryQuestionWithoutMetadataFilters_keepsMeaningfulTerms() {
        val request = AskQueryPlanner.plan("Where did I save the camera receipt?", clock)

        assertTrue(request.kinds.isEmpty())
        assertEquals(null, request.from)
        assertEquals(null, request.to)
        assertTrue(request.query.contains("camera", ignoreCase = true))
        assertTrue(request.query.contains("receipt", ignoreCase = true))
    }
}
