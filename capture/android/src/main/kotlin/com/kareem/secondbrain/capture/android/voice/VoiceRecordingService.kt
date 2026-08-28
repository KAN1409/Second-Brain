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
    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null
    private var startedAt: Instant? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stopAndPersist() }
            ACTION_START, null -> startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recording) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            stopSelf()
            return
        }
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            max(minBuffer, SAMPLE_RATE),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            stopSelf()
            return
        }

        val file = File.createTempFile("voice-", ".wav", cacheDir)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(WAV_HEADER_BYTES))
        }
        outputFile = file
        startedAt = Instant.now()
        recorder = audioRecord
        recording = true
        audioRecord.startRecording()
        recordingJob = scope.launch { writePcm(audioRecord, file, max(minBuffer, SAMPLE_RATE)) }
    }

    private fun writePcm(audioRecord: AudioRecord, file: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(WAV_HEADER_BYTES.toLong())
            while (recording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) raf.write(buffer, 0, read)
            }
        }
    }

    private suspend fun stopAndPersist() {
        if (!recording) {
            stopSelf()
            return
        }
        recording = false
        runCatching { recorder?.stop() }
        recordingJob?.join()
        recorder?.release()
        recorder = null

        val file = outputFile
        val occurredAt = startedAt ?: Instant.now()
        outputFile = null
        startedAt = null
        if (file == null || !file.isFile || file.length() <= WAV_HEADER_BYTES) {
            file?.delete()
            stopSelf()
            return
        }

        patchWavHeader(file)
        val pcmBytes = file.length() - WAV_HEADER_BYTES
        val durationMs = (pcmBytes * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        try {
            val asset = assets.importFile(
                absolutePath = file.absolutePath,
                mimeType = "audio/wav",
                suggestedName = "voice-${occurredAt.toEpochMilli()}.wav",
                durationMs = durationMs,
                moveSource = true,
            )
            val result = captureRepository.ingest(CaptureCommand.Voice(occurredAt = occurredAt, assetId = asset.id))
            if (result is CaptureResult.Stored) {
                enrichmentScheduler.enqueueTranscription(result.eventId, asset.id)
            }
        } finally {
            file.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun patchWavHeader(file: File) {
        val dataSize = file.length() - WAV_HEADER_BYTES
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeLeInt(raf, (36L + dataSize).toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeLeInt(raf, 16)
            writeLeShort(raf, 1)
            writeLeShort(raf, 1)
            writeLeInt(raf, SAMPLE_RATE)
            writeLeInt(raf, SAMPLE_RATE * BYTES_PER_SAMPLE)
            writeLeShort(raf, BYTES_PER_SAMPLE)
            writeLeShort(raf, 16)
            raf.writeBytes("data")
            writeLeInt(raf, dataSize.toInt())
        }
    }

    private fun writeLeInt(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value ushr 8) and 0xff)
        raf.write((value ushr 16) and 0xff)
        raf.write((value ushr 24) and 0xff)
    }

    private fun writeLeShort(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value ushr 8) and 0xff)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice memory recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): android.app.Notification {
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
            .setContentText("Tap Stop when finished")
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    override fun onDestroy() {
        recording = false
        runCatching { recorder?.stop() }
        recorder?.release()
        outputFile?.delete()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.kareem.secondbrain.action.START_VOICE_MEMORY"
        const val ACTION_STOP = "com.kareem.secondbrain.action.STOP_VOICE_MEMORY"
        private const val CHANNEL_ID = "voice_memory_recording"
        private const val NOTIFICATION_ID = 4102
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_BYTES = 44
    }
}
