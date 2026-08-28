package com.kareem.secondbrain.core.privacy

import com.kareem.secondbrain.core.model.AppCapturePolicy

/**
 * Conservative defaults for categories that commonly contain secrets.
 * This is deliberately only a safety net; onboarding/per-app controls remain authoritative.
 */
object DefaultCapturePolicy {
    private val sensitivePackageTokens = listOf(
        "authenticator",
        "authy",
        "bitwarden",
        "onepassword",
        "lastpass",
        "keepass",
        "password",
        "wallet",
        "bank",
        "banking",
        "secureid",
        "token",
    )

    private val knownSensitivePackages = setOf(
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.lastpass.lpandroid",
        "com.samsung.android.spay",
    )

    fun forPackage(packageName: String): AppCapturePolicy {
        val normalized = packageName.lowercase()
        val sensitive = packageName in knownSensitivePackages ||
            sensitivePackageTokens.any(normalized::contains)
        return if (sensitive) {
            AppCapturePolicy(
                packageName = packageName,
                notifications = false,
                accessibility = false,
                usage = true,
                ocr = false,
                allowAiUpload = false,
            )
        } else {
            AppCapturePolicy(packageName = packageName)
        }
    }
}
