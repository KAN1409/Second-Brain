package com.kareem.secondbrain.domain

import com.kareem.secondbrain.core.model.AppCapturePolicy
import com.kareem.secondbrain.core.model.AppSession
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

sealed interface CaptureCommand {
    val occurredAt: Instant
    val packageName: String?

    data class NotificationMessage(
        val sender: String?,
        val text: String,
        val timestamp: Instant?,
    )

    data class Notification(
        override val occurredAt: Instant,
        override val packageName: String,
        val notificationKey: String,
        val title: String?,
        val body: String?,
        val expandedText: String?,
        val conversationTitle: String?,
        val messages: List<NotificationMessage> = emptyList(),
        val metadataJson: String? = null,
    ) : CaptureCommand

    data class Screen(
        override val occurredAt: Instant,
        override val packageName: String,
        val accessibleText: String,
        val metadataJson: String? = null,
    ) : CaptureCommand

    data class AppActivity(
        override val occurredAt: Instant,
        override val packageName: String,
        val enteredForeground: Boolean,
    ) : CaptureCommand

    data class Image(
        override val occurredAt: Instant,
        override val packageName: String? = null,
        val assetId: String,
        val userSaved: Boolean,
    ) : CaptureCommand

    data class Share(
        override val occurredAt: Instant,
        override val packageName: String?,
        val text: String?,
        val assetIds: List<String> = emptyList(),
    ) : CaptureCommand

    data class Note(
        override val occurredAt: Instant,
        override val packageName: String? = null,
        val text: String,
    ) : CaptureCommand

    data class Link(
        override val occurredAt: Instant,
        override val packageName: String? = null,
        val url: String,
        val title: String? = null,
    ) : CaptureCommand

    data class File(
        override val occurredAt: Instant,
        override val packageName: String? = null,
        val assetId: String,
        val displayName: String?,
    ) : CaptureCommand
}

sealed interface CaptureResult {
    data class Stored(val eventId: String) : CaptureResult
    data class Ignored(val reason: IgnoreReason) : CaptureResult
    data class Failed(val retryable: Boolean, val message: String) : CaptureResult
}

enum class IgnoreReason {
    CAPTURE_PAUSED,
    POLICY_BLOCKED,
    EMPTY_OR_TOO_SHORT,
    EXACT_DUPLICATE,
    NEAR_DUPLICATE,
}

interface CaptureRepository {
    suspend fun ingest(command: CaptureCommand): CaptureResult
    fun observeCaptureState(): Flow<CaptureState>
    suspend fun setCaptureMode(mode: CaptureMode)
}

interface CaptureHealthRepository {
    suspend fun setNotificationListenerConnected(connected: Boolean)
    suspend fun setAccessibilityConnected(connected: Boolean)
    suspend fun markNotificationCaptured(at: Instant)
    suspend fun markScreenCaptured(at: Instant)
    suspend fun markAppActivityCaptured(at: Instant)
}

interface CapturePolicyRepository {
    fun observePolicies(): Flow<List<AppCapturePolicy>>
    suspend fun get(packageName: String): AppCapturePolicy
    suspend fun set(policy: AppCapturePolicy)
}

/** Maintains exactly one open foreground-app session at a time. */
interface AppSessionRepository {
    suspend fun switchForeground(packageName: String, at: Instant): AppSessionTransition?
    suspend fun closeOpenSession(at: Instant): AppSession?
    fun observeRecentSessions(limit: Int = 200): Flow<List<AppSession>>
}

data class AppSessionTransition(
    val previous: AppSession?,
    val current: AppSession,
)
