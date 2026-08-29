package com.kareem.secondbrain.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kareem.secondbrain.capture.android.accessibility.BrainAccessibilityService
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestCase
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestReport
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestStatus
import com.kareem.secondbrain.capture.android.notification.BrainNotificationListener
import com.kareem.secondbrain.capture.android.tile.CapturePauseTileService
import com.kareem.secondbrain.capture.android.voice.PendingVoiceFile
import com.kareem.secondbrain.capture.android.voice.VoiceRecordingService
import com.kareem.secondbrain.core.database.BrainDatabase
import com.kareem.secondbrain.core.model.CaptureAccessSnapshot
import com.kareem.secondbrain.core.model.CaptureState
import java.util.concurrent.TimeUnit

/** App-module probes layered on top of the capture-module Relay system test. */
internal object RelayAppWideSystemTest {
    suspend fun augment(
        context: Context,
        base: RelaySystemTestReport,
        database: BrainDatabase,
        access: CaptureAccessSnapshot,
        captureState: CaptureState,
    ): RelaySystemTestReport {
        val cases = mutableListOf<RelaySystemTestCase>()

        cases += execute("app.database.room_read", "App/Database") {
            val row = database.captureStateDao().get()
            when {
                row == null -> pass(
                    "Room database is readable",
                    "capture_state has not been persisted yet; runtime default=${captureState.mode.name}",
                )
                row.mode == captureState.mode.name -> pass(
                    "Room database and runtime capture state agree",
                    "mode=${row.mode}, notification_connected=${row.notification_listener_connected}, accessibility_connected=${row.accessibility_connected}",
                )
                else -> warn(
                    "Room database is readable but runtime state is newer",
                    "database=${row.mode}, runtime=${captureState.mode.name}",
                )
            }
        }

        cases += execute("app.components.capture_services", "App/Components") {
            val services = listOf(
                BrainNotificationListener::class.java,
                BrainAccessibilityService::class.java,
                CapturePauseTileService::class.java,
                VoiceRecordingService::class.java,
            )
            val missing = services.filterNot { serviceDeclared(context, it) }.map { it.simpleName }
            if (missing.isEmpty()) pass("All capture services are present in the merged APK manifest")
            else fail("Merged APK is missing capture services", missing.joinToString())
        }

        cases += execute("app.components_entrypoints", "App/Components") {
            val activityOk = activityDeclared(context, ShareReceiverActivity::class.java)
            val receiverOk = receiverDeclared(context, RelayRecoveryReceiver::class.java)
            if (activityOk && receiverOk) {
                pass("Share receiver and boot/package-replacement recovery receiver are declared")
            } else {
                fail("Required app entrypoints are missing", "share_activity=$activityOk, recovery_receiver=$receiverOk")
            }
        }

        cases += execute("app.work.usage_reconciliation", "App/WorkManager") {
            val infos = workInfos(context, "usage-reconciliation")
            if (infos.any { it.state != WorkInfo.State.CANCELLED }) {
                pass("Usage reconciliation periodic work is registered", workStateDetail(infos))
            } else fail("Usage reconciliation work is not registered", workStateDetail(infos))
        }

        cases += execute("app.work.voice_recovery", "App/WorkManager") {
            val infos = workInfos(context, "recover-pending-voice")
            if (infos.any { it.state != WorkInfo.State.CANCELLED }) {
                pass("Voice recovery work is registered", workStateDetail(infos))
            } else fail("Voice recovery work is not registered", workStateDetail(infos))
        }

        cases += execute("app.voice.staging", "App/Voice") {
            val pending = PendingVoiceFile.list(context)
            val malformed = pending.filter { file -> file.length() < PendingVoiceFile.WAV_HEADER_BYTES }
            when {
                malformed.isNotEmpty() -> warn(
                    "Pending voice staging contains malformed files",
                    malformed.joinToString { "${it.name}:${it.length()}" },
                )
                else -> pass(
                    "Pending voice staging is readable",
                    "pending_files=${pending.size}, with_audio=${pending.count(PendingVoiceFile::hasAudio)}",
                )
            }
        }

        cases += execute("app.voice.microphone_access", "App/Voice") {
            if (access.microphoneAccess) pass("Microphone permission is granted for real voice capture")
            else warn("Microphone permission is not granted", "Voice capture requires a user permission grant before device acceptance.")
        }

        cases += needsRealEvent(
            "real.accessibility_screen_capture",
            "Device acceptance",
            "Real Accessibility screen evidence",
            "With Accessibility enabled, navigate a non-sensitive test screen and verify one grounded SCREEN capture without password-field text.",
        )
        cases += needsRealEvent(
            "real.usage_reconciliation",
            "Device acceptance",
            "Real foreground-app Usage reconciliation",
            "Open another app, allow the reconciliation window to run, then verify grounded APP_ACTIVITY evidence and one coherent open app session.",
        )
        cases += needsRealEvent(
            "real.voice_capture_recovery",
            "Device acceptance",
            "Real voice capture + recovery",
            "Record a short voice note, verify durable staging/import/transcription scheduling, and repeat once with a process interruption during staging.",
        )
        cases += needsRealEvent(
            "real.share_ingest",
            "Device acceptance",
            "Real Android share-target ingest",
            "Share text/link/file content into Cortex Relay and verify grounded capture with the source package and assets preserved.",
        )
        cases += needsRealEvent(
            "real.ocr_enrichment",
            "Device acceptance",
            "Real OCR enrichment",
            "Provide one image/screenshot through a supported user-initiated path and verify OCR/enrichment completes without changing the original evidence provenance.",
        )

        val combined = base.cases + cases
        return base.copy(
            overallStatus = overallStatus(combined),
            cases = combined,
        )
    }

