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

/** Non-destructive in-app verification. Real external scenarios are never faked. */
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
                if (context.packageName == "com.kareem.secondbrain" && version.first.isNotBlank()) {
                    pass("Package/version resolved", "${context.packageName} · ${version.first} (${version.second})")
                } else fail("Unexpected app identity", "package=${context.packageName}, version=${version.first}, code=${version.second}")
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
                else warn("Accessibility access is not granted", "Notification Relay still works, but Accessibility evidence surfaces are limited.")
            }
            cases += execute("access.usage", "Capture", "Usage access") {
                if (input.usageAccess) pass("Usage access is granted")
                else warn("Usage access is not granted", "Foreground-app/context evidence is limited.")
            }

            cases += execute("storage.filesystem", "Storage", "Local filesystem round-trip") {
                root.mkdirs()
                val probe = File(root, "filesystem-probe.txt")
                val payload = "relay-self-test-$runId"
                probe.writeText(payload, Charsets.UTF_8)
                val ok = probe.readText(Charsets.UTF_8) == payload && probe.delete()
                if (ok) pass("Local read/write/delete succeeded") else fail("Local filesystem round-trip mismatch")
            }

            cases += execute("delivery.outbox_roundtrip", "Delivery", "Durable outbox primitive") {
                val outbox = DurableRelayOutbox(File(root, "outbox"))
                outbox.put("self-test-event", "{\"probe\":1}", 1_000L)
                outbox.put("self-test-event", "{\"probe\":2}", 2_000L)
                val loaded = outbox.loadAll()
                val duplicateSafe = loaded.entries.size == 1 && loaded.entries.single().raw == "{\"probe\":1}"
                outbox.remove("self-test-event")
                if (duplicateSafe && outbox.count() == 0 && loaded.corruptFiles == 0) {
                    pass("Durable put/load/dedupe/remove succeeded")
                } else fail("Durable outbox primitive failed", "entries=${loaded.entries.size}, corrupt=${loaded.corruptFiles}, remaining=${outbox.count()}")
            }

            cases += execute("lifecycle.state_machine", "Lifecycle", "POSTED/UPDATED/REMOVED/repost") {
                val store = DurableNotificationLifecycleStore(File(root, "lifecycle-state"))
                val first = store.observePosted("self-test-notification", "visible-1", "stable-1", listOf("m1"), 1_000L)
                val duplicate = store.observePosted("self-test-notification", "visible-1", "stable-1", listOf("m1"), 1_100L)
                val updated = store.observePosted("self-test-notification", "visible-2", "stable-2", listOf("m1", "m2"), 1_200L)
                val removed = store.markRemoved("self-test-notification", 1_300L)
                val repost = store.observePosted("self-test-notification", "visible-3", "stable-3", listOf("m3"), 1_400L)
                val ok = first.state.name == "POSTED" && first.isNewInstance && duplicate.unchanged &&
                    updated.state.name == "UPDATED" && updated.newMessageFingerprints == setOf("m2") &&
                    removed.state.name == "REMOVED" && repost.state.name == "POSTED" && repost.generation == first.generation + 1
                if (ok) pass("Lifecycle state machine behaved as expected")
                else fail("Lifecycle state machine mismatch", "first=$first duplicate=$duplicate updated=$updated removed=$removed repost=$repost")
            }

            cases += execute("normalization.message_delta", "Normalization", "MessagingStyle delta + stable conversation identity") {
                val store = DurableNotificationLifecycleStore(File(root, "lifecycle-analysis"))
                val firstMessage = NotificationAnalysisMessage("Alice", "Hello", Instant.ofEpochMilli(1_000L))
                val secondMessage = NotificationAnalysisMessage("Alice", "Your verification code is 123456 — https://example.com", Instant.ofEpochMilli(2_000L))
                val facts1 = messageFacts(listOf(firstMessage))
                val identity = NotificationSignalAnalyzer.notificationIdentity(facts1)
                val lifecycle1 = store.observePosted(
                    identity,
                    NotificationSignalAnalyzer.visibleFingerprint(facts1),
                    NotificationSignalAnalyzer.stableChurnFingerprint(facts1),
                    facts1.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                    1_000L,
                )
                val analysis1 = NotificationSignalAnalyzer.analyze(facts1, lifecycle1)
                val facts2 = messageFacts(listOf(firstMessage, secondMessage))
                val lifecycle2 = store.observePosted(
                    identity,
                    NotificationSignalAnalyzer.visibleFingerprint(facts2),
                    NotificationSignalAnalyzer.stableChurnFingerprint(facts2),
                    facts2.messages.map(NotificationSignalAnalyzer::messageFingerprint),
                    2_000L,
                )
                val analysis2 = NotificationSignalAnalyzer.analyze(facts2, lifecycle2)
                val entityTypes = analysis2.entities.map { it.type }.toSet()
                val ok = analysis1.signalType == RelaySignalType.HUMAN_MESSAGE && analysis2.signalType == RelaySignalType.OTP &&
                    analysis2.change == NotificationMeaningfulChange.NEW_MESSAGES && analysis2.newMessageFingerprints.size == 1 &&
                    analysis1.conversationIdentity == analysis2.conversationIdentity && analysis1.logicalSignalId != analysis2.logicalSignalId &&
                    analysis2.logicalSignalId.startsWith("signal-message-delta_") && "OTP" in entityTypes && "URL" in entityTypes
                if (ok) pass("New-message delta, identity and grounded entities verified")
                else fail("Message delta/identity analysis mismatch", "analysis1=$analysis1 analysis2=$analysis2 entities=$entityTypes")
            }

            cases += execute("filter.conservative_noise", "Filtering", "Conservative noise classification") {
                val summary = NotificationNoiseClassifier.classify(
                    NotificationNoiseFacts("example.chat", "Alice", "Hello", null, false, "msg", "messages", true, NotificationMeaningfulChange.NEW_POST, RelaySignalType.HUMAN_MESSAGE),
                )
                val child = NotificationNoiseClassifier.classify(
                    NotificationNoiseFacts("example.chat", "Alice", "Hello", null, false, "msg", "messages", false, NotificationMeaningfulChange.NEW_POST, RelaySignalType.HUMAN_MESSAGE),
                )
                if (summary.state == RelayFilterState.DROP_CONFIRMED_NOISE && child.state == RelayFilterState.FORWARD) {
                    pass("Group container is suppressed while useful child evidence is preserved")
                } else fail("Noise classifier violated conservative child/summary rule", "summary=${summary.state}, child=${child.state}")
            }

            cases += execute("ui.source_labels", "UI/Identity", "Installed app-label resolution") {
                val packages = (input.diagnostics.recentSignals.map { it.packageName } + context.packageName).distinct().take(20)
                val unresolved = mutableListOf<String>()
                val misleading = mutableListOf<String>()
                packages.forEach { packageName ->
                    val label = installedAppLabel(context, packageName)
                    if (label.isNullOrBlank()) unresolved += packageName
                    if (label.equals("Android", ignoreCase = true) && packageName !in setOf("android", "com.android.systemui")) {
                        misleading += "$packageName -> $label"
                    }
                }
                when {
                    misleading.isNotEmpty() -> fail("Misleading generic source label detected", misleading.joinToString())
                    unresolved.isNotEmpty() -> warn("Some source labels cannot be resolved by PackageManager", unresolved.joinToString())
                    else -> pass("Installed source labels resolved")
                }
            }

            cases += execute("cortex.connection", "Cortex", "Current connector state") {
                when (input.diagnostics.connectionState) {
                    RelayConnectionState.CONNECTED -> pass("Cortex Local Bus is connected", "protocol=${CortexConnectorClient.negotiatedProtocol()}")
                    RelayConnectionState.CONNECTING -> warn("Cortex Local Bus is currently connecting")
                    RelayConnectionState.DISCONNECTED -> warn("Cortex Local Bus is currently disconnected", "Run final acceptance with Cortex available.")
                }
            }
            cases += execute("cortex.correlated_ack", "Cortex", "Recent exact ACK evidence") {
                val accepted = input.diagnostics.recentSignals.firstOrNull { signal ->
                    signal.deliveryState == RelayDeliveryState.FORWARDED && signal.cortexSignalId > 0 &&
                        signal.cortexStatus in setOf("ACCEPTED", "DUPLICATE_ACCEPTED")
                }
                if (accepted != null) pass("Recent correlated Cortex ACK exists", "sb_${accepted.eventId} -> signal ${accepted.cortexSignalId}")
                else needsRealEvent("No recent correlated ACK is present in this process session", "Send one real notification through Relay and rerun the test.")
            }
            cases += execute("delivery.backlog_health", "Delivery", "Current backlog/retry health") {
                when {
                    input.diagnostics.waiting > 0 -> warn("Relay currently has ${input.diagnostics.waiting} waiting/in-flight event(s)", input.diagnostics.lastError)
                    input.diagnostics.rejected > 0 -> warn("Current session contains ${input.diagnostics.rejected} Cortex rejection(s)", input.diagnostics.lastError)
                    else -> pass("No current waiting backlog or Cortex rejection", "retry_incidents=${input.diagnostics.failedRetries}")
                }
            }

            // Every v2 implementation workstream has an executable probe. No placeholder passes.
            cases += RelayV2SelfTests.run(context, root)

            // External acceptance probes remain explicit rather than being synthetically faked.
            cases += needsRealEventCase(
                "real.notification_listener", "Device acceptance", "Real NotificationListener event",
                "Receive a real notification from another app and verify capture -> semantic normalization -> forensic record -> delivery.",
            )
            cases += needsRealEventCase(
                "real.process_death_recovery", "Device acceptance", "Durable recovery across process death/reboot",
                "Keep Cortex unavailable, queue a real event, kill/restart Relay, then verify the same event id survives and completes after Cortex returns.",
            )
            cases += needsRealEventCase(
                "real.multi_account", "Device acceptance", "Real multi-account/profile identity",
                "Exercise at least two Android account/profile surfaces and verify grounded source/conversation identity separation where Android exposes it.",
            )
            cases += needsRealEventCase(
                "real.live_message_delta", "Device acceptance", "Live notification new-message delta",
                "Keep a conversation notification live, receive another message, and verify one new delta rather than replaying old history.",
            )
            cases += needsRealEventCase(
                "real.action_execution", "Device acceptance", "Real Android action execution",
                "Have Cortex request an actually exposed Reply/Open/Dismiss/semantic capability and verify a correlated result plus local audit record.",
            )
            cases += needsRealEventCase(
                "real.v2_roundtrip", "Device acceptance", "Cortex V2 negotiation and round-trip",
                "Use a Cortex build that explicitly selects CORTEX_SIGNAL_V2 and verify event ACK, action/policy control messages and V1 fallback when V2 is not selected.",
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

    private fun execute(id: String, area: String, label: String, block: () -> RelaySystemTestCase): RelaySystemTestCase {
        val started = System.nanoTime()
        return try {
            block().copy(id = id, area = area, durationMs = elapsedMs(started))
        } catch (t: Throwable) {
            RelaySystemTestCase(id, area, RelaySystemTestStatus.FAIL, "$label threw ${t.javaClass.simpleName}", t.message, elapsedMs(started))
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L
    private fun pass(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.PASS, summary, detail)
    private fun fail(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.FAIL, summary, detail)
    private fun warn(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.WARN, summary, detail)
    private fun needsRealEvent(summary: String, detail: String? = null) = RelaySystemTestCase("", "", RelaySystemTestStatus.NEEDS_REAL_EVENT, summary, detail)

    private fun needsRealEventCase(id: String, area: String, summary: String, detail: String) = RelaySystemTestCase(
        id, area, RelaySystemTestStatus.NEEDS_REAL_EVENT, summary, detail,
    )

    @Suppress("DEPRECATION")
    private fun packageVersion(context: Context): Pair<String, Long> {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName.orEmpty() to if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun installedAppLabel(context: Context, packageName: String): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString().trim().takeIf(String::isNotBlank)
    }.getOrNull()
}
