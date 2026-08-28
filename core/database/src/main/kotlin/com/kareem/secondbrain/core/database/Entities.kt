package com.kareem.secondbrain.core.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "capture_event",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["occurred_at"]),
        Index(value = ["expires_at"]),
        Index(value = ["package_name", "occurred_at"]),
        Index(value = ["source_type", "external_id"]),
        Index(value = ["content_hash"]),
    ],
)
data class CaptureEventEntity(
    val id: String,
    val source_type: String,
    val package_name: String?,
    val external_id: String?,
    val occurred_at: Long,
    val captured_at: Long,
    val raw_text: String?,
    val normalized_text: String?,
    val content_hash: String,
    val sim_hash: Long?,
    val metadata_json: String?,
    val asset_id: String?,
    val expires_at: Long?,
    val processing_state: String,
)

@Entity(
    tableName = "memory",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["source_event_id"]),
        Index(value = ["started_at"]),
        Index(value = ["expires_at"]),
        Index(value = ["source_package", "started_at"]),
        Index(value = ["kind"]),
    ],
)
data class MemoryEntity(
    val id: String,
    val source_event_id: String?,
    val kind: String,
    val title: String?,
    val body: String,
    val summary: String?,
    val source_package: String?,
    val started_at: Long,
    val ended_at: Long?,
    val importance: Double,
    val pinned: Boolean,
    val long_term: Boolean,
    val created_at: Long,
    val updated_at: Long,
    val expires_at: Long?,
)

@Entity(
    tableName = "memory_chunk",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(
        entity = MemoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["memory_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["memory_id"]), Index(value = ["content_hash"])],
)
data class MemoryChunkEntity(
    val id: String,
    val memory_id: String,
    val ordinal: Int,
    val text: String,
    val content_hash: String,
    val embedding_model_signature: String?,
    val index_state: String,
)

@Entity(
    tableName = "memory_embedding",
    primaryKeys = ["chunk_id", "model_signature"],
    foreignKeys = [ForeignKey(
        entity = MemoryChunkEntity::class,
        parentColumns = ["id"],
        childColumns = ["chunk_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["chunk_id"]), Index(value = ["model_signature"])],
)
data class MemoryEmbeddingEntity(
    val chunk_id: String,
    val model_signature: String,
    val dimensions: Int,
    val encoding: String,
    val vector_blob: ByteArray,
    val created_at: Long,
)

@Entity(tableName = "asset", primaryKeys = ["id"], indices = [Index(value = ["sha256"], unique = true), Index(value = ["expires_at"])])
data class AssetEntity(
    val id: String,
    val relative_path: String,
    val mime_type: String,
    val sha256: String,
    val size_bytes: Long,
    val width: Int?,
    val height: Int?,
    val duration_ms: Long?,
    val created_at: Long,
    val expires_at: Long?,
)

@Entity(
    tableName = "memory_asset",
    primaryKeys = ["memory_id", "asset_id"],
    foreignKeys = [
        ForeignKey(entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["memory_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["asset_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["asset_id"])],
)
data class MemoryAssetEntity(val memory_id: String, val asset_id: String)

@Entity(
    tableName = "memory_relation",
    primaryKeys = ["source_memory_id", "target_memory_id", "relation_type"],
    indices = [Index(value = ["target_memory_id"])],
)
data class MemoryRelationEntity(
    val source_memory_id: String,
    val target_memory_id: String,
    val relation_type: String,
    val confidence: Double,
)

/** Minimal immutable provenance retained when a long-term fact outlives a raw memory. */
@Entity(
    tableName = "evidence_stub",
    primaryKeys = ["id"],
    indices = [Index(value = ["long_term_memory_id"]), Index(value = ["source_package", "occurred_at"])],
)
data class EvidenceStubEntity(
    val id: String,
    val long_term_memory_id: String,
    val source_memory_id: String?,
    val source_event_id: String?,
    val source_package: String?,
    val occurred_at: Long,
    val excerpt: String,
    val excerpt_hash: String,
    val source_content_hash: String?,
    val created_at: Long,
)

@Entity(tableName = "app_session", primaryKeys = ["id"], indices = [Index(value = ["package_name", "started_at"])])
data class AppSessionEntity(
    val id: String,
    val package_name: String,
    val started_at: Long,
    val ended_at: Long?,
)

@Entity(tableName = "capture_policy", primaryKeys = ["package_name"])
data class CapturePolicyEntity(
    val package_name: String,
    val notifications: Boolean,
    val accessibility: Boolean,
    val usage: Boolean,
    val ocr: Boolean,
    val allow_ai_upload: Boolean = false,
)

@Entity(tableName = "capture_state", primaryKeys = ["singleton_id"])
data class CaptureStateEntity(
    val singleton_id: Int = 1,
    val mode: String,
    val notification_listener_connected: Boolean = false,
    val accessibility_connected: Boolean = false,
    val last_notification_at: Long? = null,
    val last_screen_memory_at: Long? = null,
    val last_app_activity_at: Long? = null,
    val updated_at: Long,
)