    @Suppress("DEPRECATION")
    private fun serviceDeclared(context: Context, clazz: Class<*>): Boolean = runCatching {
        context.packageManager.getServiceInfo(ComponentName(context, clazz), 0)
    }.isSuccess

    @Suppress("DEPRECATION")
    private fun activityDeclared(context: Context, clazz: Class<*>): Boolean = runCatching {
        context.packageManager.getActivityInfo(ComponentName(context, clazz), 0)
    }.isSuccess

    @Suppress("DEPRECATION")
    private fun receiverDeclared(context: Context, clazz: Class<*>): Boolean = runCatching {
        context.packageManager.getReceiverInfo(ComponentName(context, clazz), 0)
    }.isSuccess

    private fun workInfos(context: Context, uniqueName: String): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(uniqueName)
            .get(5, TimeUnit.SECONDS)

    private fun workStateDetail(infos: List<WorkInfo>): String =
        if (infos.isEmpty()) "no work records" else infos.joinToString { "${it.id}:${it.state.name}" }

    private inline fun execute(id: String, area: String, block: () -> RelaySystemTestCase): RelaySystemTestCase {
        val started = System.nanoTime()
        return try {
            block().copy(id = id, area = area, durationMs = elapsedMs(started))
        } catch (t: Throwable) {
            RelaySystemTestCase(
                id = id,
                area = area,
                status = RelaySystemTestStatus.FAIL,
                summary = "Probe threw ${t.javaClass.simpleName}",
                detail = t.message,
                durationMs = elapsedMs(started),
            )
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private fun pass(summary: String, detail: String? = null) =
        RelaySystemTestCase("", "", RelaySystemTestStatus.PASS, summary, detail)

    private fun warn(summary: String, detail: String? = null) =
        RelaySystemTestCase("", "", RelaySystemTestStatus.WARN, summary, detail)

    private fun fail(summary: String, detail: String? = null) =
        RelaySystemTestCase("", "", RelaySystemTestStatus.FAIL, summary, detail)

    private fun needsRealEvent(id: String, area: String, summary: String, detail: String) =
        RelaySystemTestCase(id, area, RelaySystemTestStatus.NEEDS_REAL_EVENT, summary, detail)

    private fun overallStatus(cases: List<RelaySystemTestCase>): String = when {
        cases.any { it.status == RelaySystemTestStatus.FAIL } -> "FAIL"
        cases.any { it.status == RelaySystemTestStatus.NOT_IMPLEMENTED } -> "IMPLEMENTATION_INCOMPLETE"
        cases.any { it.status == RelaySystemTestStatus.NEEDS_REAL_EVENT } -> "REAL_DEVICE_VALIDATION_REQUIRED"
        cases.any { it.status == RelaySystemTestStatus.WARN } -> "PASS_WITH_WARNINGS"
        else -> "PASS"
    }
}
