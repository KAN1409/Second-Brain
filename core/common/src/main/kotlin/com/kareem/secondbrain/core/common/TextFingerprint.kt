package com.kareem.secondbrain.core.common

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.max

object TextFingerprint {
    private val whitespace = Regex("\\s+")
    private val tokenRegex = Regex("[\\p{L}\\p{N}_@#.+:/-]+")

    fun normalize(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFC)
            .trim()
            .replace(whitespace, " ")

    fun sha256(normalizedText: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(normalizedText.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    fun simHash64(normalizedText: String): Long {
        val tokens = tokenRegex.findAll(normalizedText.lowercase())
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()
        if (tokens.isEmpty()) return 0L

        val weights = IntArray(64)
        for (token in tokens) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
            val hash = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
            for (bit in 0 until 64) {
                val set = (hash ushr bit) and 1L == 1L
                weights[bit] += if (set) 1 else -1
            }
        }

        var result = 0L
        for (bit in 0 until 64) {
            if (weights[bit] >= 0) result = result or (1L shl bit)
        }
        return result
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Token-level approximation used only for the screen near-duplicate gate. */
    fun newContentRatio(previous: String, current: String): Double {
        val oldTokens = tokenRegex.findAll(previous.lowercase()).map { it.value }.toSet()
        val newTokens = tokenRegex.findAll(current.lowercase()).map { it.value }.toSet()
        if (newTokens.isEmpty()) return 0.0
        if (oldTokens.isEmpty()) return 1.0
        val newlyVisible = newTokens.count { it !in oldTokens }
        return newlyVisible.toDouble() / max(1, newTokens.size)
    }
}
