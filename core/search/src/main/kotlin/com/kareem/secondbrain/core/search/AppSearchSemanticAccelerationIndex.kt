package com.kareem.secondbrain.core.search

import android.content.Context
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.EmbeddingVector
import androidx.appsearch.app.Features
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AppSearchSemanticAccelerationIndex(context: Context) : SemanticAccelerationIndex {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val indexedKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var session: AppSearchSession? = null
    @Volatile private var unsupported = false

    override suspend fun isSupported(): Boolean = withContext(Dispatchers.IO) {
        sessionBlocking() != null
    }

    override suspend fun upsert(documents: List<SemanticIndexDocument>): Boolean = withContext(Dispatchers.IO) {
        if (documents.isEmpty()) return@withContext true
        val active = sessionBlocking() ?: return@withContext false
        val pending = documents.filter { document ->
            indexKey(document.chunkId, document.modelSignature) !in indexedKeys
        }
        if (pending.isEmpty()) return@withContext true

        runCatching {
            val request = PutDocumentsRequest.Builder()
                .addGenericDocuments(pending.map(AppSearchDocumentFactory::document))
                .build()
            val result = active.putAsync(request).get()
            if (result.isSuccess) {
                pending.forEach { document -> indexedKeys += indexKey(document.chunkId, document.modelSignature) }
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    override suspend fun candidateChunkIds(
        queryVector: FloatArray,
        modelSignature: String,
        limit: Int,
    ): Set<String>? = withContext(Dispatchers.IO) {
        if (queryVector.isEmpty()) return@withContext emptySet()
        val active = sessionBlocking() ?: return@withContext null
        runCatching {
            val cappedLimit = limit.coerceIn(1, MAX_CANDIDATES)
            val spec = SearchSpec.Builder()
                .addEmbeddingParameters(listOf(EmbeddingVector(queryVector, modelSignature)))
                .setResultCountPerPage(cappedLimit)
                .build()
            val results = active.search(SEMANTIC_QUERY, spec)
            results.use { searchResults ->
                val ids = LinkedHashSet<String>()
                while (ids.size < cappedLimit) {
                    val page = searchResults.nextPageAsync.get()
                    if (page.isEmpty()) break
                    for (result in page) {
                        ids += result.genericDocument.id
                        if (ids.size >= cappedLimit) break
                    }
                }
                ids
            }
        }.getOrNull()
    }

    private fun sessionBlocking(): AppSearchSession? {
        if (unsupported) return null
        session?.let { return it }
        synchronized(lock) {
            if (unsupported) return null
            session?.let { return it }

            val created = runCatching {
                LocalStorage.createSearchSessionAsync(
                    LocalStorage.SearchContext.Builder(appContext, DATABASE_NAME).build(),
                ).get()
            }.getOrNull() ?: return null

            if (!created.features.isFeatureSupported(Features.SCHEMA_EMBEDDING_PROPERTY_CONFIG)) {
                unsupported = true
                created.close()
                return null
            }

            val schema = AppSearchDocumentFactory.schema()
            val normalRequest = SetSchemaRequest.Builder().addSchemas(schema).build()
            val schemaReady = runCatching { created.setSchemaAsync(normalRequest).get() }.isSuccess ||
                runCatching {
                    created.setSchemaAsync(
                        SetSchemaRequest.Builder()
                            .addSchemas(schema)
                            .setForceOverride(true)
                            .build(),
                    ).get()
                }.isSuccess
            if (!schemaReady) {
                created.close()
                return null
            }
            session = created
            return created
        }
    }

    private fun indexKey(chunkId: String, signature: String): String = "$signature|$chunkId"

    private companion object {
        const val DATABASE_NAME = "semantic-acceleration-v1"
        const val MAX_CANDIDATES = 1000
        const val SEMANTIC_QUERY =
            "semanticSearch(getEmbeddingParameter(0), -1.0, 1.0, \"COSINE\")"
    }
}
