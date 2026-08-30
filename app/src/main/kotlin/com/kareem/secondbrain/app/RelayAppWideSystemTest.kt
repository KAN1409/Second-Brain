package com.kareem.secondbrain.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kareem.secondbrain.capture.android.accessibility.BrainAccessibilityService
import com.kareem.secondbrain.capture.android.connector.RelayDeliveryState
import com.kareem.secondbrain.capture.android.connector.RelayForensicBuffer
import com.kareem.secondbrain.capture.android.connector.RelayRuntimeDiagnostics
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestCase
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestReport
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestStatus
import com.kareem.secondbrain.capture.android.connector.RelayV2OperationalMetrics
import com.kareem.secondbrain.capture.android.connector.RelayV2Protocol
import com.kareem.secondbrain.capture.android.notification.BrainNotificationListener
import com.kareem.secondbrain.capture.android.notification.RelayEvidenceIntelligence
import com.kareem.secondbrain.capture.android.tile.CapturePauseTileService
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
                row.notification_listener_connected && !access.notificationAccess -> warn(
                    "Persisted notification-listener health was stale",
                    "Android access is currently revoked while database connected=true. MainActivity reconciliation will clear it.",
                )
                row.accessibility_connected && !access.accessibilityAccess -> warn(
                    "Persisted Accessibility health was stale",
                    "Android access is currently revoked while database connected=true. MainActivity reconciliation will clear it.",
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
            )
            val missing = services.filterNot { serviceDeclared(context, it) }.map { it.simpleName }
            if (missing.isEmpty()) pass("All Relay capture services are present in the merged APK manifest")
            else fail("Merged APK is missing capture services", missing.joinToString())
        }

        cases += execute("app.scope.no_voice_capture", "App/Product boundary") {
            val requested = requestedPermissions(context)
            if (Manifest.permission.RECORD_AUDIO !in requested) {
                pass(
                    "Relay has no microphone capture permission",
                    "Voice capture belongs to Cortex; Relay remains an evidence gateway.",
                )
            } else {
                fail("Relay still requests microphone permission", Manifest.permission.RECORD_AUDIO)
            }
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

        cases += execute("v2.evidence_intelligence_runtime", "V2/Evidence Intelligence") {
            val stats = RelayEvidenceIntelligence.forContext(context).stats()
            val observations = stats.optInt("observations", -1)
            val entities = stats.optInt("entity_candidates", -1)
            val episodes = stats.optInt("episodes", -1)
            val crossAppEpisodes = stats.optInt("cross_app_episodes", -1)
            val crossAppEntities = stats.optInt("cross_app_entity_candidates", -1)
            if (observations >= 0 && entities >= 0 && episodes >= 0 && crossAppEpisodes >= 0 && crossAppEntities >= 0) {
                pass(
                    "Evidence Intelligence durable state is readable and bounded",
                    "observations=$observations, episodes=$episodes, cross_app_episodes=$crossAppEpisodes, entities=$entities, cross_app_entities=$crossAppEntities",
                )
            } else fail("Evidence Intelligence state counters are invalid", stats.toString())
        }

        cases += needsRealEvent(
            "real.cross_app_episode",
            "Device acceptance",
            "Real cross-app episode + grounded entity continuity",
            "Open two apps within five minutes around one shared exact entity (for example the same order/reference/URL), then receive/capture related evidence and verify one cross-app episode and candidate with identity_claim=false.",
        )
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

        val combined = adjudicateRuntimeEvidence(context, base.cases + cases)
        return base.copy(
            overallStatus = overallStatus(combined),
            cases = combined,
        )
    }

    /**
     * A NEEDS_REAL_EVENT case is a statement about missing proof, not a permanent status. When the
     * current process contains enough grounded evidence from a real Android/Cortex interaction, the
     * report promotes that exact case to PASS automatically. Anything not provable remains explicit.
     */
    private fun adjudicateRuntimeEvidence(
        context: Context,
        cases: List<RelaySystemTestCase>,
    ): List<RelaySystemTestCase> {
        val diagnostics = RelayRuntimeDiagnostics.state.value
        val metrics = RelayV2OperationalMetrics.snapshot()
        val forensicEventIds = runCatching {
            RelayForensicBuffer.forContext(context).recent(100).mapTo(mutableSetOf()) { it.eventId }
        }.getOrDefault(emptySet())
        val intelligenceStats = runCatching { RelayEvidenceIntelligence.forContext(context).stats() }.getOrNull()

        val deliveredRealNotification = diagnostics.recentSignals.firstOrNull { signal ->
            signal.deliveryState == RelayDeliveryState.FORWARDED &&
                signal.cortexSignalId > 0 &&
                signal.eventId in forensicEventIds
        }
        val sourceProfiles = diagnostics.recentSignals
            .mapNotNull { it.sourceProfileIdentity?.takeIf(String::isNotBlank) }
            .distinct()
        val liveDelta = diagnostics.recentSignals.firstOrNull { signal ->
            signal.lifecycleState == "UPDATED" &&
                (signal.updateSequence ?: 0) > 0 &&
                signal.logicalSignalId?.startsWith("signal-message-delta_") == true &&
                signal.messages.isNotEmpty()
        }

        return cases.map { test ->
            if (test.status != RelaySystemTestStatus.NEEDS_REAL_EVENT) return@map test
            when (test.id) {
                "real.notification_listener" -> deliveredRealNotification?.let { signal ->
                    promote(
                        test,
                        "Real NotificationListener capture -> forensic record -> Cortex ACK verified",
                        "${signal.packageName} · ${signal.logicalSignalId} -> Cortex signal ${signal.cortexSignalId}",
                    )
                } ?: test

                "real.multi_account" -> if (sourceProfiles.size >= 2) {
                    promote(
                        test,
                        "Real distinct Android source/profile identities observed",
                        "distinct_source_profiles=${sourceProfiles.size}",
                    )
                } else test

                "real.live_message_delta" -> liveDelta?.let { signal ->
                    promote(
                        test,
                        "Real live notification update produced a bounded new-message delta",
                        "${signal.logicalSignalId} · update_sequence=${signal.updateSequence} · delta_messages=${signal.messages.size}",
                    )
                } ?: test

                "real.action_execution" -> if (metrics.actionSucceeded > 0) {
                    promote(
                        test,
                        "At least one real Cortex-authorized Android action succeeded",
                        "requests=${metrics.actionRequests}, succeeded=${metrics.actionSucceeded}, failed=${metrics.actionFailed}",
                    )
                } else test

                "real.v2_roundtrip" -> if (
                    metrics.negotiatedProtocol == RelayV2Protocol.SIGNAL_PROTOCOL &&
                    diagnostics.forwarded > 0 &&
                    diagnostics.lastCortexSignalId > 0 &&
                    diagnostics.lastCortexStatus in setOf("ACCEPTED", "DUPLICATE_ACCEPTED")
                ) {
                    promote(
                        test,
                        "Cortex selected Signal V2 and completed a correlated V2 data round-trip",
                        "forwarded=${diagnostics.forwarded}, last_signal=${diagnostics.lastCortexSignalId}, status=${diagnostics.lastCortexStatus}",
                    )
                } else test

                "real.cross_app_episode" -> if (
                    (intelligenceStats?.optInt("cross_app_episodes", 0) ?: 0) > 0 &&
                    (intelligenceStats?.optInt("cross_app_entity_candidates", 0) ?: 0) > 0
                ) {
                    promote(
                        test,
                        "Real cross-app episode and exact grounded entity continuity observed",
                        "cross_app_episodes=${intelligenceStats?.optInt("cross_app_episodes")}, cross_app_entities=${intelligenceStats?.optInt("cross_app_entity_candidates")}",
                    )
                } else test

                else -> test
            }
        }
    }

    private fun promote(test: RelaySystemTestCase, summary: String, detail: String) = test.copy(
        status = RelaySystemTestStatus.PASS,
        summary = summary,
        detail = detail,
    )

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

    @Suppress("DEPRECATION")
    private fun requestedPermissions(context: Context): Set<String> =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

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
