package com.kareem.secondbrain.app.enrichment

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kareem.secondbrain.ai.api.ImageInput
import com.kareem.secondbrain.ai.api.OcrEngine
import com.kareem.secondbrain.capture.android.intelligence.RelayIntelligenceV3
import com.kareem.secondbrain.capture.android.intelligence.observeGenericEvidence
import com.kareem.secondbrain.core.common.TextFingerprint
import com.kareem.secondbrain.core.database.EnrichmentDao
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.MemorySearchRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONObject

@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val assets: AssetRepository,
    private val enrichment: EnrichmentDao,
    private val ocr: OcrEngine,
    private val search: MemorySearchRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(WorkManagerEnrichmentScheduler.KEY_EVENT_ID) ?: return Result.failure()
        val assetId = inputData.getString(WorkManagerEnrichmentScheduler.KEY_ASSET_ID) ?: return Result.failure()
        return try {
            enrichment.markProcessing(eventId)
            val asset = assets.get(assetId) ?: error("Image asset not found: $assetId")
            val path = assets.resolveAbsolutePath(assetId) ?: error("Image asset path unavailable")
            val result = ocr.recognize(ImageInput(path, asset.mimeType))
            val text = result.text.trim()
            val normalized = TextFingerprint.normalize(text)
            val metadata = JSONObject().put("engine", result.engineSignature).toString()
            enrichment.markReady(
                eventId,
                text,
                normalized,
                TextFingerprint.sha256(normalized.ifBlank { "ocr-empty:$assetId" }),
                metadata,
            )
            enrichment.updateMemoryBody(eventId, text.ifBlank { "No text detected" }, System.currentTimeMillis())
            enrichment.getMemoryByEventId(eventId)?.id?.let { memoryId -> search.index(memoryId) }
            runCatching {
                RelayIntelligenceV3.forContext(applicationContext).observeGenericEvidence(
                    kind = "OCR",
                    sourcePackage = applicationContext.packageName,
                    text = text.takeIf(String::isNotBlank),
                    occurredAtEpochMs = System.currentTimeMillis(),
                    provenance = "Local OCR enrichment of user-captured image asset",
                    metadata = JSONObject().apply {
                        put("event_id", eventId)
                        put("asset_id", assetId)
                        put("engine", result.engineSignature)
                        put("mime_type", asset.mimeType)
                    },
                )
            }
            Result.success()
        } catch (t: Throwable) {
            enrichment.markFailed(eventId, failureMetadata(t))
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun failureMetadata(t: Throwable): String = JSONObject()
        .put("stage", "ocr")
        .put("error", t.message ?: t::class.java.simpleName)
        .toString()
}
