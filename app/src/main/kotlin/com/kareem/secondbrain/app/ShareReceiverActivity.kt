package com.kareem.secondbrain.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kareem.secondbrain.capture.android.connector.CortexConnectorClient
import com.kareem.secondbrain.capture.android.notification.RelayEvidenceGatewayV1
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import com.kareem.secondbrain.domain.EnrichmentScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var enrichmentScheduler: EnrichmentScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            runCatching { consumeShare(intent) }
            finish()
        }
    }

    private suspend fun consumeShare(intent: Intent) {
        val now = Instant.now()
        val shareId = UUID.randomUUID().toString()
        val sourcePackage = referrer?.host?.takeIf { it.isNotBlank() && it != packageName }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotBlank() }
        val handoffAttachments = mutableListOf<RelayEvidenceGatewayV1.HandoffAttachment>()
        val assetIds = mutableListOf<String>()

        streamUris(intent).distinct().forEach { uri ->
            val mime = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            val name = displayName(uri)
            val asset = assets.importContentUri(uri.toString(), mimeType = mime, suggestedName = name)
            assetIds += asset.id
            handoffAttachments += RelayEvidenceGatewayV1.HandoffAttachment(
                attachmentId = "attachment_${asset.sha256.take(24)}",
                kind = RelayEvidenceGatewayV1.inferAttachmentKind(asset.mimeType, name, text),
                mimeType = asset.mimeType,
                displayName = name,
                sizeBytes = asset.sizeBytes,
                sha256 = asset.sha256,
                contentAvailable = true,
                storageRef = "asset:${asset.id}",
                origin = "ANDROID_SHARE_CONTENT_URI",
                originalUriProvenance = RelayEvidenceGatewayV1.uriProvenance(uri),
            )

            // Keep existing local indexing behavior. Audio/voice files are stored as files only;
            // Relay does not transcribe them or request microphone access.
            if (mime.startsWith("image/")) {
                val result = captureRepository.ingest(CaptureCommand.Image(now, assetId = asset.id, userSaved = true))
                if (result is CaptureResult.Stored) enrichmentScheduler.enqueueOcr(result.eventId, asset.id)
            } else {
                captureRepository.ingest(CaptureCommand.File(now, assetId = asset.id, displayName = name))
            }
        }

        if (text == null && handoffAttachments.isEmpty()) return

        val summary = text ?: buildString {
            append("Shared ").append(handoffAttachments.size).append(" attachment")
            if (handoffAttachments.size != 1) append('s')
            val kinds = handoffAttachments.map { it.kind }.distinct()
            if (kinds.isNotEmpty()) append(": ").append(kinds.joinToString(", "))
        }

        val metadata = RelayEvidenceGatewayV1.buildShareMetadata(
            shareId = shareId,
            sourcePackage = sourcePackage,
            observedAtEpochMs = now.toEpochMilli(),
            action = intent.action ?: "ANDROID_SHARE",
            text = text,
            attachments = handoffAttachments,
            referrerProvenance = referrer?.let(RelayEvidenceGatewayV1::uriProvenance),
        )

        val result = captureRepository.ingest(
            CaptureCommand.Share(
                occurredAt = now,
                packageName = sourcePackage,
                text = summary,
                assetIds = assetIds,
            ),
        )
        if (result !is CaptureResult.Stored) return

        // Local Bus V1 remains notification-shaped for compatibility. The metadata carries the
        // canonical ANDROID_SHARE source, and CORTEX_SIGNAL_V2 exposes that canonical envelope.
        CortexConnectorClient.enqueueNotification(
            applicationContext,
            CaptureCommand.Notification(
                occurredAt = now,
                packageName = sourcePackage ?: "android.share",
                notificationKey = "relay-share:$shareId",
                title = "Shared content",
                body = summary,
                expandedText = null,
                conversationTitle = null,
                messages = emptyList(),
                metadataJson = metadata.toString(),
            ),
            result.eventId,
        )
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> {
        val extras = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> emptyList()
        }
        val clipped = buildList {
            val clip = intent.clipData ?: return@buildList
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
        return extras + clipped
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getString(0)
    }
}