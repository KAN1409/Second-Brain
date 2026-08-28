package com.kareem.secondbrain.domain

import java.time.Instant

data class StoredAsset(
    val id: String,
    val relativePath: String,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val createdAt: Instant,
    val expiresAt: Instant?,
)

/**
 * Owns files in app-private storage. Imports are content-addressed by SHA-256,
 * so importing the same bytes twice resolves to the same Asset row/file.
 */
interface AssetRepository {
    suspend fun importContentUri(
        uri: String,
        mimeType: String? = null,
        suggestedName: String? = null,
        expiresAt: Instant? = null,
    ): StoredAsset

    suspend fun importFile(
        absolutePath: String,
        mimeType: String,
        suggestedName: String? = null,
        durationMs: Long? = null,
        expiresAt: Instant? = null,
        moveSource: Boolean = false,
    ): StoredAsset

    suspend fun get(id: String): StoredAsset?
    suspend fun resolveAbsolutePath(id: String): String?
}

/** Durable WorkManager-backed enrichment queue. Capture itself never waits for this work. */
interface EnrichmentScheduler {
    suspend fun enqueueTranscription(eventId: String, assetId: String)
    suspend fun enqueueOcr(eventId: String, assetId: String)

    /** For Accessibility screenshot OCR: the temporary image must be deleted after OCR. */
    suspend fun enqueueTemporaryScreenshotOcr(
        packageName: String,
        occurredAt: Instant,
        absolutePath: String,
    )
}
