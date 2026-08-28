package com.kareem.secondbrain.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: CaptureEventEntity)

    @Query("""
        SELECT * FROM capture_event
        WHERE source_type = :sourceType
          AND package_name = :packageName
          AND external_id = :externalId
        ORDER BY occurred_at DESC
        LIMIT 1
    """)
    suspend fun latestByExternalId(sourceType: String, packageName: String, externalId: String): CaptureEventEntity?

    @Query("""
        SELECT * FROM capture_event
        WHERE source_type = 'SCREEN'
          AND package_name = :packageName
          AND occurred_at >= :sinceMs
        ORDER BY occurred_at DESC
        LIMIT 1
    """)
    suspend fun latestScreen(packageName: String, sinceMs: Long): CaptureEventEntity?

    @Query("SELECT COUNT(*) FROM capture_event WHERE content_hash = :hash")
    suspend fun countByContentHash(hash: String): Int

    @Query("SELECT COUNT(*) FROM capture_event WHERE source_type = :sourceType AND content_hash = :hash")
    suspend fun countBySourceAndContentHash(sourceType: String, hash: String): Int

    @Query("""
        SELECT * FROM capture_event
        WHERE source_type = :sourceType
          AND asset_id = :assetId
        ORDER BY occurred_at DESC
        LIMIT 1
    """)
    suspend fun latestBySourceAndAssetId(sourceType: String, assetId: String): CaptureEventEntity?
}

