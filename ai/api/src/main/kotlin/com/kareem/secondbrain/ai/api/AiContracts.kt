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
    suspend fun answer(request: AiAnswerRequest): AiAnswerResponse
    suspend fun summarize(request: SummaryRequest): SummaryResponse
}
