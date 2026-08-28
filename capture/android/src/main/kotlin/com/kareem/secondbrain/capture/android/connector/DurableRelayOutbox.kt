package com.kareem.secondbrain.capture.android.connector

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class DurableOutboxEntry(
    val eventId: String,
    val raw: String,
    val enqueuedAtEpochMs: Long,
)

internal data class DurableOutboxLoadResult(
    val entries: List<DurableOutboxEntry>,
    val corruptFiles: Int,
)

/**
 * Small crash-safe disk outbox for Relay -> Cortex delivery copies.
 *
 * One logical event is one file. A successful Cortex ACK removes that file. If Relay dies after
 * Cortex accepts an event but before deletion completes, the same event_id is intentionally replayed
 * after restart and Cortex's existing V1 idempotency can return DUPLICATE_ACCEPTED.
 */
internal class DurableRelayOutbox(private val directory: File) {
    companion object {
        private const val MAGIC = 0x43524F31 // CRO1
        private const val VERSION = 1
        private const val ENTRY_SUFFIX = ".entry"
    }

    @Synchronized
    fun put(eventId: String, raw: String, enqueuedAtEpochMs: Long = System.currentTimeMillis()): DurableOutboxEntry {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        ensureDirectory()
        val target = fileFor(eventId)
        if (target.exists()) {
            runCatching { readEntry(target) }.getOrNull()?.let { existing ->
                if (existing.eventId == eventId) return existing
            }
            preserveCorrupt(target)
        }

        val entry = DurableOutboxEntry(eventId = eventId, raw = raw, enqueuedAtEpochMs = enqueuedAtEpochMs)
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    out.writeLong(entry.enqueuedAtEpochMs)
                    out.writeUTF(entry.eventId)
                    val rawBytes = entry.raw.toByteArray(Charsets.UTF_8)
                    out.writeInt(rawBytes.size)
                    out.write(rawBytes)
                    out.flush()
                    stream.fd.sync()
                }
            }
            moveAtomically(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
        return entry
    }

    @Synchronized
    fun remove(eventId: String) {
        ensureDirectory()
        val target = fileFor(eventId)
        if (target.exists() && !target.delete()) {
            error("Could not remove durable outbox entry for $eventId")
        }
    }

    @Synchronized
    fun loadAll(): DurableOutboxLoadResult {
        ensureDirectory()
        var corrupt = 0
        val entries = directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(ENTRY_SUFFIX) }
            .mapNotNull { file ->
                runCatching { readEntry(file) }
                    .onFailure { corrupt += 1 }
                    .getOrNull()
            }
            .sortedWith(compareBy<DurableOutboxEntry> { it.enqueuedAtEpochMs }.thenBy { it.eventId })
            .toList()
        return DurableOutboxLoadResult(entries = entries, corruptFiles = corrupt)
    }

    @Synchronized
    fun count(): Int {
        ensureDirectory()
        return directory.listFiles().orEmpty().count { it.isFile && it.name.endsWith(ENTRY_SUFFIX) }
    }

    private fun readEntry(file: File): DurableOutboxEntry = DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
        check(input.readInt() == MAGIC) { "Unexpected outbox magic" }
        check(input.readInt() == VERSION) { "Unsupported outbox version" }
        val enqueuedAt = input.readLong()
        val eventId = input.readUTF()
        val rawSize = input.readInt()
        check(rawSize in 0..(256 * 1024)) { "Invalid outbox payload size: $rawSize" }
        val rawBytes = ByteArray(rawSize)
        input.readFully(rawBytes)
        DurableOutboxEntry(eventId, rawBytes.toString(Charsets.UTF_8), enqueuedAt)
    }

    private fun fileFor(eventId: String): File = File(directory, "${sha256(eventId)}$ENTRY_SUFFIX")

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) error("Could not create durable outbox directory")
        check(directory.isDirectory) { "Durable outbox path is not a directory" }
    }

    private fun preserveCorrupt(file: File) {
        val preserved = File(directory, "${file.name}.corrupt.${System.currentTimeMillis()}")
        if (!file.renameTo(preserved)) error("Could not preserve corrupt durable outbox entry")
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
