package com.kareem.secondbrain.capture.android.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kareem.secondbrain.capture.android.health.CaptureAccessChecker
import com.kareem.secondbrain.capture.android.notification.RelayEvidenceIntelligence
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.AppSessionRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.concurrent.TimeUnit

class UsageReconciliationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            UsageWorkerEntryPoint::class.java,
        )
        if (!entryPoint.accessChecker().snapshot().usageAccess) return Result.success()
        if (entryPoint.captureRepository().observeCaptureState().first().mode == CaptureMode.PAUSED) {
            return Result.success()
        }

        val usage = applicationContext.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val begin = end - RECONCILIATION_LOOKBACK_MS
        val events = usage.queryEvents(begin, end)
        val scratch = UsageEvents.Event()
        val latestStateByPackage = mutableMapOf<String, UsageState>()

        while (events.hasNextEvent()) {
            events.getNextEvent(scratch)
            val relevant = scratch.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                scratch.eventType == UsageEvents.Event.ACTIVITY_PAUSED
            val eventPackage = scratch.packageName?.takeIf { it.isNotBlank() }
            if (relevant && eventPackage != null) {
                val previous = latestStateByPackage[eventPackage]
                if (previous == null || scratch.timeStamp >= previous.timestamp) {
                    latestStateByPackage[eventPackage] = UsageState(scratch.eventType, scratch.timeStamp)
                }
            }
        }

        if (latestStateByPackage.isEmpty()) return Result.success()

        val foreground = latestStateByPackage
            .asSequence()
            .filter { (_, state) -> state.eventType == UsageEvents.Event.ACTIVITY_RESUMED }
            .maxByOrNull { (_, state) -> state.timestamp }
        val intelligence = RelayEvidenceIntelligence.forContext(applicationContext)

        if (foreground == null || foreground.key == applicationContext.packageName) {
            val closeAt = latestStateByPackage.values.maxOf { it.timestamp }.let(Instant::ofEpochMilli)
            entryPoint.appSessions().closeOpenSession(closeAt)?.let { previous ->
                intelligence.observeAppActivity(previous.packageName, closeAt.toEpochMilli(), false)
                entryPoint.captureRepository().ingest(
                    CaptureCommand.AppActivity(closeAt, previous.packageName, enteredForeground = false),
                )
            }
            return Result.success()
        }

        val packageName = foreground.key
        val at = Instant.ofEpochMilli(foreground.value.timestamp)
        val policy = entryPoint.policyRepository().get(packageName)
        if (!policy.usage) {
            entryPoint.appSessions().closeOpenSession(at)?.let { previous ->
                intelligence.observeAppActivity(previous.packageName, at.toEpochMilli(), false)
                entryPoint.captureRepository().ingest(
                    CaptureCommand.AppActivity(at, previous.packageName, enteredForeground = false),
                )
            }
            return Result.success()
        }

        val transition = entryPoint.appSessions().switchForeground(packageName, at) ?: return Result.success()
        transition.previous?.let { previous ->
            intelligence.observeAppActivity(previous.packageName, at.toEpochMilli(), false)
            entryPoint.captureRepository().ingest(
                CaptureCommand.AppActivity(at, previous.packageName, enteredForeground = false),
            )
        }
        intelligence.observeAppActivity(packageName, at.toEpochMilli(), true)
        entryPoint.captureRepository().ingest(
            CaptureCommand.AppActivity(at, packageName, enteredForeground = true),
        )
        return Result.success()
    }

    private data class UsageState(
        val eventType: Int,
        val timestamp: Long,
    )

    private companion object {
        const val RECONCILIATION_LOOKBACK_MS = 20 * 60 * 1000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UsageWorkerEntryPoint {
    fun captureRepository(): CaptureRepository
    fun policyRepository(): CapturePolicyRepository
    fun appSessions(): AppSessionRepository
    fun accessChecker(): CaptureAccessChecker
}

object UsageReconciliationScheduler {
    private const val UNIQUE_WORK = "usage-reconciliation"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageReconciliationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
