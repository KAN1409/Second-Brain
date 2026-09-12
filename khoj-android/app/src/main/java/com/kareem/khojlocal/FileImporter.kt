package com.kareem.khojlocal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class FileImporter(private val context: Context, private val store: LocalBrainStore) {
    data class ImportResult(val imported: Int, val skipped: Int, val errors: List<String>)

    fun importUris(uris: List<Uri>): ImportResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        uris.distinct().forEach { uri ->
            try {
                val name = displayName(uri).ifBlank { "Imported file" }
                val mime = context.contentResolver.getType(uri).orEmpty()
                val copied = copyIntoPrivateStorage(uri, name)
                val extracted = extractText(uri, mime, name)
                val body = if (extracted.isNotBlank()) {
                    extracted
                } else {
                    "Attachment saved locally. Text extraction is not available for this file type yet.\n\nFile: $name\nMIME: ${mime.ifBlank { "unknown" }}"
                }
                val before = store.count()
                store.addMemory(
                    title = name,
                    body = body,
                    source = "file",
                    tags = mime,
                    attachmentPath = copied.absolutePath,
                )
                if (store.count() > before) imported++ else skipped++
            } catch (t: Throwable) {
                errors += "${displayName(uri).ifBlank { uri.lastPathSegment ?: "file" }}: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        return ImportResult(imported, skipped, errors)
    }

    private fun extractText(uri: Uri, mime: String, name: String): String {
        val lowerName = name.lowercase()
        val textual = mime.startsWith("text/") ||
            mime in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml") ||
            lowerName.endsWith(".md") || lowerName.endsWith(".txt") || lowerName.endsWith(".csv") ||
            lowerName.endsWith(".json") || lowerName.endsWith(".xml") || lowerName.endsWith(".html") ||
            lowerName.endsWith(".htm") || lowerName.endsWith(".yaml") || lowerName.endsWith(".yml") ||
            lowerName.endsWith(".log") || lowerName.endsWith(".kt") || lowerName.endsWith(".java") ||
            lowerName.endsWith(".py") || lowerName.endsWith(".js") || lowerName.endsWith(".ts")
        if (!textual) return ""
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val maxBytes = 4 * 1024 * 1024
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.toByteArray().toString(Charsets.UTF_8).replace("\u0000", "").trim()
        }.orEmpty()
    }

    private fun copyIntoPrivateStorage(uri: Uri, name: String): File {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(100).ifBlank { "attachment" }
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        val target = File(imports, "${System.currentTimeMillis()}-$digest-$safeName")
        context.contentResolver.openInputStream(uri).use { input ->
            val source = requireNotNull(input) { "Unable to open file" }
            target.outputStream().use { output -> source.copyTo(output, 64 * 1024) }
        }
        return target
    }

    fun displayName(uri: Uri): String {
        var result = ""
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = cursor.getString(index).orEmpty()
            }
        }
        return result.ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
    }
}
