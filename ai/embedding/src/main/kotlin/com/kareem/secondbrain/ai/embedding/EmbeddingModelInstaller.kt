package com.kareem.secondbrain.ai.embedding

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class EmbeddingModelStatus(
    val installed: Boolean,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
)

class EmbeddingModelInstaller(private val context: Context) {
    private val appContext = context.applicationContext
    private val modelFile: File
        get() = File(appContext.filesDir, EmbeddingGemmaEmbedder.MODEL_RELATIVE_PATH)

    suspend fun status(): EmbeddingModelStatus = withContext(Dispatchers.IO) {
        val file = modelFile
        if (!file.isFile || file.length() <= 0L) return@withContext EmbeddingModelStatus(false)
        EmbeddingModelStatus(
            installed = true,
            sizeBytes = file.length(),
            sha256 = sha256(file),
        )
    }

    suspend fun install(contentUri: String): EmbeddingModelStatus = withContext(Dispatchers.IO) {
        val destination = modelFile
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.part")
        temp.delete()

        try {
            val uri = Uri.parse(contentUri)
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("Unable to open selected model file")
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    source.copyTo(output, bufferSize = 1024 * 1024)
                    output.fd.sync()
                }
            }
            require(temp.length() >= MIN_MODEL_BYTES) {
                "Selected file is too small to be an EmbeddingGemma task model"
            }
            val hash = sha256(temp)
            if (destination.exists() && !destination.delete()) {
                error("Unable to replace existing embedding model")
            }
            if (!temp.renameTo(destination)) {
                temp.inputStream().use { source ->
                    FileOutputStream(destination).use { output ->
                        source.copyTo(output, bufferSize = 1024 * 1024)
                        output.fd.sync()
                    }
                }
                temp.delete()
            }
            require(destination.length() >= MIN_MODEL_BYTES) { "Embedding model copy was incomplete" }
            EmbeddingModelStatus(true, destination.length(), hash)
        } catch (throwable: Throwable) {
            temp.delete()
            throw throwable
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        val destination = modelFile
        if (destination.exists() && !destination.delete()) {
            error("Unable to remove embedding model")
        }
        File(destination.parentFile, "${destination.name}.part").delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MIN_MODEL_BYTES = 10L * 1024L * 1024L
    }
}
