package com.kareem.secondbrain.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kareem.secondbrain.capture.android.connector.RelayRuntimeDiagnostics
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestInput
import com.kareem.secondbrain.capture.android.connector.RelaySystemTestRunner
import com.kareem.secondbrain.capture.android.health.CaptureAccessChecker
import com.kareem.secondbrain.core.database.BrainDatabase
import com.kareem.secondbrain.core.model.CaptureAccessSnapshot
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import com.kareem.secondbrain.domain.CaptureRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var accessChecker: CaptureAccessChecker
    @Inject lateinit var database: BrainDatabase

    private val accessSnapshotState = mutableStateOf(CaptureAccessSnapshot(false, false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accessSnapshotState.value = accessChecker.snapshot()
        setContent {
            CortexRelayRoot(
                captureRepository = captureRepository,
                database = database,
                access = accessSnapshotState.value,
                openNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                openAccessibilityAccess = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                openUsageAccess = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accessChecker.isInitialized) accessSnapshotState.value = accessChecker.snapshot()
    }
}

@Composable
private fun CortexRelayRoot(
    captureRepository: CaptureRepository,
    database: BrainDatabase,
    access: CaptureAccessSnapshot,
    openNotificationAccess: () -> Unit,
    openAccessibilityAccess: () -> Unit,
    openUsageAccess: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var testRunning by remember { mutableStateOf(false) }
    val captureState by captureRepository.observeCaptureState().collectAsState(
        initial = CaptureState(CaptureMode.RUNNING),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        RelayDashboardScreen(
            captureState = captureState,
            access = access,
            onToggleCapture = {
                scope.launch {
                    captureRepository.setCaptureMode(
                        if (captureState.mode == CaptureMode.RUNNING) CaptureMode.PAUSED else CaptureMode.RUNNING,
                    )
                }
            },
            onNotificationAccess = openNotificationAccess,
            onAccessibilityAccess = openAccessibilityAccess,
            onUsageAccess = openUsageAccess,
        )

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            OutlinedButton(
                onClick = {
                    val shared = runCatching { RelayReplayExporter.replayLatestAndShare(context.applicationContext) }
                        .getOrDefault(false)
                    if (!shared) {
                        Toast.makeText(context, "No forensic evidence is available to replay yet", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("Replay latest evidence") }

            Button(
                onClick = {
                    if (testRunning) return@Button
                    scope.launch {
                        testRunning = true
                        try {
                            val snapshot = RelayRuntimeDiagnostics.state.value
                            val report = withContext(Dispatchers.IO) {
                                val base = RelaySystemTestRunner.run(
                                    context.applicationContext,
                                    RelaySystemTestInput(
                                        captureRunning = captureState.mode == CaptureMode.RUNNING,
                                        notificationAccess = access.notificationAccess,
                                        accessibilityAccess = access.accessibilityAccess,
                                        usageAccess = access.usageAccess,
                                        diagnostics = snapshot,
                                    ),
                                )
                                RelayAppWideSystemTest.augment(
                                    context = context.applicationContext,
                                    base = base,
                                    database = database,
                                    access = access,
                                    captureState = captureState,
                                )
                            }
                            RelaySystemTestExporter.share(
                                context.applicationContext,
                                report,
                                RelayRuntimeDiagnostics.state.value,
                            )
                        } finally {
                            testRunning = false
                        }
                    }
                },
            ) { Text(if (testRunning) "Testing…" else "Full system test") }
        }
    }
}
