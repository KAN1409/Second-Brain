package com.kareem.secondbrain.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    version = 1,
    exportSchema = true,
    entities = [
        CaptureEventEntity::class,
        MemoryEntity::class,
        MemoryChunkEntity::class,
        MemoryEmbeddingEntity::class,
        AssetEntity::class,
        MemoryAssetEntity::class,
        MemoryRelationEntity::class,
        EvidenceStubEntity::class,
        AppSessionEntity::class,
        CapturePolicyEntity::class,
        CaptureStateEntity::class,
    ],
)
abstract class BrainDatabase : RoomDatabase() {
    abstract fun captureEventDao(): CaptureEventDao
    abstract fun captureWriteDao(): CaptureWriteDao
    abstract fun capturePolicyDao(): CapturePolicyDao
    abstract fun captureStateDao(): CaptureStateDao
    abstract fun appSessionDao(): AppSessionDao
    abstract fun memoryDao(): MemoryDao
}
