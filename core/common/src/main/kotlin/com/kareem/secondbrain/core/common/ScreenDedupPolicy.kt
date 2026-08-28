package com.kareem.secondbrain.core.common

data class ScreenFingerprint(
    val normalizedText: String,
    val sha256: String,
    val simHash: Long,
    val occurredAtMs: Long,
)

object ScreenDedupPolicy {
    const val MIN_USEFUL_CHARS = 20
    const val NEAR_DUP_WINDOW_MS = 10_000L
    const val MAX_SIMHASH_DISTANCE = 3
    const val MIN_NEW_CONTENT_RATIO = 0.15

    fun shouldStore(previous: ScreenFingerprint?, current: ScreenFingerprint): Boolean {
        if (current.normalizedText.length < MIN_USEFUL_CHARS) return false
        if (previous == null) return true
        if (previous.sha256 == current.sha256) return false
        if (current.occurredAtMs - previous.occurredAtMs > NEAR_DUP_WINDOW_MS) return true

        val near = TextFingerprint.hammingDistance(previous.simHash, current.simHash) <= MAX_SIMHASH_DISTANCE
        val newRatio = TextFingerprint.newContentRatio(previous.normalizedText, current.normalizedText)
        return !(near && newRatio < MIN_NEW_CONTENT_RATIO)
    }
}
