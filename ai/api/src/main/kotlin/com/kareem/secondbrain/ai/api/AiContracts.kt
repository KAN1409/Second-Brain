package com.kareem.secondbrain.ai.api

data class AudioAsset(val id: String, val path: String, val mimeType: String)
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)
data class Transcript(val text: String, val language: String?, val segments: List<TranscriptSegment>, val modelSignature: String)
data class ImageInput(val path: String, val mimeType: String)
data class OcrResult(val text: String, val engineSignature: String)

data class Evidence(
    val evidenceId: String,
    val memoryId: String,
    val chunkId: String? = null,
    val source: String,
    val occurredAtMs: Long,
    val text: String,
)

data class AiAnswerRequest(val question: String, val evidence: List<Evidence>)
data class AiClaim(val text: String, val evidenceIds: List<String>)
data class Citation(val memoryId: String)
data class AiAnswerResponse(
    val claims: List<AiClaim>,
    val insufficientEvidence: Boolean,
)
data class SummaryRequest(val evidence: List<Evidence>)
data class SummaryResponse(val summary: String, val citations: List<Citation>)

/**
 * Query planning is intentionally separate from answering.
 * The model may suggest semantic search variants and soft source/type hints, but it cannot
 * manufacture evidence or impose hard database constraints that were not explicit in the question.
 */
data class AiQueryPlanRequest(
    val question: String,
    val nowEpochMs: Long,
    val zoneId: String,
)

data class AiQueryPlanResponse(
    val semanticQueries: List<String> = emptyList(),
    val softKindHints: List<String> = emptyList(),
    val relationHints: List<String> = emptyList(),
)

class AiProviderUnavailableException(message: String) : IllegalStateException(message)

interface Transcriber { suspend fun transcribe(asset: AudioAsset): Transcript }
interface OcrEngine { suspend fun recognize(image: ImageInput): OcrResult }
interface Embedder {
    val signature: String
    suspend fun embed(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(text: String): FloatArray = embed(listOf(text)).single()
    suspend fun embedDocuments(texts: List<String>): List<FloatArray> = embed(texts)
}
interface AiProvider {
    /** Optional question-only planning pass. Default keeps existing providers source compatible. */
    suspend fun planQuery(request: AiQueryPlanRequest): AiQueryPlanResponse? = null
    suspend fun answer(request: AiAnswerRequest): AiAnswerResponse
    suspend fun summarize(request: SummaryRequest): SummaryResponse
}
