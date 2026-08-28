package com.kareem.secondbrain.ai.whisper

import android.content.Context
import com.kareem.secondbrain.ai.api.AudioAsset
import com.kareem.secondbrain.ai.api.Transcriber
import com.kareem.secondbrain.ai.api.Transcript
import com.kareem.secondbrain.ai.api.TranscriptSegment
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File

/**
 * File-based on-device whisper.cpp adapter.
 *
 * M2 uses the free arm64-v8a whisper.cpp AAR with an unquantized multilingual
 * ggml-base.bin model. The original q5_1 target remains a Phase-9 optimization,
 * where the official whisper.cpp native build will be vendored directly.
 */
class WhisperCppTranscriber(
    private val context: Context,
) : Transcriber {
    override suspend fun transcribe(asset: AudioAsset): Transcript {
        val modelFile = chooseModel()
        require(modelFile.isFile) {
            "Whisper model not installed. Expected ${modelFile.absolutePath}"
        }
        val audioFile = File(asset.path)
        require(audioFile.isFile) { "Audio asset missing: ${audioFile.absolutePath}" }

        val model = Whisper.loadModel(context, modelFile.absolutePath)
        return try {
            val result = Whisper.transcribe(model, audioFile.absolutePath, WhisperConfig())
            Transcript(
                text = result.text.trim(),
                language = null,
                segments = result.segments.map { segment ->
                    TranscriptSegment(
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        text = segment.text.trim(),
                    )
                },
                modelSignature = "whisper.cpp:${modelFile.name}:whisper-android-1.0.0",
            )
        } finally {
            Whisper.releaseModel(model)
        }
    }

    private fun chooseModel(): File {
        val dir = File(context.filesDir, "models/whisper")
        val base = File(dir, "ggml-base.bin")
        if (base.isFile) return base
        return File(dir, "ggml-tiny.bin")
    }
}
