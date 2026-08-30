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

/**
 * Operational continuity for a grounded Android conversation identity.
 *
 * No message text, person profile, personal importance or relationship inference is stored here.
 * The store only remembers the already-hashed conversation identity and observation timing/count.
 */
class DurableConversationContinuityStore(private val directory: File) {
    companion object {
        private const val MAGIC = 0x43524332 // CRC2
        private const val VERSION = 1
        private const val SUFFIX = ".continuity"
    }

    @Synchronized
    fun observe(
        conversationIdentity: String,
        atEpochMs: Long = System.currentTimeMillis(),
    ): RelayConversationContinuity {
        require(conversationIdentity.isNotBlank())
        ensureDirectory()
        val existing = readIfPresent(conversationIdentity)
        val next = RelayConversationContinuity(
            conversationIdentity = conversationIdentity,
            observationSequence = (existing?.observationSequence ?: 0L) + 1L,
            firstSeenAtEpochMs = existing?.firstSeenAtEpochMs ?: atEpochMs,
            lastSeenAtEpochMs = maxOf(existing?.lastSeenAtEpochMs ?: atEpochMs, atEpochMs),
        )
        write(next)
        return next
    }

    @Synchronized
    fun get(conversationIdentity: String): RelayConversationContinuity? {
        ensureDirectory()
        return readIfPresent(conversationIdentity)
    }

    @Synchronized
    fun pruneOlderThan(cutoffEpochMs: Long) {
        ensureDirectory()
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .forEach { file ->
                val item = runCatching { read(file) }.getOrNull() ?: return@forEach
                if (item.lastSeenAtEpochMs < cutoffEpochMs) file.delete()
            }
    }

    private fun readIfPresent(identity: String): RelayConversationContinuity? {
        val file = fileFor(identity)
        if (!file.exists()) return null
        return runCatching { read(file) }.getOrNull()
    }

    private fun read(file: File): RelayConversationContinuity =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            check(input.readInt() == MAGIC) { "Unexpected continuity magic" }
            check(input.readInt() == VERSION) { "Unsupported continuity version" }
            RelayConversationContinuity(
                conversationIdentity = input.readUTF(),
                observationSequence = input.readLong(),
                firstSeenAtEpochMs = input.readLong(),
                lastSeenAtEpochMs = input.readLong(),
            )
        }

    private fun write(item: RelayConversationContinuity) {
        val target = fileFor(item.conversationIdentity)
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(item.conversationIdentity)
                    output.writeLong(item.observationSequence)
                    output.writeLong(item.firstSeenAtEpochMs)
                    output.writeLong(item.lastSeenAtEpochMs)
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
        if (!directory.exists() && !directory.mkdirs()) error("Could not create continuity directory")
        check(directory.isDirectory) { "Continuity path is not a directory" }
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