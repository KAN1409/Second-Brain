package com.kareem.secondbrain.capture.android.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import com.kareem.secondbrain.domain.EnrichmentScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class VoiceRecordingService : Service() {
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var enrichmentScheduler: EnrichmentScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var recording = false
    @Volatile private var starting = false
    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null
    private var startedAt: Instant? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stopAndPersist() }
            ACTION_START, null -> requestStartRecording()
        }
        return START_NOT_STICKY
    }

    private fun requestStartRecording() {
        if (recording || starting) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        starting = true
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing voice memory…"))
        scope.launch {
            val running = captureRepository.observeCaptureState().first().mode == CaptureMode.RUNNING
            if (!running) {
                starting = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            startRecordingAfterGate()
        }
    }

    private fun startRecordingAfterGate() {
        if (recording) {
            starting = false
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(PendingVoiceFile.SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            starting = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            PendingVoiceFile.SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            max(minBuffer, PendingVoiceFile.SAMPLE_RATE),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            starting = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val start = Instant.now()
        val file = PendingVoiceFile.create(this, start)
        outputFile = file
        startedAt = start
        recorder = audioRecord
        recording = true
        starting = false
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification("Tap Stop when finished"))
        audioRecord.startRecording()
        recordingJob = scope.launch { writePcm(audioRecord, file, max(minBuffer, PendingVoiceFile.SAMPLE_RATE)) }
    }

    private fun writePcm(audioRecord: AudioRecord, file: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(PendingVoiceFile.WAV_HEADER_BYTES.toLong())
            while (recording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) raf.write(buffer, 0, read)
            }
        }
    }

    private suspend fun stopAndPersist() {
        if (!recording) {
            starting = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recording = false
        runCatching { recorder?.stop() }
        recordingJob?.join()
        recorder?.release()
        recorder = null

        val file = outputFile
        val occurredAt = startedAt ?: file?.let(PendingVoiceFile::occurredAt) ?: Instant.now()
        outputFile = null
        startedAt = null
        if (file == null || !PendingVoiceFile.hasAudio(file)) {
            file?.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        PendingVoiceFile.patchHeader(file)
        try {
            val asset = assets.importFile(
                absolutePath = file.absolutePath,
                mimeType = "audio/wav",
                suggestedName = file.name,
                durationMs = PendingVoiceFile.durationMs(file),
                moveSource = false,
            )
            when (val result = captureRepository.ingest(CaptureCommand.Voice(occurredAt = occurredAt, assetId = asset.id))) {
                is CaptureResult.Stored -> {
                    enrichmentScheduler.enqueueTranscription(result.eventId, asset.id)
                    file.delete()
                }
                else -> Unit // Keep durable pending file for recovery on the next app start.
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice memory recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = Intent(this, VoiceRecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording voice memory")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    override fun onDestroy() {
        recording = false
        starting = false
        runCatching { recorder?.stop() }
        recorder?.release()
        // Never mutate or delete the pending file here. The write job may still be unwinding after
        // AudioRecord.stop(); VoiceRecoveryWorker owns header repair and cleanup on a later app start.
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.kareem.secondbrain.action.START_VOICE_MEMORY"
        const val ACTION_STOP = "com.kareem.secondbrain.action.STOP_VOICE_MEMORY"
        private const val CHANNEL_ID = "voice_memory_recording"
        private const val NOTIFICATION_ID = 4102
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
