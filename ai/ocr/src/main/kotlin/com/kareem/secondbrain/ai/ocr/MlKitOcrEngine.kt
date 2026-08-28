package com.kareem.secondbrain.ai.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kareem.secondbrain.ai.api.ImageInput
import com.kareem.secondbrain.ai.api.OcrEngine
import com.kareem.secondbrain.ai.api.OcrResult
import kotlinx.coroutines.tasks.await
import java.io.File

class MlKitOcrEngine(
    private val context: Context,
) : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(image: ImageInput): OcrResult {
        val input = InputImage.fromFilePath(context, Uri.fromFile(File(image.path)))
        val result = recognizer.process(input).await()
        return OcrResult(result.text.trim(), "mlkit-text-latin:16.0.1")
    }
}
