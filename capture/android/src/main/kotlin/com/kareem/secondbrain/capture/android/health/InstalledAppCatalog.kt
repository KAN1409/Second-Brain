package com.kareem.secondbrain.capture.android.health

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
)

@Singleton
class InstalledAppCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun launchableApps(): List<InstalledAppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { resolveInfo ->
                InstalledAppEntry(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(context.packageManager)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: resolveInfo.activityInfo.packageName,
                )
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
