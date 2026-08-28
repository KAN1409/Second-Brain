package com.kareem.secondbrain.app

import android.app.Application
import com.kareem.secondbrain.capture.android.usage.UsageReconciliationScheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SecondBrainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UsageReconciliationScheduler.schedule(this)
    }
}
