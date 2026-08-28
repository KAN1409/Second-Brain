package com.kareem.secondbrain.core.privacy

data class AppPrivacyPolicy(
    val packageName: String,
    val allowNotificationCapture: Boolean = true,
    val allowAccessibilityCapture: Boolean = true,
    val allowUsageTracking: Boolean = true,
    val allowOcr: Boolean = true,
    val allowCloudAi: Boolean = false,
)

interface PrivacyGate {
    fun mayUpload(packageName: String?): Boolean
}
