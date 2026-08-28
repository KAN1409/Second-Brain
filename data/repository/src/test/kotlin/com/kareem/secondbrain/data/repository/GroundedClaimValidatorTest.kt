package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.ai.api.AiClaim
import com.kareem.secondbrain.domain.AskEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GroundedClaimValidatorTest {
    private val evidence = AskEvidence(
        id = "E1",
        memoryId = "memory-1",
        chunkId = "chunk-1",
        text = "Sarah said Wednesday works for the project review at 3 PM.",
        sourcePackage = "com.example.chat",
        sourceLabel = "com.example.chat",
        occurredAt = Instant.parse("2026-08-28T10:00:00Z"),
        retrievalScore = 0.91,
        cloudEligible = true,
    )

    @Test
    fun validate_keepsSupportedClaimWithKnownEvidence() {
        val result = GroundedClaimValidator.validate(
            claims = listOf(AiClaim("Sarah said Wednesday works for the project review.", listOf("E1"))),
            evidence = listOf(evidence),
        )

        assertEquals(1, result.size)
        assertEquals(listOf("E1"), result.single().claim.evidenceIds)
        assertTrue(result.single().supportScore > 0.15)
    }

    @Test
    fun validate_dropsUnknownCitation() {
        val result = GroundedClaimValidator.validate(
            claims = listOf(AiClaim("Sarah said Wednesday works.", listOf("E999"))),
            evidence = listOf(evidence),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun validate_dropsUnsupportedClaimEvenWithValidCitationId() {
        val result = GroundedClaimValidator.validate(
            claims = listOf(AiClaim("The restaurant serves excellent sushi near the beach.", listOf("E1"))),
            evidence = listOf(evidence),
        )

        assertTrue(result.isEmpty())
    }
}
