package com.kareem.secondbrain.capture.android.health

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kareem.secondbrain.core.model.CaptureAccessSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaptureAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun snapshot(): CaptureAccessSnapshot = CaptureAccessSnapshot(
        notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
        accessibilityAccess = accessibilityEnabled(),
        usageAccess = usageAccessEnabled(),
        microphoneAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )

    private fun accessibilityEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    private fun usageAccessEnabled(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }
}
