package com.kareem.secondbrain.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.kareem.secondbrain.core.database.AssetDao
import com.kareem.secondbrain.core.database.AssetEntity
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.StoredAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class RoomAssetRepository(
    private val context: Context,
    private val assets: AssetDao,
    private val clock: Clock = Clock.systemUTC(),
) : AssetRepository {
    private val assetRoot: File get() = File(context.filesDir, "assets")

    override suspend fun importContentUri(
        uri: String,
        mimeType: String?,
        suggestedName: String?,
        expiresAt: Instant?,
    ): StoredAsset = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val resolvedMime = mimeType ?: context.contentResolver.getType(parsed) ?: "application/octet-stream"
        val temp = File.createTempFile("import-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(parsed).use { input ->
                requireNotNull(input) { "Unable to open content URI" }
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            importFileInternal(temp, resolvedMime, suggestedName, null, expiresAt, moveSource = true)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override suspend fun importFile(
        absolutePath: String,
        mimeType: String,
        suggestedName: String?,
        durationMs: Long?,
        expiresAt: Instant?,
        moveSource: Boolean,
    ): StoredAsset = withContext(Dispatchers.IO) {
        importFileInternal(File(absolutePath), mimeType, suggestedName, durationMs, expiresAt, moveSource)
    }

    override suspend fun get(id: String): StoredAsset? = withContext(Dispatchers.IO) {
        assets.get(id)?.toModel()
    }

    override suspend fun resolveAbsolutePath(id: String): String? = withContext(Dispatchers.IO) {
        assets.get(id)?.let { File(context.filesDir, it.relative_path).absolutePath }
    }

    private suspend fun importFileInternal(
        source: File,
        mimeType: String,
        suggestedName: String?,
        durationMs: Long?,
        expiresAt: Instant?,
        moveSource: Boolean,
    ): StoredAsset {
        require(source.isFile) { "Asset source does not exist: ${source.absolutePath}" }
        val sha = sha256(source)
        assets.findBySha256(sha)?.let { return it.toModel() }

        val extension = extensionFor(mimeType, suggestedName)
        val shard = sha.take(2)
        val relative = "assets/$shard/$sha${extension?.let { ".$it" }.orEmpty()}"
        val destination = File(context.filesDir, relative)
        destination.parentFile?.mkdirs()
        if (!destination.exists()) {
            if (moveSource && source.renameTo(destination)) {
                // moved atomically when source/destination share a filesystem
            } else {
                source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            }
        }

        val now = Instant.ofEpochMilli(clock.millis())
        val entity = AssetEntity(
            id = UUID.randomUUID().toString(),
            relative_path = relative,
            mime_type = mimeType,
            sha256 = sha,
            size_bytes = destination.length(),
            width = null,
            height = null,
            duration_ms = durationMs,
            created_at = now.toEpochMilli(),
            expires_at = (expiresAt ?: now.plus(90, ChronoUnit.DAYS)).toEpochMilli(),
        )
        return try {
            assets.insert(entity)
            entity.toModel()
        } catch (t: Throwable) {
            assets.findBySha256(sha)?.toModel() ?: throw t
        }
    }

    private fun extensionFor(mimeType: String, suggestedName: String?): String? {
        val fromName = suggestedName?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
        return fromName ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun AssetEntity.toModel() = StoredAsset(
        id = id,
        relativePath = relative_path,
        mimeType = mime_type,
        sha256 = sha256,
        sizeBytes = size_bytes,
        width = width,
        height = height,
        durationMs = duration_ms,
        createdAt = Instant.ofEpochMilli(created_at),
        expiresAt = expires_at?.let(Instant::ofEpochMilli),
    )
}