/** Event + normalized Memory are committed together. */
@Dao
abstract class CaptureWriteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvent(event: CaptureEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMemory(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMemoryAsset(link: MemoryAssetEntity)

    @Query("""
        UPDATE capture_event
        SET raw_text = :rawText,
            normalized_text = :normalizedText,
            content_hash = :contentHash,
            sim_hash = :simHash,
            metadata_json = :metadataJson,
            captured_at = :capturedAt
        WHERE id = :eventId
    """)
    protected abstract suspend fun updateNotificationEvent(
        eventId: String,
        rawText: String,
        normalizedText: String,
        contentHash: String,
        simHash: Long,
        metadataJson: String?,
        capturedAt: Long,
    )

    @Query("""
        UPDATE memory
        SET title = :title,
            body = :body,
            updated_at = :updatedAt
        WHERE source_event_id = :eventId
    """)
    protected abstract suspend fun updateNotificationMemory(
        eventId: String,
        title: String?,
        body: String,
        updatedAt: Long,
    )

    @Transaction
    open suspend fun insertCapture(
        event: CaptureEventEntity,
        memory: MemoryEntity,
        memoryAsset: MemoryAssetEntity? = null,
    ) {
        insertEvent(event)
        insertMemory(memory)
        memoryAsset?.let { insertMemoryAsset(it) }
    }

    @Transaction
    open suspend fun updateNotificationCapture(
        eventId: String,
        rawText: String,
        normalizedText: String,
        contentHash: String,
        simHash: Long,
        metadataJson: String?,
        title: String?,
        updatedAt: Long,
    ) {
        updateNotificationEvent(
            eventId = eventId,
            rawText = rawText,
            normalizedText = normalizedText,
            contentHash = contentHash,
            simHash = simHash,
            metadataJson = metadataJson,
            capturedAt = updatedAt,
        )
        updateNotificationMemory(
            eventId = eventId,
            title = title,
            body = rawText,
            updatedAt = updatedAt,
        )
    }
}

@Dao
interface CapturePolicyDao {
    @Query("SELECT * FROM capture_policy ORDER BY package_name")
    fun observeAll(): Flow<List<CapturePolicyEntity>>

    @Query("SELECT * FROM capture_policy WHERE package_name = :packageName LIMIT 1")
    suspend fun get(packageName: String): CapturePolicyEntity?

    @Upsert
    suspend fun upsert(policy: CapturePolicyEntity)
}

@Dao
abstract class CaptureStateDao {
    @Query("SELECT * FROM capture_state WHERE singleton_id = 1")
    abstract fun observe(): Flow<CaptureStateEntity?>

    @Query("SELECT * FROM capture_state WHERE singleton_id = 1")
    abstract suspend fun get(): CaptureStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertInitial(state: CaptureStateEntity)

    @Query("UPDATE capture_state SET mode = :mode, updated_at = :updatedAt WHERE singleton_id = 1")
    protected abstract suspend fun updateMode(mode: String, updatedAt: Long)

    @Query("UPDATE capture_state SET notification_listener_connected = :connected, updated_at = :updatedAt WHERE singleton_id = 1")
    protected abstract suspend fun updateNotificationConnected(connected: Boolean, updatedAt: Long)

    @Query("UPDATE capture_state SET accessibility_connected = :connected, updated_at = :updatedAt WHERE singleton_id = 1")
    protected abstract suspend fun updateAccessibilityConnected(connected: Boolean, updatedAt: Long)

    @Query("""
        UPDATE capture_state
        SET last_notification_at = CASE
                WHEN last_notification_at IS NULL OR :at > last_notification_at THEN :at
                ELSE last_notification_at
            END,
            updated_at = CASE WHEN :at > updated_at THEN :at ELSE updated_at END
        WHERE singleton_id = 1
    """)
    protected abstract suspend fun updateLastNotification(at: Long)

    @Query("""
        UPDATE capture_state
        SET last_screen_memory_at = CASE
                WHEN last_screen_memory_at IS NULL OR :at > last_screen_memory_at THEN :at
                ELSE last_screen_memory_at
            END,
            updated_at = CASE WHEN :at > updated_at THEN :at ELSE updated_at END
        WHERE singleton_id = 1
    """)
    protected abstract suspend fun updateLastScreen(at: Long)

    @Query("""
        UPDATE capture_state
        SET last_app_activity_at = CASE
                WHEN last_app_activity_at IS NULL OR :at > last_app_activity_at THEN :at
                ELSE last_app_activity_at
            END,
            updated_at = CASE WHEN :at > updated_at THEN :at ELSE updated_at END
        WHERE singleton_id = 1
    """)
    protected abstract suspend fun updateLastAppActivity(at: Long)

    private suspend fun ensure(now: Long) {
        insertInitial(CaptureStateEntity(mode = "RUNNING", updated_at = now))
    }

    @Transaction
    open suspend fun setMode(mode: String, updatedAt: Long) {
        ensure(updatedAt)
        updateMode(mode, updatedAt)
    }

    @Transaction
    open suspend fun setNotificationConnected(connected: Boolean, updatedAt: Long) {
        ensure(updatedAt)
        updateNotificationConnected(connected, updatedAt)
    }

    @Transaction
    open suspend fun setAccessibilityConnected(connected: Boolean, updatedAt: Long) {
        ensure(updatedAt)
        updateAccessibilityConnected(connected, updatedAt)
    }

    @Transaction
    open suspend fun markNotification(at: Long) {
        ensure(at)
        updateLastNotification(at)
    }

    @Transaction
    open suspend fun markScreen(at: Long) {
        ensure(at)
        updateLastScreen(at)
    }

    @Transaction
    open suspend fun markAppActivity(at: Long) {
        ensure(at)
        updateLastAppActivity(at)
    }
}

@Dao
interface AppSessionDao {
    @Query("SELECT * FROM app_session WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")
    suspend fun getOpen(): AppSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: AppSessionEntity)

    @Query("UPDATE app_session SET ended_at = :endedAt WHERE id = :id AND ended_at IS NULL")
    suspend fun close(id: String, endedAt: Long)

    @Query("SELECT * FROM app_session ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AppSessionEntity>>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun get(id: String): MemoryEntity?

    @Query("UPDATE memory SET pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM asset WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): AssetEntity?

    @Query("SELECT * FROM asset WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(asset: AssetEntity)
}

@Dao
interface EnrichmentDao {
    @Query("SELECT * FROM capture_event WHERE id = :eventId LIMIT 1")
    suspend fun getEvent(eventId: String): CaptureEventEntity?

    @Query("SELECT * FROM memory WHERE source_event_id = :eventId LIMIT 1")
    suspend fun getMemoryByEventId(eventId: String): MemoryEntity?

    @Query("UPDATE capture_event SET processing_state = 'PROCESSING' WHERE id = :eventId")
    suspend fun markProcessing(eventId: String)

    @Query("UPDATE capture_event SET processing_state = 'FAILED', metadata_json = :metadataJson WHERE id = :eventId")
    suspend fun markFailed(eventId: String, metadataJson: String?)

    @Query("""
        UPDATE capture_event
        SET raw_text = :rawText,
            normalized_text = :normalizedText,
            content_hash = :contentHash,
            metadata_json = :metadataJson,
            processing_state = 'READY'
        WHERE id = :eventId
    """)
    suspend fun markReady(
        eventId: String,
        rawText: String,
        normalizedText: String,
        contentHash: String,
        metadataJson: String?,
    )

    @Query("UPDATE memory SET body = :body, updated_at = :updatedAt WHERE source_event_id = :eventId")
    suspend fun updateMemoryBody(eventId: String, body: String, updatedAt: Long)
}
