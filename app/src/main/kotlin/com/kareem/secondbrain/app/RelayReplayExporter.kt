package com.kareem.secondbrain.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kareem.secondbrain.capture.android.connector.RelayForensicBuffer
import com.kareem.secondbrain.capture.android.connector.RelayReplayEngine
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object RelayReplayExporter {
    fun replayLatestAndShare(context: Context): Boolean {
        val buffer = RelayForensicBuffer.forContext(context.applicationContext)
        val record = buffer.recent(1).firstOrNull() ?: return false
        val result = RelayReplayEngine.replay(record)
        val directory = File(context.cacheDir, "relay-diagnostics").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val file = File(directory, "CortexRelayReplay_$stamp.json")
        val report = JSONObject().apply {
            put("schema", RelayReplayEngine.SCHEMA)
            put("generated_at", Instant.now().toString())
            put("forensic_schema", RelayForensicBuffer.SCHEMA)
            put("result", result.toJson())
            put("note", "Replay is local-only: no capture row or Cortex delivery was created.")
        }
        file.writeText(report.toString(2), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cortex Relay local replay")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share Cortex Relay replay").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}