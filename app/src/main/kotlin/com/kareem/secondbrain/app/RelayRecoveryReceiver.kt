package com.kareem.secondbrain.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kareem.secondbrain.capture.android.connector.CortexConnectorClient

/** Re-opens durable Cortex delivery work after reboot or update-in-place. */
class RelayRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> CortexConnectorClient.start(context.applicationContext)
        }
    }
}
