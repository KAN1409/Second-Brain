package com.kareem.secondbrain.capture.android.voice

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/** Durable staging format for voice notes that have not yet been committed as a Memory. */
object PendingVoiceFile {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2
    const val WAV_HEADER_BYTES = 44
    private const val PREFIX = "voice-"
    private const val SUFFIX = ".wav"
    private const val DIRECTORY = "voice-pending"

    fun create(context: Context, startedAt: Instant): File {
        val directory = directory(context).apply { mkdirs() }
        val file = File(directory, "$PREFIX${startedAt.toEpochMilli()}$SUFFIX")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(WAV_HEADER_BYTES))
        }
        return file
    }

    fun list(context: Context): List<File> =
        directory(context).listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            .sortedBy { it.lastModified() }

    fun occurredAt(file: File): Instant =
        file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull()
            ?.let(Instant::ofEpochMilli)
            ?: Instant.ofEpochMilli(file.lastModified().coerceAtLeast(0L))

    fun hasAudio(file: File): Boolean = file.isFile && file.length() > WAV_HEADER_BYTES

    fun durationMs(file: File): Long {
        val pcmBytes = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
        return (pcmBytes * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
    }

    fun patchHeader(file: File) {
        require(hasAudio(file)) { "Pending voice file has no PCM data" }
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

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

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
}
