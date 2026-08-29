package com.kareem.secondbrain.capture.android.connector

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kareem.secondbrain.capture.android.notification.DurableNotificationLifecycleStore
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisFacts
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisMessage
import com.kareem.secondbrain.capture.android.notification.NotificationAnalysisPerson
import com.kareem.secondbrain.capture.android.notification.NotificationMeaningfulChange
import com.kareem.secondbrain.capture.android.notification.NotificationNoiseClassifier
import com.kareem.secondbrain.capture.android.notification.NotificationNoiseFacts
import com.kareem.secondbrain.capture.android.notification.NotificationSignalAnalyzer
import com.kareem.secondbrain.capture.android.notification.RelaySignalType
import java.io.File
import java.time.Instant
import java.util.UUID

/** Result state for one in-app Full System Test case. */
enum class RelaySystemTestStatus {
    PASS,
    FAIL,
    WARN,
    NOT_IMPLEMENTED,
    NEEDS_REAL_EVENT,
}

data class RelaySystemTestCase(
    val id: String,
    val area: String,
    val status: RelaySystemTestStatus,
    val summary: String,
    val detail: String? = null,
    val durationMs: Long = 0,
)

data class RelaySystemTestInput(
    val captureRunning: Boolean,
    val notificationAccess: Boolean,
    val accessibilityAccess: Boolean,
    val usageAccess: Boolean,
    val diagnostics: RelayDiagnosticSnapshot,
)

data class RelaySystemTestReport(
    val schema: String,
    val runId: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val overallStatus: String,
    val cases: List<RelaySystemTestCase>,
) {
    val counts: Map<RelaySystemTestStatus, Int>
        get() = RelaySystemTestStatus.entries.associateWith { status -> cases.count { it.status == status } }
}

/**
 * Deterministic and non-destructive in-app verification for Cortex Relay.
 *
 * This runner deliberately refuses to fake external Android/Cortex scenarios. Tests that require a
 * real notification, process death, another Android profile or a real action remain
 * NEEDS_REAL_EVENT until guided device acceptance proves them.
 */
object RelaySystemTestRunner {
    const val SCHEMA = "CORTEX_RELAY_FULL_SYSTEM_TEST_V1"

