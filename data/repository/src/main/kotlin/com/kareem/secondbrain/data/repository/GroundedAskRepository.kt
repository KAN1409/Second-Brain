package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.ai.api.AiAnswerRequest
import com.kareem.secondbrain.ai.api.AiAnswerResponse
import com.kareem.secondbrain.ai.api.AiClaim as ProviderClaim
import com.kareem.secondbrain.ai.api.AiProvider
import com.kareem.secondbrain.ai.api.AiProviderUnavailableException
import com.kareem.secondbrain.ai.api.Evidence
import com.kareem.secondbrain.core.model.Memory
import com.kareem.secondbrain.core.model.SearchHit
import com.kareem.secondbrain.core.model.SearchRequest
import com.kareem.secondbrain.core.model.TimelineRequest
import com.kareem.secondbrain.domain.AskAnswer
import com.kareem.secondbrain.domain.AskClaim
import com.kareem.secondbrain.domain.AskEvidence
import com.kareem.secondbrain.domain.AskRepository
import com.kareem.secondbrain.domain.AskSynthesisMode
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.MemoryRepository
import com.kareem.secondbrain.domain.MemorySearchRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import kotlin.math.abs
import kotlin.math.round

class GroundedAskRepository(
    private val searchRepository: MemorySearchRepository,
    private val memoryRepository: MemoryRepository,
    private val policyRepository: CapturePolicyRepository,
    private val aiProvider: AiProvider? = null,
    private val cloudAiEnabled: suspend () -> Boolean = { false },
    private val clock: Clock = Clock.systemDefaultZone(),
) : AskRepository {

    override suspend fun ask(question: String): AskAnswer {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isBlank()) {
            return AskAnswer(
                question = normalizedQuestion,
                answer = "Enter a question about your captured memories.",
                claims = emptyList(),
                evidence = emptyList(),
                insufficientEvidence = true,
                synthesisMode = AskSynthesisMode.NONE,
                confidence = 0.0,
            )
        }

        val globalCloudEnabled = runCatching { cloudAiEnabled() }.getOrDefault(false)
        val plan = AskQueryPlanner.plan(
            question = normalizedQuestion,
            clock = clock,
            aiProvider = aiProvider,
            allowModelPlanning = globalCloudEnabled,
        )
        val hits = retrieveAcrossPlan(plan)
        val candidates = hits.mapNotNull { hit ->
            memoryRepository.getMemory(hit.memoryId)?.let { memory ->
                val softHintBoost = if (memory.kind in plan.softKindHints) SOFT_KIND_HINT_BOOST else 0.0
                AskCandidate(hit, memory, (hit.score + softHintBoost).coerceIn(0.0, 1.0))
            }
        }.sortedByDescending(AskCandidate::score)
            .take(MAX_EVIDENCE_MEMORIES)

        val evidence = mutableListOf<AskEvidence>()
        for (candidate in candidates) {
            val hit = candidate.hit
            val memory = candidate.memory
            val sourcePackage = memory.sourcePackage
            val policyAllowsCloud = sourcePackage == null || runCatching {
                policyRepository.get(sourcePackage).allowAiUpload
            }.getOrDefault(false)
            val excerpt = buildString {
                memory.title?.takeIf { it.isNotBlank() }?.let {
                    append(it.trim())
                    append('\n')
                }
                append(hit.snippet.ifBlank { memory.body }.trim())
            }.take(MAX_EVIDENCE_CHARS)
            if (excerpt.isBlank()) continue
            evidence += AskEvidence(
                id = "E${evidence.size + 1}",
                memoryId = memory.id,
                chunkId = hit.chunkId,
                text = excerpt,
                sourcePackage = sourcePackage,
                sourceLabel = sourcePackage ?: memory.kind.name,
                occurredAt = memory.startedAt,
                retrievalScore = candidate.score,
                cloudEligible = globalCloudEnabled && policyAllowsCloud,
            )
        }

        if (evidence.isEmpty()) {
            return AskAnswer(
                question = normalizedQuestion,
                answer = "I don't have enough captured evidence to answer that.",
                claims = emptyList(),
                evidence = emptyList(),
                insufficientEvidence = true,
                synthesisMode = AskSynthesisMode.NONE,
                confidence = 0.0,
            )
        }

        val cloudEvidence = evidence.filter(AskEvidence::cloudEligible)
        val blockedCount = if (globalCloudEnabled) evidence.size - cloudEvidence.size else 0
        var providerUnavailable = false
        val provider = aiProvider
        val providerResponse = if (provider != null && cloudEvidence.isNotEmpty()) {
            runCatching {
                provider.answer(
                    AiAnswerRequest(
                        question = normalizedQuestion,
                        evidence = cloudEvidence.map { item ->
                            Evidence(
                                evidenceId = item.id,
                                memoryId = item.memoryId,
                                chunkId = item.chunkId,
                                source = item.sourceLabel,
                                occurredAtMs = item.occurredAt.toEpochMilli(),
                                text = item.text,
                            )
                        },
                    ),
                )
            }.onFailure { throwable ->
                providerUnavailable = throwable is AiProviderUnavailableException || throwable is java.io.IOException
            }.getOrNull()
        } else {
            null
        }

        val validated = providerResponse
            ?.takeUnless(AiAnswerResponse::insufficientEvidence)
            ?.let { GroundedClaimValidator.validate(it.claims, cloudEvidence) }
            .orEmpty()

        if (validated.isNotEmpty()) {
            val claims = validated.map(ValidatedClaim::claim)
            val answerText = claims.joinToString(separator = "\n\n", transform = AskClaim::text)
            val confidence = deterministicConfidence(evidence, validated)
            return AskAnswer(
                question = normalizedQuestion,
                answer = answerText,
                claims = claims,
                evidence = evidence,
                insufficientEvidence = false,
                synthesisMode = AskSynthesisMode.CLOUD,
                confidence = confidence,
                note = privacyNote(blockedCount),
            )
        }

        val synthesisMessage = when {
            !globalCloudEnabled ->
                "Cloud synthesis is off. Showing retrieved evidence locally."
            providerResponse?.insufficientEvidence == true ->
                "I found related memories, but there isn't enough directly supported evidence for a confident answer."
            cloudEvidence.isEmpty() ->
                "I found relevant memories, but their app policies keep them local. Showing the evidence without cloud synthesis."
            providerUnavailable ->
                "I found relevant memories, but cloud synthesis is unavailable. Showing the evidence only."
            provider == null ->
                "I found relevant memories. Cloud synthesis is not configured, so I'm showing evidence only."
            else ->
                "I found relevant memories, but no fully supported answer survived evidence validation."
        }
        return AskAnswer(
            question = normalizedQuestion,
            answer = synthesisMessage,
            claims = emptyList(),
            evidence = evidence,
            insufficientEvidence = providerResponse?.insufficientEvidence == true,
            synthesisMode = AskSynthesisMode.EVIDENCE_ONLY,
            confidence = evidenceOnlyConfidence(evidence),
            note = privacyNote(blockedCount),
        )
    }

    /**
     * Execute several interpretations without allowing any one interpretation to exclude the others.
     * Text/semantic retrieval is fused with a temporal-window channel whenever the user explicitly
     * supplied a time or date. That means a question such as "what did I do around 3pm?" can still
     * retrieve the actual events in that window even when no text token such as "do" exists in memory.
     */
    private suspend fun retrieveAcrossPlan(plan: AskSearchPlan): List<SearchHit> {
        val fused = linkedMapOf<String, FusedHit>()
        val hasTemporalChannel = plan.from != null && plan.to != null
        val totalChannels = plan.queries.size + if (hasTemporalChannel) 1 else 0

        plan.queries.forEach { query ->
            val hits = searchRepository.search(
                SearchRequest(
                    query = query,
                    from = plan.from,
                    to = plan.to,
                    limit = SEARCH_LIMIT,
                ),
            )
            hits.forEachIndexed { index, hit ->
                val current = fused.getOrPut(hit.memoryId) { FusedHit(bestHit = hit) }
                current.reciprocalRank += 1.0 / (PLAN_RRF_K + index + 1.0)
                current.channelsMatched += 1
                if (hit.score > current.bestHit.score) current.bestHit = hit
            }
        }

        if (hasTemporalChannel) {
            addTemporalWindowChannel(plan, fused)
        }

        if (fused.isEmpty()) return emptyList()

        val maxRrf = fused.values.maxOf(FusedHit::reciprocalRank).coerceAtLeast(1e-9)
        return fused.values.map { item ->
            val agreement = (item.channelsMatched.toDouble() / totalChannels.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val score = (
                0.62 * (item.reciprocalRank / maxRrf) +
                    0.28 * item.bestHit.score.coerceIn(0.0, 1.0) +
                    0.10 * agreement
                ).coerceIn(0.0, 1.0)
            item.bestHit.copy(score = score)
        }.sortedByDescending(SearchHit::score)
            .take(SEARCH_LIMIT)
    }

    private suspend fun addTemporalWindowChannel(
        plan: AskSearchPlan,
        fused: MutableMap<String, FusedHit>,
    ) {
        val from = plan.from ?: return
        val to = plan.to ?: return
        val fromMs = from.toEpochMilli()
        val toMs = to.toEpochMilli()
        if (toMs <= fromMs) return
        val midpointMs = fromMs + (toMs - fromMs) / 2L
        val halfWindowMs = ((toMs - fromMs) / 2L).coerceAtLeast(1L)

        val memories = memoryRepository.observeTimeline(
            TimelineRequest(from = from, to = to),
        ).first()

        val rankedByTime = memories.asSequence()
            .map { memory ->
                val distanceMs = abs(memory.startedAt.toEpochMilli() - midpointMs)
                val proximity = (1.0 - distanceMs.toDouble() / halfWindowMs.toDouble()).coerceIn(0.0, 1.0)
                memory to proximity
            }
            .sortedWith(
                compareByDescending<Pair<Memory, Double>> { it.second }
                    .thenByDescending { it.first.startedAt },
            )
            .take(TEMPORAL_WINDOW_LIMIT)
            .toList()

        rankedByTime.forEachIndexed { index, (memory, proximity) ->
            val temporalScore = (TEMPORAL_SCORE_FLOOR + (1.0 - TEMPORAL_SCORE_FLOOR) * proximity)
                .coerceIn(0.0, 1.0)
            val snippet = memory.body.ifBlank {
                memory.summary?.takeIf(String::isNotBlank) ?: memory.title.orEmpty()
            }
            val hit = SearchHit(
                memoryId = memory.id,
                chunkId = null,
                snippet = snippet,
                score = temporalScore,
            )
            val current = fused.getOrPut(memory.id) { FusedHit(bestHit = hit) }
            current.reciprocalRank += 1.0 / (PLAN_RRF_K + index + 1.0)
            current.channelsMatched += 1
            if (hit.score > current.bestHit.score) current.bestHit = hit
        }
    }

    private fun deterministicConfidence(
        evidence: List<AskEvidence>,
        claims: List<ValidatedClaim>,
    ): Double {
        val retrieval = evidence.take(3).map(AskEvidence::retrievalScore).averageOrZero()
        val support = claims.map(ValidatedClaim::supportScore).averageOrZero()
        val diversity = (claims.flatMap { it.claim.evidenceIds }.distinct().size / 3.0).coerceIn(0.0, 1.0)
        return round3(0.45 * retrieval + 0.40 * support + 0.15 * diversity)
    }

    private fun evidenceOnlyConfidence(evidence: List<AskEvidence>): Double =
        round3(0.65 * evidence.take(3).map(AskEvidence::retrievalScore).averageOrZero())

    private fun privacyNote(blockedCount: Int): String? = when {
        blockedCount <= 0 -> null
        blockedCount == 1 -> "1 matching memory stayed local because its app policy blocks AI upload."
        else -> "$blockedCount matching memories stayed local because their app policies block AI upload."
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun round3(value: Double): Double = round(value.coerceIn(0.0, 1.0) * 1000.0) / 1000.0

    private companion object {
        const val SEARCH_LIMIT = 24
        const val MAX_EVIDENCE_MEMORIES = 12
        const val MAX_EVIDENCE_CHARS = 1800
        const val PLAN_RRF_K = 30.0
        const val SOFT_KIND_HINT_BOOST = 0.04
        const val TEMPORAL_WINDOW_LIMIT = 64
        const val TEMPORAL_SCORE_FLOOR = 0.55
    }
}

private data class FusedHit(
    var bestHit: SearchHit,
    var reciprocalRank: Double = 0.0,
    var channelsMatched: Int = 0,
)

private data class AskCandidate(
    val hit: SearchHit,
    val memory: Memory,
    val score: Double,
)

internal data class ValidatedClaim(
    val claim: AskClaim,
    val supportScore: Double,
)

internal object GroundedClaimValidator {
    private const val MIN_SUPPORT_SCORE = 0.60
    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")
    private val numberRegex = Regex("\\d+(?:[.:]\\d+)?")
    private val polarityMarkers = setOf("not", "never", "no", "without", "مش", "ليس", "لن", "لم")
    private val stopWords = setOf(
        "the", "a", "an", "is", "are", "was", "were", "to", "of", "and", "or", "in", "on", "at", "for", "with",
        "that", "this", "it", "he", "she", "they", "قال", "قالت", "هو", "هي", "في", "من", "على", "إلى", "الى", "عن",
        "كان", "كانت", "ده", "دي", "و",
    )

    fun validate(claims: List<ProviderClaim>, evidence: List<AskEvidence>): List<ValidatedClaim> {
        if (claims.isEmpty() || evidence.isEmpty()) return emptyList()
        val evidenceById = evidence.associateBy(AskEvidence::id)
        return claims.mapNotNull { providerClaim ->
            val text = providerClaim.text.trim()
            if (text.isBlank()) return@mapNotNull null
            val validIds = providerClaim.evidenceIds.distinct().filter(evidenceById::containsKey)
            if (validIds.isEmpty()) return@mapNotNull null
            val supportText = validIds.joinToString("\n") { evidenceById.getValue(it).text }
            val supportScore = supportScore(text, supportText)
            if (supportScore < MIN_SUPPORT_SCORE) return@mapNotNull null
            ValidatedClaim(AskClaim(text = text, evidenceIds = validIds), supportScore)
        }
    }

    fun supportScore(claim: String, evidence: String): Double {
        val claimTokens = meaningfulTokens(claim)
        if (claimTokens.isEmpty()) return 0.0
        val evidenceTokens = meaningfulTokens(evidence).toHashSet()

        val claimNumbers = numberRegex.findAll(claim).map { it.value }.toSet()
        val evidenceNumbers = numberRegex.findAll(evidence).map { it.value }.toSet()
        if (!evidenceNumbers.containsAll(claimNumbers)) return 0.0

        val claimPolarity = claimTokens.filter { it in polarityMarkers }.toSet()
        val evidencePolarity = evidenceTokens.filter { it in polarityMarkers }.toSet()
        if (!evidencePolarity.containsAll(claimPolarity)) return 0.0

        val overlap = claimTokens.count(evidenceTokens::contains)
        if (overlap == 0) return 0.0
        return (overlap / minOf(claimTokens.size, 8).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun meaningfulTokens(text: String): List<String> = tokenRegex.findAll(text.lowercase())
        .map { it.value }
        .filter { it.length > 1 && it !in stopWords }
        .distinct()
        .toList()
}
