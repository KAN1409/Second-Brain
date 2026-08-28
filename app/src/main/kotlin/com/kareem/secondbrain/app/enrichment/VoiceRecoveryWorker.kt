package com.kareem.secondbrain.app.enrichment

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kareem.secondbrain.capture.android.voice.PendingVoiceFile
import com.kareem.secondbrain.core.database.CaptureEventDao
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.SourceType
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import com.kareem.secondbrain.domain.EnrichmentScheduler
import com.kareem.secondbrain.domain.IgnoreReason
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class VoiceRecoveryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val assets: AssetRepository,
    private val captureRepository: CaptureRepository,
    private val captureEvents: CaptureEventDao,
    private val enrichmentScheduler: EnrichmentScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (captureRepository.observeCaptureState().first().mode != CaptureMode.RUNNING) return Result.retry()

        var needsRetry = false
        for (file in PendingVoiceFile.list(applicationContext)) {
            if (!PendingVoiceFile.hasAudio(file)) {
                file.delete()
                continue
            }
            try {
                PendingVoiceFile.patchHeader(file)
                val occurredAt = PendingVoiceFile.occurredAt(file)
                val asset = assets.importFile(
                    absolutePath = file.absolutePath,
                    mimeType = "audio/wav",
                    suggestedName = file.name,
                    durationMs = PendingVoiceFile.durationMs(file),
                    moveSource = false,
                )
                val existing = captureEvents.latestBySourceAndAssetId(SourceType.VOICE.name, asset.id)
                if (existing != null) {
                    enrichmentScheduler.enqueueTranscription(existing.id, asset.id)
                    file.delete()
                    continue
                }

                when (val result = captureRepository.ingest(CaptureCommand.Voice(occurredAt, asset.id))) {
                    is CaptureResult.Stored -> {
                        enrichmentScheduler.enqueueTranscription(result.eventId, asset.id)
                        file.delete()
                    }
                    is CaptureResult.Ignored -> {
                        val raced = captureEvents.latestBySourceAndAssetId(SourceType.VOICE.name, asset.id)
                        if (result.reason == IgnoreReason.EXACT_DUPLICATE && raced != null) {
                            enrichmentScheduler.enqueueTranscription(raced.id, asset.id)
                            file.delete()
                        } else {
                            needsRetry = true
                        }
                    }
                    is CaptureResult.Failed -> needsRetry = true
                }
            } catch (_: Throwable) {
                needsRetry = true
            }
        }
        return if (needsRetry) Result.retry() else Result.success()
    }
}

object VoiceRecoveryScheduler {
    private const val UNIQUE_WORK = "recover-pending-voice"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<VoiceRecoveryWorker>().build(),
        )
    }
}
