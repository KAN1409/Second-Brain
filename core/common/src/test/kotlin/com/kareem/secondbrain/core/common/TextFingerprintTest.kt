package com.kareem.secondbrain.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFingerprintTest {
    @Test
    fun normalization_isDeterministic() {
        assertEquals("Hello world", TextFingerprint.normalize("  Hello\n\t world  "))
    }

    @Test
    fun exactText_hasZeroSimHashDistance() {
        val text = TextFingerprint.normalize("Sarah moved the meeting to Wednesday")
        val a = TextFingerprint.simHash64(text)
        val b = TextFingerprint.simHash64(text)
        assertEquals(0, TextFingerprint.hammingDistance(a, b))
    }

    @Test
    fun screenExactDuplicate_isDiscarded() {
        val text = TextFingerprint.normalize("A sufficiently long visible screen state for testing")
        val sha = TextFingerprint.sha256(text)
        val sim = TextFingerprint.simHash64(text)
        val previous = ScreenFingerprint(text, sha, sim, 1_000)
        val current = ScreenFingerprint(text, sha, sim, 1_500)
        assertFalse(ScreenDedupPolicy.shouldStore(previous, current))
    }

    @Test
    fun substantiallyNewScreen_isStored() {
        val old = TextFingerprint.normalize("Camera list Sony Alpha A7 IV price and specifications")
        val fresh = TextFingerprint.normalize("Restaurant address reservation Thursday Cairo menu phone number")
        val previous = ScreenFingerprint(old, TextFingerprint.sha256(old), TextFingerprint.simHash64(old), 1_000)
        val current = ScreenFingerprint(fresh, TextFingerprint.sha256(fresh), TextFingerprint.simHash64(fresh), 1_500)
        assertTrue(ScreenDedupPolicy.shouldStore(previous, current))
    }
}
