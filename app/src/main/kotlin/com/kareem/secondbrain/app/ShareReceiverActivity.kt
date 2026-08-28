package com.kareem.secondbrain.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import com.kareem.secondbrain.domain.EnrichmentScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotBlank() }
        if (text != null) {
            if (text.startsWith("http://") || text.startsWith("https://")) {
                captureRepository.ingest(CaptureCommand.Link(now, url = text))
            } else {
                captureRepository.ingest(CaptureCommand.Share(now, packageName = null, text = text))
            }
        }

        streamUris(intent).forEach { uri ->
            val mime = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            val name = displayName(uri)
            val asset = assets.importContentUri(uri.toString(), mimeType = mime, suggestedName = name)
            if (mime.startsWith("image/")) {
                val result = captureRepository.ingest(CaptureCommand.Image(now, assetId = asset.id, userSaved = true))
                if (result is CaptureResult.Stored) enrichmentScheduler.enqueueOcr(result.eventId, asset.id)
            } else {
                captureRepository.ingest(CaptureCommand.File(now, assetId = asset.id, displayName = name))
            }
        }
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
