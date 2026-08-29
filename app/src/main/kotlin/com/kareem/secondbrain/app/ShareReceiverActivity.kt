package com.kareem.secondbrain.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kareem.secondbrain.capture.android.intelligence.RelayIntelligenceV3
import com.kareem.secondbrain.capture.android.intelligence.observeGenericEvidence
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import com.kareem.secondbrain.domain.EnrichmentScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
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
        val sourcePackage = groundedSourcePackage(intent)
        val intelligence = RelayIntelligenceV3.forContext(applicationContext)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotBlank() }
        if (text != null) {
            runCatching {
                intelligence.observeGenericEvidence(
                    kind = if (text.startsWith("http://") || text.startsWith("https://")) "LINK" else "SHARE",
                    sourcePackage = sourcePackage,
                    text = text,
                    occurredAtEpochMs = now.toEpochMilli(),
                    provenance = "Android ACTION_SEND text",
                    metadata = JSONObject().apply { put("mime_type", intent.type ?: JSONObject.NULL) },
                )
            }
            if (text.startsWith("http://") || text.startsWith("https://")) {
                captureRepository.ingest(CaptureCommand.Link(now, url = text))
            } else {
                captureRepository.ingest(CaptureCommand.Share(now, packageName = sourcePackage, text = text))
            }
        }

        streamUris(intent).forEach { uri ->
            val mime = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            val name = displayName(uri)
            runCatching {
                intelligence.observeGenericEvidence(
                    kind = if (mime.startsWith("image/")) "IMAGE" else "FILE",
                    sourcePackage = sourcePackage,
                    text = name,
                    occurredAtEpochMs = now.toEpochMilli(),
                    provenance = "Android ACTION_SEND content URI",
                    metadata = JSONObject().apply {
                        put("mime_type", mime)
                        put("display_name", name ?: JSONObject.NULL)
                        put("content_uri_present", true)
                    },
                )
            }
            val asset = assets.importContentUri(uri.toString(), mimeType = mime, suggestedName = name)
            if (mime.startsWith("image/")) {
                val result = captureRepository.ingest(CaptureCommand.Image(now, assetId = asset.id, userSaved = true))
                if (result is CaptureResult.Stored) enrichmentScheduler.enqueueOcr(result.eventId, asset.id)
            } else {
                captureRepository.ingest(CaptureCommand.File(now, assetId = asset.id, displayName = name))
            }
        }
    }

    /** Only use Android-supplied package identity; absence stays unknown rather than guessed. */
    private fun groundedSourcePackage(intent: Intent): String? {
        val referrerPackage = referrer
            ?.takeIf { it.scheme == "android-app" }
            ?.host
            ?.takeIf(String::isNotBlank)
        @Suppress("DEPRECATION")
        val extraReferrer = intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)
            ?.takeIf { it.scheme == "android-app" }
            ?.host
            ?.takeIf(String::isNotBlank)
        return referrerPackage ?: extraReferrer ?: callingPackage?.takeIf(String::isNotBlank)
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else -> emptyList()
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
