package com.kareem.secondbrain.app.enrichment

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kareem.secondbrain.ai.api.ImageInput
import com.kareem.secondbrain.ai.api.OcrEngine
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.time.Instant

@HiltWorker
class TemporaryScreenshotOcrWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ocr: OcrEngine,
    private val captureRepository: CaptureRepository,
    private val policyRepository: CapturePolicyRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val packageName = inputData.getString(WorkManagerEnrichmentScheduler.KEY_PACKAGE_NAME) ?: return Result.failure()
        val occurredAt = inputData.getLong(WorkManagerEnrichmentScheduler.KEY_OCCURRED_AT, -1L).takeIf { it >= 0 }
            ?: return Result.failure()
        val path = inputData.getString(WorkManagerEnrichmentScheduler.KEY_TEMP_PATH) ?: return Result.failure()
        val file = File(path)
        return try {
            if (!file.isFile) return Result.failure()
            if (captureRepository.observeCaptureState().first().mode != CaptureMode.RUNNING) return Result.success()
            val policy = policyRepository.get(packageName)
            if (!policy.accessibility || !policy.ocr) return Result.success()
            val result = ocr.recognize(ImageInput(file.absolutePath, "image/png"))
            if (result.text.isBlank()) return Result.success()
            captureRepository.ingest(
                CaptureCommand.Screen(
                    occurredAt = Instant.ofEpochMilli(occurredAt),
                    packageName = packageName,
                    accessibleText = result.text,
                    metadataJson = JSONObject()
                        .put("source", "accessibility_screenshot_ocr")
                        .put("engine", result.engineSignature)
                        .toString(),
                ),
            )
            Result.success()
        } catch (_: Throwable) {
            Result.failure()
        } finally {
            file.delete()
        }
    }
}
