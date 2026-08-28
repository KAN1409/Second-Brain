package com.kareem.secondbrain.domain

import java.time.Instant

enum class AskSynthesisMode { CLOUD, EVIDENCE_ONLY, NONE }

data class AskEvidence(
    val id: String,
    val memoryId: String,
    val chunkId: String?,
    val text: String,
    val sourcePackage: String?,
    val sourceLabel: String,
    val occurredAt: Instant,
    val retrievalScore: Double,
    val cloudEligible: Boolean,
)

data class AskClaim(
    val text: String,
    val evidenceIds: List<String>,
)

data class AskAnswer(
    val question: String,
    val answer: String,
    val claims: List<AskClaim>,
    val evidence: List<AskEvidence>,
    val insufficientEvidence: Boolean,
    val synthesisMode: AskSynthesisMode,
    val confidence: Double,
    val note: String? = null,
)

interface AskRepository {
    suspend fun ask(question: String): AskAnswer
}