    fun run(context: Context, input: RelaySystemTestInput): RelaySystemTestReport {
        val startedAt = Instant.now()
        val runId = "relay-test-${UUID.randomUUID()}"
        val cases = mutableListOf<RelaySystemTestCase>()
        val root = File(context.cacheDir, "relay-full-system-test/$runId")

        try {
            cases += execute("app.identity", "App", "Package/version identity") {
                val version = packageVersion(context)
                val correctPackage = context.packageName == "com.kareem.secondbrain"
                if (correctPackage && version.first.isNotBlank()) {
                    pass("Package/version resolved", "${context.packageName} · ${version.first} (${version.second})")
                } else {
                    fail("Unexpected app identity", "package=${context.packageName}, version=${version.first}, code=${version.second}")
                }
            }

            cases += execute("capture.mode", "Capture", "Capture mode") {
                if (input.captureRunning) pass("Capture is running")
                else warn("Capture is currently paused", "Resume capture before final device acceptance.")
            }

            cases += execute("access.notification_listener", "Capture", "Notification access") {
                if (input.notificationAccess) pass("Notification listener access is granted")
                else fail("Notification listener access is missing", "Relay cannot observe real notification evidence without it.")
            }

            cases += execute("access.accessibility", "Capture", "Accessibility access") {
                if (input.accessibilityAccess) pass("Accessibility access is granted")
                else warn("Accessibility access is not granted", "This does not block notification Relay but limits future evidence surfaces that depend on Accessibility.")
            }

            cases += execute("access.usage", "Capture", "Usage access") {
                if (input.usageAccess) pass("Usage access is granted")
                else warn("Usage access is not granted", "This does not block notification Relay but limits future foreground-app/context evidence.")
            }

            cases += execute("storage.filesystem", "Storage", "Local filesystem round-trip") {
                root.mkdirs()
                val probe = File(root, "filesystem-probe.txt")
                val payload = "relay-self-test-$runId"
                probe.writeText(payload, Charsets.UTF_8)
                val readBack = probe.readText(Charsets.UTF_8)
                if (readBack == payload && probe.delete()) pass("Local read/write/delete succeeded")
                else fail("Local filesystem round-trip mismatch")
            }

            cases += execute("delivery.outbox_roundtrip", "Delivery", "Durable outbox primitive") {
                val outbox = DurableRelayOutbox(File(root, "outbox"))
                val eventId = "self-test-event"
                val firstRaw = "{\"probe\":1}"
                outbox.put(eventId, firstRaw, 1000L)
                outbox.put(eventId, "{\"probe\":2}", 2000L)
                val loaded = outbox.loadAll()
                val duplicateSafe = loaded.entries.size == 1 && loaded.entries.single().eventId == eventId && loaded.entries.single().raw == firstRaw
                outbox.remove(eventId)
                if (duplicateSafe && outbox.count() == 0 && loaded.corruptFiles == 0) {
                    pass("Durable put/load/dedupe/remove succeeded")
                } else {
                    fail(
                        "Durable outbox primitive failed",
                        "entries=${loaded.entries.size}, corrupt=${loaded.corruptFiles}, remaining=${outbox.count()}",
                    )
                }
            }

            cases += execute("lifecycle.state_machine", "Lifecycle", "POSTED/UPDATED/REMOVED/repost") {
                val store = DurableNotificationLifecycleStore(File(root, "lifecycle-state"))
                val identity = "self-test-notification"
                val first = store.observePosted(identity, "visible-1", "stable-1", listOf("m1"), 1000L)
                val duplicate = store.observePosted(identity, "visible-1", "stable-1", listOf("m1"), 1100L)
                val updated = store.observePosted(identity, "visible-2", "stable-2", listOf("m1", "m2"), 1200L)
                val removed = store.markRemoved(identity, 1300L)
                val repost = store.observePosted(identity, "visible-3", "stable-3", listOf("m3"), 1400L)
                val ok = first.state.name == "POSTED" && first.isNewInstance &&
                    duplicate.state.name == "UPDATED" && duplicate.unchanged &&
                    updated.state.name == "UPDATED" && updated.newMessageFingerprints == setOf("m2") &&
                    removed.state.name == "REMOVED" && repost.state.name == "POSTED" &&
                    repost.generation == first.generation + 1
                if (ok) pass("Lifecycle state machine behaved as expected")
                else fail("Lifecycle state machine mismatch", "first=$first duplicate=$duplicate updated=$updated removed=$removed repost=$repost")
            }

            cases += execute("normalization.message_delta", "Normalization", "MessagingStyle delta + stable conversation identity") {
                val store = DurableNotificationLifecycleStore(File(root, "lifecycle-analysis"))
                val firstMessage = NotificationAnalysisMessage("Alice", "Hello", Instant.ofEpochMilli(1000L))
                val secondMessage = NotificationAnalysisMessage(
                    "Alice",
                    "Your verification code is 123456 — https://example.com",
                    Instant.ofEpochMilli(2000L),
                )
                val facts1 = messageFacts(listOf(firstMessage))
                val identity = NotificationSignalAnalyzer.notificationIdentity(facts1)
                val lifecycle1 = store.observePosted(
                    identity,
                    NotificationSignalAnalyzer.visibleFingerprint(facts1),
                    NotificationSignalAnalyzer.stableChurnFingerprint(facts1),
                    facts1.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                    1000L,
                )
                val analysis1 = NotificationSignalAnalyzer.analyze(facts1, lifecycle1)

                val facts2 = messageFacts(listOf(firstMessage, secondMessage))
                val lifecycle2 = store.observePosted(
                    identity,
                    NotificationSignalAnalyzer.visibleFingerprint(facts2),
                    NotificationSignalAnalyzer.stableChurnFingerprint(facts2),
                    facts2.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                    2000L,
                )
                val analysis2 = NotificationSignalAnalyzer.analyze(facts2, lifecycle2)
                val entityTypes = analysis2.entities.map { it.type }.toSet()
                val ok = analysis1.signalType == RelaySignalType.HUMAN_MESSAGE &&
                    analysis2.signalType == RelaySignalType.OTP &&
                    analysis2.change == NotificationMeaningfulChange.NEW_MESSAGES &&
                    analysis2.newMessageFingerprints.size == 1 &&
                    analysis1.conversationIdentity == analysis2.conversationIdentity &&
                    analysis1.logicalSignalId != analysis2.logicalSignalId &&
                    analysis2.logicalSignalId.startsWith("signal-message-delta_") &&
                    "OTP" in entityTypes && "URL" in entityTypes
                if (ok) {
                    pass(
                        "New-message delta, identity and grounded entities verified",
                        "conversation=${analysis2.conversationIdentity}; logical=${analysis2.logicalSignalId}; entities=${entityTypes.sorted()}",
                    )
                } else {
                    fail("Message delta/identity analysis mismatch", "analysis1=$analysis1 analysis2=$analysis2 entities=$entityTypes")
                }
            }

            cases += execute("filter.conservative_noise", "Filtering", "Conservative noise classification") {
                val summary = NotificationNoiseClassifier.classify(
                    NotificationNoiseFacts(
                        packageName = "example.chat",
                        title = "Alice",
                        body = "Hello",
                        expandedText = null,
                        isOngoing = false,
                        category = "msg",
                        channelId = "messages",
                        isGroupSummary = true,
                        meaningfulChange = NotificationMeaningfulChange.NEW_POST,
                        signalType = RelaySignalType.HUMAN_MESSAGE,
                    ),
                )
                val child = NotificationNoiseClassifier.classify(
                    NotificationNoiseFacts(
                        packageName = "example.chat",
                        title = "Alice",
                        body = "Hello",
                        expandedText = null,
                        isOngoing = false,
                        category = "msg",
                        channelId = "messages",
                        isGroupSummary = false,
                        meaningfulChange = NotificationMeaningfulChange.NEW_POST,
                        signalType = RelaySignalType.HUMAN_MESSAGE,
                    ),
                )
                if (summary.state == RelayFilterState.DROP_CONFIRMED_NOISE && child.state == RelayFilterState.FORWARD) {
                    pass("Group container is suppressed while useful child evidence is preserved")
                } else {
                    fail("Noise classifier violated conservative child/summary rule", "summary=${summary.state}, child=${child.state}")
                }
            }

            cases += execute("ui.source_labels", "UI/Identity", "Installed app-label resolution") {
                val packages = (input.diagnostics.recentSignals.map { it.packageName } + context.packageName).distinct().take(20)
                val unresolved = mutableListOf<String>()
                val misleading = mutableListOf<String>()
                val labels = packages.associateWith { packageName ->
                    val label = installedAppLabel(context, packageName)
                    if (label.isNullOrBlank()) unresolved += packageName
                    if (label.equals("Android", ignoreCase = true) && packageName !in setOf("android", "com.android.systemui")) {
                        misleading += "$packageName -> $label"
                    }
                    label
                }
                when {
                    misleading.isNotEmpty() -> fail("Misleading generic source label detected", misleading.joinToString())
                    unresolved.isNotEmpty() -> warn("Some source labels cannot be resolved by PackageManager", unresolved.joinToString())
                    else -> pass("Installed source labels resolved", labels.entries.joinToString { "${it.key}=${it.value}" })
                }
            }

            cases += execute("cortex.connection", "Cortex", "Current connector state") {
                when (input.diagnostics.connectionState) {
                    RelayConnectionState.CONNECTED -> pass("Cortex Local Bus is connected")
                    RelayConnectionState.CONNECTING -> warn("Cortex Local Bus is currently connecting")
                    RelayConnectionState.DISCONNECTED -> warn("Cortex Local Bus is currently disconnected", "Run final acceptance with Cortex available.")
                }
            }

            cases += execute("cortex.correlated_ack", "Cortex", "Recent exact ACK evidence") {
                val accepted = input.diagnostics.recentSignals.firstOrNull { signal ->
                    signal.deliveryState == RelayDeliveryState.FORWARDED &&
                        signal.cortexSignalId > 0 &&
                        signal.cortexStatus in setOf("ACCEPTED", "DUPLICATE_ACCEPTED")
                }
                if (accepted != null) {
                    pass("Recent correlated Cortex ACK exists", "sb_${accepted.eventId} -> signal ${accepted.cortexSignalId}")
                } else {
                    needsRealEvent("No recent correlated ACK is present in this process session", "Send one real notification through Relay and rerun the test.")
                }
            }

            cases += execute("delivery.backlog_health", "Delivery", "Current backlog/retry health") {
                when {
                    input.diagnostics.waiting > 0 -> warn(
                        "Relay currently has ${input.diagnostics.waiting} waiting/in-flight event(s)",
                        input.diagnostics.lastError,
                    )
                    input.diagnostics.rejected > 0 -> warn(
                        "Current session contains ${input.diagnostics.rejected} Cortex rejection(s)",
                        input.diagnostics.lastError,
                    )
                    else -> pass(
                        "No current waiting backlog or Cortex rejection",
                        "retry_incidents=${input.diagnostics.failedRetries}",
                    )
                }
            }

            // v2 workstream registration. These deliberately block a v2 candidate until their
            // implementations replace NOT_IMPLEMENTED with deterministic tests.
            cases += notImplemented("v2.semantic_schemas", "V2", "Generic semantic evidence schemas")
            cases += notImplemented("v2.attachment_provenance", "V2", "Attachment/link/file provenance")
            cases += notImplemented("v2.forensic_buffer", "V2", "24–72h forensic buffer")
            cases += notImplemented("v2.replay_engine", "V2", "Replay/debug engine")
            cases += notImplemented("v2.observability", "V2", "Extended operational health metrics")
            cases += notImplemented("v2.policy_feedback", "V2", "Cortex capture-policy feedback")
            cases += notImplemented("v2.action_capabilities", "V2", "Action capability extraction")
            cases += notImplemented("v2.action_bridge", "V2", "Cortex-authorized action execution")
            cases += notImplemented("v2.signal_protocol", "V2", "Signal/Local Bus V2 negotiation")

            // External acceptance probes: never fake these with an in-process unit test.
            cases += needsRealEventCase(
                "real.notification_listener",
                "Device acceptance",
                "Real NotificationListener event",
                "Receive a real notification from another app and verify capture -> normalization -> delivery.",
            )
            cases += needsRealEventCase(
                "real.process_death_recovery",
                "Device acceptance",
                "Durable recovery across process death/reboot",
                "Keep Cortex unavailable, queue a real event, kill/restart Relay, then verify the same event id survives and completes after Cortex returns.",
            )
            cases += needsRealEventCase(
                "real.multi_account",
                "Device acceptance",
                "Real multi-account/profile identity",
                "Exercise at least two Android account/profile surfaces and verify grounded source/conversation identity separation where Android exposes it.",
            )
            cases += needsRealEventCase(
                "real.live_message_delta",
                "Device acceptance",
                "Live notification new-message delta",
                "Keep a conversation notification live, receive another message, and verify one new delta rather than replaying old history.",
            )
            cases += needsRealEventCase(
                "real.action_execution",
                "Device acceptance",
                "Real Android action execution",
                "After Action Bridge is implemented, execute a real exposed action and verify a correlated result/audit record.",
            )
            cases += needsRealEventCase(
                "real.v2_roundtrip",
                "Device acceptance",
                "Cortex V2 negotiation and round-trip",
                "After Signal V2 is implemented on both sides, verify negotiation, signal ACK, policy/action messages and fallback behavior.",
            )
        } finally {
            root.deleteRecursively()
        }

        val overall = when {
            cases.any { it.status == RelaySystemTestStatus.FAIL } -> "FAIL"
            cases.any { it.status == RelaySystemTestStatus.NOT_IMPLEMENTED } -> "IMPLEMENTATION_INCOMPLETE"
            cases.any { it.status == RelaySystemTestStatus.NEEDS_REAL_EVENT } -> "REAL_DEVICE_VALIDATION_REQUIRED"
            cases.any { it.status == RelaySystemTestStatus.WARN } -> "PASS_WITH_WARNINGS"
            else -> "PASS"
        }
        val version = packageVersion(context)
        return RelaySystemTestReport(
            schema = SCHEMA,
            runId = runId,
            startedAt = startedAt,
            finishedAt = Instant.now(),
            packageName = context.packageName,
            versionName = version.first,
            versionCode = version.second,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            overallStatus = overall,
            cases = cases,
        )
    }

