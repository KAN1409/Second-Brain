package com.kareem.secondbrain.capture.android.notification

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

enum class NotificationLifecycleState { POSTED, UPDATED, REMOVED }

data class NotificationLifecycleDecision(
    val notificationIdentity: String,
    val state: NotificationLifecycleState,
    val generation: Int,
    val sequence: Int,
    val instanceStartedAtEpochMs: Long,
    val visibleFingerprint: String,
    val stableChurnFingerprint: String,
    val newMessageFingerprints: Set<String>,
    val unchanged: Boolean,
    val stableChurnOnly: Boolean,
    val isNewInstance: Boolean,
)

private data class LifecycleSnapshot(
    val notificationIdentity: String,
    val state: NotificationLifecycleState,
    val generation: Int,
    val sequence: Int,
    val instanceStartedAtEpochMs: Long,
    val observedAtEpochMs: Long,
    val visibleFingerprint: String,
    val stableChurnFingerprint: String,
    val messageFingerprints: List<String>,
)

/** Durable per-notification lifecycle state. It is operational state, not personal memory. */
class DurableNotificationLifecycleStore(private val directory: File) {
    companion object {
        private const val MAGIC = 0x43524C31 // CRL1
        private const val VERSION = 1
        private const val SUFFIX = ".lifecycle"
    }

    @Synchronized
    fun observePosted(
        notificationIdentity: String,
        visibleFingerprint: String,
        stableChurnFingerprint: String,
        messageFingerprints: List<String>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): NotificationLifecycleDecision {
        ensureDirectory()
        val previous = readIfPresent(notificationIdentity)
        val newInstance = previous == null || previous.state == NotificationLifecycleState.REMOVED
        val generation = when {
            previous == null -> 1
            newInstance -> previous.generation + 1
            else -> previous.generation
        }
        val sequence = if (newInstance) 0 else previous!!.sequence + 1
        val state = if (newInstance) NotificationLifecycleState.POSTED else NotificationLifecycleState.UPDATED
        val previousMessages = if (newInstance) emptySet() else previous!!.messageFingerprints.toSet()
        val newMessages = messageFingerprints.filterNot(previousMessages::contains).toSet()
        val unchanged = !newInstance &&
            previous!!.visibleFingerprint == visibleFingerprint &&
            previous.messageFingerprints == messageFingerprints
        val stableChurnOnly = !newInstance && !unchanged && newMessages.isEmpty() &&
            previous!!.stableChurnFingerprint == stableChurnFingerprint
        val startedAt = if (newInstance) nowEpochMs else previous!!.instanceStartedAtEpochMs
        write(
            LifecycleSnapshot(
                notificationIdentity = notificationIdentity,
                state = state,
                generation = generation,
                sequence = sequence,
                instanceStartedAtEpochMs = startedAt,
                observedAtEpochMs = nowEpochMs,
                visibleFingerprint = visibleFingerprint,
                stableChurnFingerprint = stableChurnFingerprint,
                messageFingerprints = messageFingerprints,
            ),
        )
        return NotificationLifecycleDecision(
            notificationIdentity = notificationIdentity,
            state = state,
            generation = generation,
            sequence = sequence,
            instanceStartedAtEpochMs = startedAt,
            visibleFingerprint = visibleFingerprint,
            stableChurnFingerprint = stableChurnFingerprint,
            newMessageFingerprints = newMessages,
            unchanged = unchanged,
            stableChurnOnly = stableChurnOnly,
            isNewInstance = newInstance,
        )
    }

    @Synchronized
    fun markRemoved(notificationIdentity: String, nowEpochMs: Long = System.currentTimeMillis()): NotificationLifecycleDecision {
        ensureDirectory()
        val previous = readIfPresent(notificationIdentity)
        val generation = previous?.generation ?: 1
        val sequence = if (previous == null || previous.state == NotificationLifecycleState.REMOVED) {
            previous?.sequence ?: 0
        } else {
            previous.sequence + 1
        }
        val startedAt = previous?.instanceStartedAtEpochMs ?: nowEpochMs
        val snapshot = LifecycleSnapshot(
            notificationIdentity = notificationIdentity,
            state = NotificationLifecycleState.REMOVED,
            generation = generation,
            sequence = sequence,
            instanceStartedAtEpochMs = startedAt,
            observedAtEpochMs = nowEpochMs,
            visibleFingerprint = previous?.visibleFingerprint.orEmpty(),
            stableChurnFingerprint = previous?.stableChurnFingerprint.orEmpty(),
            messageFingerprints = previous?.messageFingerprints.orEmpty(),
        )
        write(snapshot)
        return NotificationLifecycleDecision(
            notificationIdentity = notificationIdentity,
            state = NotificationLifecycleState.REMOVED,
            generation = generation,
            sequence = sequence,
            instanceStartedAtEpochMs = startedAt,
            visibleFingerprint = snapshot.visibleFingerprint,
            stableChurnFingerprint = snapshot.stableChurnFingerprint,
            newMessageFingerprints = emptySet(),
            unchanged = previous?.state == NotificationLifecycleState.REMOVED,
            stableChurnOnly = false,
            isNewInstance = false,
        )
    }

    @Synchronized
    fun pruneOlderThan(cutoffEpochMs: Long) {
        ensureDirectory()
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .forEach { file ->
                val snapshot = runCatching { read(file) }.getOrNull() ?: return@forEach
                if (snapshot.observedAtEpochMs < cutoffEpochMs) file.delete()
            }
    }

    private fun readIfPresent(notificationIdentity: String): LifecycleSnapshot? {
        val file = fileFor(notificationIdentity)
        if (!file.exists()) return null
        return runCatching { read(file) }.getOrNull()
    }

    private fun read(file: File): LifecycleSnapshot = DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
        check(input.readInt() == MAGIC) { "Unexpected lifecycle magic" }
        check(input.readInt() == VERSION) { "Unsupported lifecycle version" }
        val identity = input.readUTF()
        val state = NotificationLifecycleState.valueOf(input.readUTF())
        val generation = input.readInt()
        val sequence = input.readInt()
        val startedAt = input.readLong()
        val observedAt = input.readLong()
        val visible = input.readUTF()
        val stable = input.readUTF()
        val count = input.readInt()
        check(count in 0..512) { "Invalid message fingerprint count" }
        val messages = buildList(count) { repeat(count) { add(input.readUTF()) } }
        LifecycleSnapshot(identity, state, generation, sequence, startedAt, observedAt, visible, stable, messages)
    }

    private fun write(snapshot: LifecycleSnapshot) {
        ensureDirectory()
        val target = fileFor(snapshot.notificationIdentity)
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(snapshot.notificationIdentity)
                    output.writeUTF(snapshot.state.name)
                    output.writeInt(snapshot.generation)
                    output.writeInt(snapshot.sequence)
                    output.writeLong(snapshot.instanceStartedAtEpochMs)
                    output.writeLong(snapshot.observedAtEpochMs)
                    output.writeUTF(snapshot.visibleFingerprint)
                    output.writeUTF(snapshot.stableChurnFingerprint)
                    output.writeInt(snapshot.messageFingerprints.size)
                    snapshot.messageFingerprints.take(512).forEach(output::writeUTF)
                    output.flush()
                    stream.fd.sync()
                }
            }
            moveAtomically(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun fileFor(identity: String): File = File(directory, "${sha256(identity)}$SUFFIX")

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) error("Could not create lifecycle directory")
        check(directory.isDirectory) { "Lifecycle path is not a directory" }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
