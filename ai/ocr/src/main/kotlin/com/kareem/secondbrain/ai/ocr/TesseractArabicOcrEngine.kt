package com.kareem.secondbrain.ai.ocr

import android.content.Context
import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI
import com.kareem.secondbrain.ai.api.ImageInput
import com.kareem.secondbrain.ai.api.OcrEngine
import com.kareem.secondbrain.ai.api.OcrResult
import java.io.File

class TesseractArabicOcrEngine(
    private val context: Context,
) : OcrEngine {
    val isInstalled: Boolean
        get() = File(context.filesDir, "models/tesseract/tessdata/ara.traineddata").isFile

    override suspend fun recognize(image: ImageInput): OcrResult {
        val modelRoot = File(context.filesDir, "models/tesseract")
        val tessdata = File(modelRoot, "tessdata")
        require(File(tessdata, "ara.traineddata").isFile) { "Arabic Tesseract model is not installed" }
        val languages = if (File(tessdata, "eng.traineddata").isFile) "ara+eng" else "ara"
        val bitmap = requireNotNull(BitmapFactory.decodeFile(image.path)) { "Unable to decode image" }
        val api = TessBaseAPI()
        try {
            check(api.init(modelRoot.absolutePath, languages)) { "Tesseract initialization failed" }
            api.setImage(bitmap)
            return OcrResult(api.getUTF8Text().orEmpty().trim(), "tesseract-5.5.1:$languages")
        } finally {
            api.recycle()
            bitmap.recycle()
        }
    }
}
