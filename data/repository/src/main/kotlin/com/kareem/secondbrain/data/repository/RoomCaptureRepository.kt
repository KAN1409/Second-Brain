package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.core.common.ScreenDedupPolicy
import com.kareem.secondbrain.core.common.ScreenFingerprint
import com.kareem.secondbrain.core.common.TextFingerprint
import com.kareem.secondbrain.core.database.CaptureEventDao
import com.kareem.secondbrain.core.database.CaptureEventEntity
import com.kareem.secondbrain.core.database.CapturePolicyDao
import com.kareem.secondbrain.core.database.CaptureStateDao
import com.kareem.secondbrain.core.database.CaptureWriteDao
import com.kareem.secondbrain.core.database.MemoryEntity
import com.kareem.secondbrain.core.database.MemoryAssetEntity
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import com.kareem.secondbrain.core.model.MemoryKind
import com.kareem.secondbrain.core.model.ProcessingState
import com.kareem.secondbrain.core.model.SourceType
import com.kareem.secondbrain.core.privacy.DefaultCapturePolicy
import com.kareem.secondbrain.domain.AppSessionRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import com.kareem.secondbrain.domain.IgnoreReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class RoomCaptureRepository(
    private val events: CaptureEventDao,
    private val writer: CaptureWriteDao,
    private val policies: CapturePolicyDao,
    private val state: CaptureStateDao,
    private val appSessions: AppSessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) : CaptureRepository {

    override fun observeCaptureState(): Flow<CaptureState> = state.observe().map { row ->
        CaptureState(
            mode = row?.mode?.let(CaptureMode::valueOf) ?: CaptureMode.RUNNING,
            notificationListenerConnected = row?.notification_listener_connected ?: false,
            accessibilityConnected = row?.accessibility_connected ?: false,
            lastNotificationAt = row?.last_notification_at?.let(Instant::ofEpochMilli),
            lastScreenMemoryAt = row?.last_screen_memory_at?.let(Instant::ofEpochMilli),
            lastAppActivityAt = row?.last_app_activity_at?.let(Instant::ofEpochMilli),
        )
    }

    override suspend fun setCaptureMode(mode: CaptureMode) {
        val now = Instant.ofEpochMilli(clock.millis())
        state.setMode(mode.name, now.toEpochMilli())
        if (mode == CaptureMode.PAUSED) appSessions.closeOpenSession(now)
    }

    override suspend fun ingest(command: CaptureCommand): CaptureResult {
        if ((state.get()?.mode ?: CaptureMode.RUNNING.name) == CaptureMode.PAUSED.name) {
            return CaptureResult.Ignored(IgnoreReason.CAPTURE_PAUSED)
        }
        if (!policyAllows(command)) return CaptureResult.Ignored(IgnoreReason.POLICY_BLOCKED)

        return when (command) {
            is CaptureCommand.Notification -> ingestNotification(command)
            is CaptureCommand.Screen -> ingestScreen(command)
            else -> ingestGeneric(command)
        }
    }

    private suspend fun policyAllows(command: CaptureCommand): Boolean {
        val packageName = command.packageName ?: return true
        val persisted = policies.get(packageName)
        val policy = persisted?.toModel() ?: DefaultCapturePolicy.forPackage(packageName)
        return when (command) {
            is CaptureCommand.Notification -> policy.notifications
            is CaptureCommand.Screen -> policy.accessibility
            is CaptureCommand.AppActivity -> policy.usage
            else -> true
        }
    }

    private suspend fun ingestNotification(command: CaptureCommand.Notification): CaptureResult {
        val messageLines = command.messages.map { message ->
            buildString {
                if (!message.sender.isNullOrBlank()) append(message.sender).append(": ")
                append(message.text)
            }
        }
        val raw = (listOfNotNull(command.title, command.body, command.expandedText, command.conversationTitle) + messageLines)
            .distinct()
            .joinToString("\n")
        val normalized = TextFingerprint.normalize(raw)
        if (normalized.isBlank()) return CaptureResult.Ignored(IgnoreReason.EMPTY_OR_TOO_SHORT)
        val hash = TextFingerprint.sha256(normalized)
        val previous = events.latestByExternalId(SourceType.NOTIFICATION.name, command.packageName, command.notificationKey)
        if (previous?.content_hash == hash) return CaptureResult.Ignored(IgnoreReason.EXACT_DUPLICATE)

        return insert(
            source = SourceType.NOTIFICATION,
            memoryKind = MemoryKind.NOTIFICATION,
            title = command.conversationTitle ?: command.title,
            packageName = command.packageName,
            externalId = command.notificationKey,
            occurredAt = command.occurredAt,
            raw = raw,
            normalized = normalized,
            hash = hash,
            simHash = TextFingerprint.simHash64(normalized),
            metadataJson = command.metadataJson,
        )
    }

    private suspend fun ingestScreen(command: CaptureCommand.Screen): CaptureResult {
        val normalized = TextFingerprint.normalize(command.accessibleText)
        if (normalized.length < ScreenDedupPolicy.MIN_USEFUL_CHARS) {
            return CaptureResult.Ignored(IgnoreReason.EMPTY_OR_TOO_SHORT)
        }
        val hash = TextFingerprint.sha256(normalized)
        val sim = TextFingerprint.simHash64(normalized)
        val current = ScreenFingerprint(normalized, hash, sim, command.occurredAt.toEpochMilli())
        val previous = events.latestScreen(
            command.packageName,
            command.occurredAt.toEpochMilli() - ScreenDedupPolicy.NEAR_DUP_WINDOW_MS,
        )?.let { row ->
            ScreenFingerprint(
                normalizedText = row.normalized_text.orEmpty(),
                sha256 = row.content_hash,
                simHash = row.sim_hash ?: 0L,
                occurredAtMs = row.occurred_at,
            )
        }
        if (!ScreenDedupPolicy.shouldStore(previous, current)) {
            return CaptureResult.Ignored(
                if (previous?.sha256 == hash) IgnoreReason.EXACT_DUPLICATE else IgnoreReason.NEAR_DUPLICATE,
            )
        }

        return insert(
            source = SourceType.SCREEN,
            memoryKind = MemoryKind.SCREEN_CONTEXT,
            title = null,
            packageName = command.packageName,
            externalId = null,
            occurredAt = command.occurredAt,
            raw = command.accessibleText,
            normalized = normalized,
            hash = hash,
            simHash = sim,
            metadataJson = command.metadataJson,
        )
    }

    private suspend fun ingestGeneric(command: CaptureCommand): CaptureResult {
        val raw = when (command) {
            is CaptureCommand.Note -> command.text
            is CaptureCommand.Link -> listOfNotNull(command.title, command.url).joinToString("\n")
            is CaptureCommand.Share -> command.text.orEmpty()
            is CaptureCommand.File -> command.displayName.orEmpty()
            is CaptureCommand.Voice -> command.assetId
            is CaptureCommand.Image -> command.assetId
            is CaptureCommand.AppActivity -> if (command.enteredForeground) "FOREGROUND" else "BACKGROUND"
            else -> ""
        }
        val normalized = TextFingerprint.normalize(raw)
        val hash = TextFingerprint.sha256(normalized.ifBlank { command.toString() })
        val source = when (command) {
            is CaptureCommand.AppActivity -> SourceType.APP_ACTIVITY
            is CaptureCommand.Voice -> SourceType.VOICE
            is CaptureCommand.Image -> SourceType.IMAGE
            is CaptureCommand.Share -> SourceType.SHARE
            is CaptureCommand.Note -> SourceType.NOTE
            is CaptureCommand.Link -> SourceType.LINK
            is CaptureCommand.File -> SourceType.FILE
            else -> error("Handled earlier")
        }
        val kind = when (command) {
            is CaptureCommand.AppActivity -> MemoryKind.APP_ACTIVITY
            is CaptureCommand.Note -> MemoryKind.NOTE
            is CaptureCommand.Link -> MemoryKind.WEB_LINK
            is CaptureCommand.Voice -> MemoryKind.VOICE_TRANSCRIPT
            is CaptureCommand.Image -> MemoryKind.OCR
            is CaptureCommand.Share -> MemoryKind.NOTE
            is CaptureCommand.File -> MemoryKind.NOTE
            else -> error("Handled earlier")
        }
        val title = when (command) {
            is CaptureCommand.Link -> command.title
            is CaptureCommand.File -> command.displayName
            is CaptureCommand.AppActivity -> command.packageName
            else -> null
        }
        val assetId = when (command) {
            is CaptureCommand.Voice -> command.assetId
            is CaptureCommand.Image -> command.assetId
            is CaptureCommand.File -> command.assetId
            else -> null
        }
        if (command is CaptureCommand.Image && events.countBySourceAndContentHash(SourceType.IMAGE.name, hash) > 0) {
            return CaptureResult.Ignored(IgnoreReason.EXACT_DUPLICATE)
        }
        return insert(
            source = source,
            memoryKind = kind,
            title = title,
            packageName = command.packageName,
            externalId = null,
            occurredAt = command.occurredAt,
            raw = raw,
            normalized = normalized,
            hash = hash,
            simHash = null,
            metadataJson = null,
            assetId = assetId,
        )
    }

    private suspend fun insert(
        source: SourceType,
        memoryKind: MemoryKind,
        title: String?,
        packageName: String?,
        externalId: String?,
        occurredAt: Instant,
        raw: String?,
        normalized: String?,
        hash: String,
        simHash: Long?,
        metadataJson: String?,
        assetId: String? = null,
    ): CaptureResult {
        val eventId = UUID.randomUUID().toString()
        val memoryId = UUID.randomUUID().toString()
        val now = clock.millis()
        val expiresAt = occurredAt.plus(90, ChronoUnit.DAYS).toEpochMilli()
        return try {
            writer.insertCapture(
                CaptureEventEntity(
                    id = eventId,
                    source_type = source.name,
                    package_name = packageName,
                    external_id = externalId,
                    occurred_at = occurredAt.toEpochMilli(),
                    captured_at = now,
                    raw_text = raw,
                    normalized_text = normalized,
                    content_hash = hash,
                    sim_hash = simHash,
                    metadata_json = metadataJson,
                    asset_id = assetId,
                    expires_at = expiresAt,
                    processing_state = ProcessingState.RAW.name,
                ),
                MemoryEntity(
                    id = memoryId,
                    source_event_id = eventId,
                    kind = memoryKind.name,
                    title = title,
                    body = raw.orEmpty(),
                    summary = null,
                    source_package = packageName,
                    started_at = occurredAt.toEpochMilli(),
                    ended_at = null,
                    importance = 0.0,
                    pinned = false,
                    long_term = false,
                    created_at = now,
                    updated_at = now,
                    expires_at = expiresAt,
                ),
                assetId?.let { MemoryAssetEntity(memory_id = memoryId, asset_id = it) },
            )
            when (source) {
                SourceType.NOTIFICATION -> state.markNotification(occurredAt.toEpochMilli())
                SourceType.SCREEN -> state.markScreen(occurredAt.toEpochMilli())
                SourceType.APP_ACTIVITY -> state.markAppActivity(occurredAt.toEpochMilli())
                else -> Unit
            }
            CaptureResult.Stored(eventId)
        } catch (t: Throwable) {
            CaptureResult.Failed(retryable = true, message = t.message ?: t::class.java.simpleName)
        }
    }
}
