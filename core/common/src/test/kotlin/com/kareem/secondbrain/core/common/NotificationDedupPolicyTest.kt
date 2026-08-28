package com.kareem.secondbrain.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDedupPolicyTest {
    @Test
    fun messagingStyleEnrichment_withSameSemanticTokens_isCoalesced() {
        val previous = TextFingerprint.normalize("Kareem Abdel Nasser\nSB_M4_REAL_001")
        val current = TextFingerprint.normalize(
            "Kareem Abdel Nasser\nSB_M4_REAL_001\nKareem Abdel Nasser: SB_M4_REAL_001",
        )

        assertTrue(
            NotificationDedupPolicy.shouldCoalesceEnrichment(
                previousNormalized = previous,
                previousOccurredAtMs = 1_000,
                currentNormalized = current,
                currentOccurredAtMs = 1_400,
            ),
        )
    }

    @Test
    fun genuinelyNewMessageToken_isNotCoalesced() {
        val previous = TextFingerprint.normalize("Kareem Abdel Nasser\nhello")
        val current = TextFingerprint.normalize("Kareem Abdel Nasser\nnew meeting tomorrow")

        assertFalse(
            NotificationDedupPolicy.shouldCoalesceEnrichment(
                previousNormalized = previous,
                previousOccurredAtMs = 1_000,
                currentNormalized = current,
                currentOccurredAtMs = 1_500,
            ),
        )
    }

    @Test
    fun sameSemanticTokens_outsideWindow_areNotCoalesced() {
        val previous = TextFingerprint.normalize("Kareem Abdel Nasser\nOK")
        val current = TextFingerprint.normalize("Kareem Abdel Nasser: OK")

        assertFalse(
            NotificationDedupPolicy.shouldCoalesceEnrichment(
                previousNormalized = previous,
                previousOccurredAtMs = 1_000,
                currentNormalized = current,
                currentOccurredAtMs = 7_000,
            ),
        )
    }
}
