package com.kareem.secondbrain.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kareem.secondbrain.core.database.AppSessionDao
import com.kareem.secondbrain.core.database.BrainDatabase
import com.kareem.secondbrain.core.database.CaptureEventDao
import com.kareem.secondbrain.core.database.CapturePolicyDao
import com.kareem.secondbrain.core.database.CaptureStateDao
import com.kareem.secondbrain.core.database.CaptureWriteDao
import com.kareem.secondbrain.core.database.MemoryDao
import com.kareem.secondbrain.data.repository.RoomAppSessionRepository
import com.kareem.secondbrain.data.repository.RoomCaptureHealthRepository
import com.kareem.secondbrain.data.repository.RoomCapturePolicyRepository
import com.kareem.secondbrain.data.repository.RoomCaptureRepository
import com.kareem.secondbrain.data.repository.RoomMemoryRepository
import com.kareem.secondbrain.domain.AppSessionRepository
import com.kareem.secondbrain.domain.CaptureHealthRepository
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.MemoryRepository
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
}
