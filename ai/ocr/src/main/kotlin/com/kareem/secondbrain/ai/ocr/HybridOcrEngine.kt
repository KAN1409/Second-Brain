package com.kareem.secondbrain.ai.ocr

import com.kareem.secondbrain.ai.api.ImageInput
import com.kareem.secondbrain.ai.api.OcrEngine
import com.kareem.secondbrain.ai.api.OcrResult

/** ML Kit is primary for Latin screenshots; installed Tesseract ara(+eng) wins for Arabic text. */
class HybridOcrEngine(
    private val mlKit: MlKitOcrEngine,
    private val tesseract: TesseractArabicOcrEngine,
) : OcrEngine {
    override suspend fun recognize(image: ImageInput): OcrResult {
        val ml = runCatching { mlKit.recognize(image) }.getOrElse { OcrResult("", "mlkit-failed") }
        if (!tesseract.isInstalled) return ml
        val tess = runCatching { tesseract.recognize(image) }.getOrElse { return ml }
        val hasArabic = tess.text.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F }
        return when {
            hasArabic && tess.text.isNotBlank() -> tess
            ml.text.isNotBlank() -> ml
            else -> tess
        }
    }
}
