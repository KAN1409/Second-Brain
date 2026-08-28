package com.kareem.secondbrain.data.repository

import com.kareem.secondbrain.core.database.MemoryDao
import com.kareem.secondbrain.core.database.MemoryEntity
import com.kareem.secondbrain.core.model.Memory
import com.kareem.secondbrain.core.model.MemoryKind
import com.kareem.secondbrain.core.model.TimelineRequest
import com.kareem.secondbrain.domain.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant

class RoomMemoryRepository(
    private val dao: MemoryDao,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryRepository {
    override fun observeTimeline(request: TimelineRequest): Flow<List<Memory>> =
        dao.observeRecent(500).map { rows ->
            rows.asSequence()
                .map { it.toModel() }
                .filter { memory -> request.from?.let { memory.startedAt >= it } ?: true }
                .filter { memory -> request.to?.let { memory.startedAt <= it } ?: true }
                .filter { memory -> request.appPackages.isEmpty() || memory.sourcePackage in request.appPackages }
                .filter { memory -> request.kinds.isEmpty() || memory.kind in request.kinds }
                .filter { memory -> !request.pinnedOnly || memory.pinned }
                .toList()
        }

    override suspend fun getMemory(id: String): Memory? = dao.get(id)?.toModel()

    override suspend fun pin(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned, clock.millis())
    }

    override suspend fun delete(id: String) = dao.delete(id)
}

private fun MemoryEntity.toModel() = Memory(
    id = id,
    kind = MemoryKind.valueOf(kind),
    title = title,
    body = body,
    summary = summary,
    sourcePackage = source_package,
    startedAt = Instant.ofEpochMilli(started_at),
    endedAt = ended_at?.let(Instant::ofEpochMilli),
    importance = importance,
    pinned = pinned,
    longTerm = long_term,
    expiresAt = expires_at?.let(Instant::ofEpochMilli),
)
