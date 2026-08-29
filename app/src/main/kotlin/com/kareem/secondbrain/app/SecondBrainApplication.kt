package com.kareem.secondbrain.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kareem.secondbrain.app.enrichment.VoiceRecoveryScheduler
import com.kareem.secondbrain.capture.android.connector.CortexConnectorClient
import com.kareem.secondbrain.capture.android.usage.UsageReconciliationScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SecondBrainApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        CortexConnectorClient.start(this)
        UsageReconciliationScheduler.schedule(this)
        VoiceRecoveryScheduler.schedule(this)
    }
}
