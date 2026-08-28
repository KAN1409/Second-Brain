package com.kareem.secondbrain.core.model

import java.time.Instant

enum class SourceType { NOTIFICATION, SCREEN, APP_ACTIVITY, VOICE, IMAGE, SHARE, NOTE, LINK, FILE }
enum class ProcessingState { RAW, PROCESSING, READY, FAILED }
enum class MemoryKind {
    NOTIFICATION, SCREEN_CONTEXT, VOICE_TRANSCRIPT, OCR, NOTE, WEB_LINK,
    APP_ACTIVITY, DAILY_SUMMARY, LONG_TERM_FACT, TASK
}
enum class CaptureMode { RUNNING, PAUSED }

data class CaptureState(
    val mode: CaptureMode,
    val notificationListenerConnected: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val lastNotificationAt: Instant? = null,
    val lastScreenMemoryAt: Instant? = null,
    val lastAppActivityAt: Instant? = null,
)

data class CaptureAccessSnapshot(
    val notificationAccess: Boolean,
    val accessibilityAccess: Boolean,
    val usageAccess: Boolean,
    val microphoneAccess: Boolean,
)

data class AppCapturePolicy(
    val packageName: String,
    val notifications: Boolean = true,
    val accessibility: Boolean = true,
    val usage: Boolean = true,
    val ocr: Boolean = true,
    val allowAiUpload: Boolean = false,
)

data class AppSession(
    val id: String,
    val packageName: String,
    val startedAt: Instant,
    val endedAt: Instant?,
)

data class Memory(
    val id: String,
    val kind: MemoryKind,
    val title: String?,
    val body: String,
    val summary: String?,
    val sourcePackage: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val importance: Double,
    val pinned: Boolean,
    val longTerm: Boolean,
    val expiresAt: Instant?,
)

data class TimelineRequest(
    val from: Instant? = null,
    val to: Instant? = null,
    val appPackages: Set<String> = emptySet(),
    val kinds: Set<MemoryKind> = emptySet(),
    val pinnedOnly: Boolean = false,
)

data class SearchRequest(
    val query: String,
    val from: Instant? = null,
    val to: Instant? = null,
    val appPackages: Set<String> = emptySet(),
    val kinds: Set<MemoryKind> = emptySet(),
    val pinnedOnly: Boolean = false,
    val limit: Int = 40,
)

data class SearchHit(
    val memoryId: String,
    val chunkId: String?,
    val snippet: String,
    val score: Double,
)