    private fun messageFacts(messages: List<NotificationAnalysisMessage>) = NotificationAnalysisFacts(
        packageName = "example.chat",
        notificationKey = "self-test-key",
        androidUserId = 0,
        uid = 12345,
        tag = "self-test",
        shortcutId = "thread-alice",
        channelId = "messages",
        category = "msg",
        isOngoing = false,
        title = "Alice",
        body = messages.lastOrNull()?.text,
        expandedText = null,
        conversationTitle = "Alice",
        messages = messages,
        people = listOf(NotificationAnalysisPerson("Alice", "alice-key", null)),
        replyable = true,
    )

    private fun execute(
        id: String,
        area: String,
        label: String,
        block: () -> RelaySystemTestCase,
    ): RelaySystemTestCase {
        val started = System.nanoTime()
        return try {
            block().copy(id = id, area = area, durationMs = elapsedMs(started))
        } catch (t: Throwable) {
            RelaySystemTestCase(
                id = id,
                area = area,
                status = RelaySystemTestStatus.FAIL,
                summary = "$label threw ${t.javaClass.simpleName}",
                detail = t.message,
                durationMs = elapsedMs(started),
            )
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    private fun pass(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.PASS, summary, detail)
    private fun fail(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.FAIL, summary, detail)
    private fun warn(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.WARN, summary, detail)
    private fun needsRealEvent(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.NEEDS_REAL_EVENT, summary, detail)

    private fun notImplemented(id: String, area: String, summary: String) = RelaySystemTestCase(
        id = id,
        area = area,
        status = RelaySystemTestStatus.NOT_IMPLEMENTED,
        summary = summary,
        detail = "Required by Cortex Relay v2.0 major-update scope; implementation/test probe not complete yet.",
    )

    private fun needsRealEventCase(id: String, area: String, summary: String, detail: String) = RelaySystemTestCase(
        id = id,
        area = area,
        status = RelaySystemTestStatus.NEEDS_REAL_EVENT,
        summary = summary,
        detail = detail,
    )

    @Suppress("DEPRECATION")
    private fun packageVersion(context: Context): Pair<String, Long> {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionName = info.versionName.orEmpty()
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return versionName to versionCode
    }

    @Suppress("DEPRECATION")
    private fun installedAppLabel(context: Context, packageName: String): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            context.packageManager.getApplicationInfo(packageName, 0)
        }
        context.packageManager.getApplicationLabel(info).toString().trim().takeIf(String::isNotBlank)
    }.getOrNull()
}
