package com.kareem.secondbrain.app.enrichment

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kareem.secondbrain.domain.EnrichmentScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject

class WorkManagerEnrichmentScheduler @Inject constructor(
    @ApplicationContext context: Context,
) : EnrichmentScheduler {
    private val workManager = WorkManager.getInstance(context)

    override suspend fun enqueueTranscription(eventId: String, assetId: String) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(KEY_EVENT_ID to eventId, KEY_ASSET_ID to assetId))
            .build()
        workManager.enqueueUniqueWork("transcription:$eventId", ExistingWorkPolicy.KEEP, request)
    }

    override suspend fun enqueueOcr(eventId: String, assetId: String) {
        val request = OneTimeWorkRequestBuilder<OcrWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf(KEY_EVENT_ID to eventId, KEY_ASSET_ID to assetId))
            .build()
        workManager.enqueueUniqueWork("ocr:$eventId", ExistingWorkPolicy.KEEP, request)
    }

    override suspend fun enqueueTemporaryScreenshotOcr(
        packageName: String,
        occurredAt: Instant,
        absolutePath: String,
    ) {
        val request = OneTimeWorkRequestBuilder<TemporaryScreenshotOcrWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInputData(
                workDataOf(
                    KEY_PACKAGE_NAME to packageName,
                    KEY_OCCURRED_AT to occurredAt.toEpochMilli(),
                    KEY_TEMP_PATH to absolutePath,
                ),
            )
            .build()
        workManager.enqueueUniqueWork(
            "screenshot-ocr:$packageName:${occurredAt.toEpochMilli()}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val KEY_EVENT_ID = "event_id"
        const val KEY_ASSET_ID = "asset_id"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_OCCURRED_AT = "occurred_at"
        const val KEY_TEMP_PATH = "temp_path"
    }
}
