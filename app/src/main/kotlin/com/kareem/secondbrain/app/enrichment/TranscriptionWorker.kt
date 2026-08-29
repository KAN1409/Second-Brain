package com.kareem.secondbrain.app.enrichment

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kareem.secondbrain.ai.api.AudioAsset
import com.kareem.secondbrain.ai.api.Transcriber
import com.kareem.secondbrain.capture.android.intelligence.RelayIntelligenceV3
import com.kareem.secondbrain.capture.android.intelligence.observeGenericEvidence
import com.kareem.secondbrain.core.common.TextFingerprint
import com.kareem.secondbrain.core.database.EnrichmentDao
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.MemorySearchRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONArray
import org.json.JSONObject

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val assets: AssetRepository,
    private val enrichment: EnrichmentDao,
    private val transcriber: Transcriber,
    private val search: MemorySearchRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(WorkManagerEnrichmentScheduler.KEY_EVENT_ID) ?: return Result.failure()
        val assetId = inputData.getString(WorkManagerEnrichmentScheduler.KEY_ASSET_ID) ?: return Result.failure()
        return try {
            enrichment.markProcessing(eventId)
            val asset = assets.get(assetId) ?: error("Voice asset not found: $assetId")
            val path = assets.resolveAbsolutePath(assetId) ?: error("Voice asset path unavailable")
            val transcript = transcriber.transcribe(AudioAsset(assetId, path, asset.mimeType))
            val text = transcript.text.trim()
            require(text.isNotBlank()) { "Whisper returned an empty transcript" }
            val normalized = TextFingerprint.normalize(text)
            val metadata = JSONObject()
                .put("engine", transcript.modelSignature)
                .put("language", transcript.language)
                .put(
                    "segments",
                    JSONArray().apply {
                        transcript.segments.forEach { s ->
                            put(JSONObject().put("startMs", s.startMs).put("endMs", s.endMs).put("text", s.text))
                        }
                    },
                )
                .toString()
            enrichment.markReady(eventId, text, normalized, TextFingerprint.sha256(normalized), metadata)
            enrichment.updateMemoryBody(eventId, text, System.currentTimeMillis())
            enrichment.getMemoryByEventId(eventId)?.id?.let { memoryId -> search.index(memoryId) }
            runCatching {
                RelayIntelligenceV3.forContext(applicationContext).observeGenericEvidence(
                    kind = "TRANSCRIPT",
                    sourcePackage = applicationContext.packageName,
                    text = text,
                    occurredAtEpochMs = System.currentTimeMillis(),
                    provenance = "Local voice transcription enrichment",
                    metadata = JSONObject().apply {
                        put("event_id", eventId)
                        put("asset_id", assetId)
                        put("engine", transcript.modelSignature)
                        put("language", transcript.language ?: JSONObject.NULL)
                        put("segment_count", transcript.segments.size)
                    },
                )
            }
            Result.success()
        } catch (t: Throwable) {
            enrichment.markFailed(eventId, failureMetadata(t))
            if (runAttemptCount < 2 && t !is IllegalArgumentException) Result.retry() else Result.failure()
        }
    }

    private fun failureMetadata(t: Throwable): String = JSONObject()
        .put("stage", "transcription")
        .put("error", t.message ?: t::class.java.simpleName)
        .toString()
}
