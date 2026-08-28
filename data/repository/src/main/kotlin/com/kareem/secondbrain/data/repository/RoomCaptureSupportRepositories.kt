package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.core.database.AppSessionDao
import com.kareem.secondbrain.core.database.AppSessionEntity
import com.kareem.secondbrain.core.database.CapturePolicyDao
import com.kareem.secondbrain.core.database.CapturePolicyEntity
import com.kareem.secondbrain.core.database.CaptureStateDao
import com.kareem.secondbrain.core.model.AppCapturePolicy
import com.kareem.secondbrain.core.model.AppSession
import com.kareem.secondbrain.core.privacy.DefaultCapturePolicy
import com.kareem.secondbrain.domain.AppSessionRepository
import com.kareem.secondbrain.domain.AppSessionTransition
import com.kareem.secondbrain.domain.CaptureHealthRepository
import com.kareem.secondbrain.domain.CapturePolicyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.util.UUID

class RoomCaptureHealthRepository(
    private val state: CaptureStateDao,
    private val clock: Clock = Clock.systemUTC(),
) : CaptureHealthRepository {
    override suspend fun setNotificationListenerConnected(connected: Boolean) {
        state.setNotificationConnected(connected, clock.millis())
    }

    override suspend fun setAccessibilityConnected(connected: Boolean) {
        state.setAccessibilityConnected(connected, clock.millis())
    }

    override suspend fun markNotificationCaptured(at: Instant) = state.markNotification(at.toEpochMilli())
    override suspend fun markScreenCaptured(at: Instant) = state.markScreen(at.toEpochMilli())
    override suspend fun markAppActivityCaptured(at: Instant) = state.markAppActivity(at.toEpochMilli())
}

class RoomCapturePolicyRepository(
    private val dao: CapturePolicyDao,
) : CapturePolicyRepository {
    override fun observePolicies(): Flow<List<AppCapturePolicy>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun get(packageName: String): AppCapturePolicy =
        dao.get(packageName)?.toModel() ?: DefaultCapturePolicy.forPackage(packageName)

    override suspend fun set(policy: AppCapturePolicy) {
        dao.upsert(policy.toEntity())
    }
}

class RoomAppSessionRepository(
    private val dao: AppSessionDao,
) : AppSessionRepository {
    private val mutex = Mutex()

    override suspend fun switchForeground(packageName: String, at: Instant): AppSessionTransition? = mutex.withLock {
        val open = dao.getOpen()
        if (open?.package_name == packageName) return@withLock null

        val previous = open?.let {
            dao.close(it.id, at.toEpochMilli())
            it.copy(ended_at = at.toEpochMilli()).toModel()
        }
        val current = AppSessionEntity(
            id = UUID.randomUUID().toString(),
            package_name = packageName,
            started_at = at.toEpochMilli(),
            ended_at = null,
        )
        dao.insert(current)
        AppSessionTransition(previous = previous, current = current.toModel())
    }

    override suspend fun closeOpenSession(at: Instant): AppSession? = mutex.withLock {
        val open = dao.getOpen() ?: return@withLock null
        dao.close(open.id, at.toEpochMilli())
        open.copy(ended_at = at.toEpochMilli()).toModel()
    }

    override fun observeRecentSessions(limit: Int): Flow<List<AppSession>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }
}

internal fun CapturePolicyEntity.toModel() = AppCapturePolicy(
    packageName = package_name,
    notifications = notifications,
    accessibility = accessibility,
    usage = usage,
    ocr = ocr,
    allowAiUpload = allow_ai_upload,
)

private fun AppCapturePolicy.toEntity() = CapturePolicyEntity(
    package_name = packageName,
    notifications = notifications,
    accessibility = accessibility,
    usage = usage,
    ocr = ocr,
    allow_ai_upload = allowAiUpload,
)

private fun AppSessionEntity.toModel() = AppSession(
    id = id,
    packageName = package_name,
    startedAt = Instant.ofEpochMilli(started_at),
    endedAt = ended_at?.let(Instant::ofEpochMilli),
)
