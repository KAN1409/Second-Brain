package com.kareem.secondbrain.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kareem.secondbrain.ai.api.Embedder
import com.kareem.secondbrain.ai.api.OcrEngine
import com.kareem.secondbrain.ai.api.Transcriber
import com.kareem.secondbrain.ai.embedding.EmbeddingGemmaEmbedder
import com.kareem.secondbrain.ai.embedding.EmbeddingModelInstaller
import com.kareem.secondbrain.ai.ocr.HybridOcrEngine
import com.kareem.secondbrain.ai.ocr.MlKitOcrEngine
import com.kareem.secondbrain.ai.ocr.TesseractArabicOcrEngine
import com.kareem.secondbrain.ai.whisper.WhisperCppTranscriber
import com.kareem.secondbrain.app.enrichment.WorkManagerEnrichmentScheduler
import com.kareem.secondbrain.core.database.AppSessionDao
import com.kareem.secondbrain.core.database.AssetDao
import com.kareem.secondbrain.core.database.BrainDatabase
import com.kareem.secondbrain.core.database.CaptureEventDao
import com.kareem.secondbrain.core.database.CapturePolicyDao
import com.kareem.secondbrain.core.database.CaptureStateDao
import com.kareem.secondbrain.core.database.CaptureWriteDao
import com.kareem.secondbrain.core.database.EnrichmentDao
import com.kareem.secondbrain.core.database.MemoryDao
import com.kareem.secondbrain.core.database.SearchDao
import com.kareem.secondbrain.core.search.AppSearchSemanticAccelerationIndex
import com.kareem.secondbrain.core.search.SemanticAccelerationIndex
import com.kareem.secondbrain.data.repository.RoomAppSessionRepository
import com.kareem.secondbrain.data.repository.RoomAssetRepository
import com.kareem.secondbrain.data.repository.RoomCaptureHealthRepository
import com.kareem.secondbrain.data.repository.RoomCapturePolicyRepository
import com.kareem.secondbrain.data.repository.RoomCaptureRepository
import com.kareem.secondbrain.data.repository.RoomMemoryRepository
import com.kareem.secondbrain.data.repository.RoomMemorySearchRepository
import com.kareem.secondbrain.domain.AppSessionRepository
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.CaptureHealthRepository
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.EnrichmentScheduler
import com.kareem.secondbrain.domain.MemoryRepository
import com.kareem.secondbrain.domain.MemorySearchRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BrainDatabase =
        Room.databaseBuilder(context, BrainDatabase::class.java, "second-brain.db")
            .setDriver(AndroidSQLiteDriver())
            .build()

    @Provides fun provideCaptureEventDao(db: BrainDatabase): CaptureEventDao = db.captureEventDao()
    @Provides fun provideCaptureWriteDao(db: BrainDatabase): CaptureWriteDao = db.captureWriteDao()
    @Provides fun provideCapturePolicyDao(db: BrainDatabase): CapturePolicyDao = db.capturePolicyDao()
    @Provides fun provideCaptureStateDao(db: BrainDatabase): CaptureStateDao = db.captureStateDao()
    @Provides fun provideAppSessionDao(db: BrainDatabase): AppSessionDao = db.appSessionDao()
    @Provides fun provideMemoryDao(db: BrainDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideAssetDao(db: BrainDatabase): AssetDao = db.assetDao()
    @Provides fun provideEnrichmentDao(db: BrainDatabase): EnrichmentDao = db.enrichmentDao()
    @Provides fun provideSearchDao(db: BrainDatabase): SearchDao = db.searchDao()

    @Provides
    @Singleton
    fun provideCaptureRepository(
        events: CaptureEventDao,
        writer: CaptureWriteDao,
        policies: CapturePolicyDao,
        state: CaptureStateDao,
        appSessions: AppSessionRepository,
    ): CaptureRepository = RoomCaptureRepository(events, writer, policies, state, appSessions)

    @Provides
    @Singleton
    fun provideCaptureHealthRepository(state: CaptureStateDao): CaptureHealthRepository =
        RoomCaptureHealthRepository(state)

    @Provides
    @Singleton
    fun provideCapturePolicyRepository(policies: CapturePolicyDao): CapturePolicyRepository =
        RoomCapturePolicyRepository(policies)

    @Provides
    @Singleton
    fun provideAppSessionRepository(sessions: AppSessionDao): AppSessionRepository =
        RoomAppSessionRepository(sessions)

    @Provides
    @Singleton
    fun provideMemoryRepository(memoryDao: MemoryDao): MemoryRepository =
        RoomMemoryRepository(memoryDao)

    @Provides
    @Singleton
    fun provideEmbedder(@ApplicationContext context: Context): Embedder =
        EmbeddingGemmaEmbedder(context)

    @Provides
    @Singleton
    fun provideEmbeddingModelInstaller(@ApplicationContext context: Context): EmbeddingModelInstaller =
        EmbeddingModelInstaller(context)

    @Provides
    @Singleton
    fun provideSemanticAccelerationIndex(@ApplicationContext context: Context): SemanticAccelerationIndex =
        AppSearchSemanticAccelerationIndex(context)

    @Provides
    @Singleton
    fun provideMemorySearchRepository(
        searchDao: SearchDao,
        embedder: Embedder,
        semanticAccelerationIndex: SemanticAccelerationIndex,
    ): MemorySearchRepository = RoomMemorySearchRepository(searchDao, embedder, semanticAccelerationIndex)

    @Provides
    @Singleton
    fun provideAssetRepository(
        @ApplicationContext context: Context,
        assetDao: AssetDao,
    ): AssetRepository = RoomAssetRepository(context, assetDao)

    @Provides
    @Singleton
    fun provideEnrichmentScheduler(
        @ApplicationContext context: Context,
    ): EnrichmentScheduler = WorkManagerEnrichmentScheduler(context)

    @Provides
    @Singleton
    fun provideTranscriber(@ApplicationContext context: Context): Transcriber =
        WhisperCppTranscriber(context)

    @Provides
    @Singleton
    fun provideOcrEngine(@ApplicationContext context: Context): OcrEngine =
        HybridOcrEngine(MlKitOcrEngine(context), TesseractArabicOcrEngine(context))
}
