package com.kareem.secondbrain.core.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCapturePolicyTest {
    @Test
    fun normalApp_defaultsToLocalCaptureWithCloudOff() {
        val policy = DefaultCapturePolicy.forPackage("com.example.notes")
        assertTrue(policy.notifications)
        assertTrue(policy.accessibility)
        assertTrue(policy.usage)
        assertTrue(policy.ocr)
        assertFalse(policy.allowAiUpload)
    }

    @Test
    fun knownAuthenticator_blocksContentCaptureByDefault() {
        val policy = DefaultCapturePolicy.forPackage("com.google.android.apps.authenticator2")
        assertFalse(policy.notifications)
        assertFalse(policy.accessibility)
        assertTrue(policy.usage)
        assertFalse(policy.ocr)
        assertFalse(policy.allowAiUpload)
    }

    @Test
    fun likelyBankPackage_blocksNotificationScreenAndOcr() {
        val policy = DefaultCapturePolicy.forPackage("com.example.mobilebanking")
        assertFalse(policy.notifications)
        assertFalse(policy.accessibility)
        assertFalse(policy.ocr)
    }
}
