package com.kareem.secondbrain.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kareem.secondbrain.capture.android.health.CaptureAccessChecker
import com.kareem.secondbrain.capture.android.health.InstalledAppCatalog
import com.kareem.secondbrain.capture.android.health.InstalledAppEntry
import com.kareem.secondbrain.core.model.CaptureAccessSnapshot
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import com.kareem.secondbrain.core.model.TimelineRequest
import com.kareem.secondbrain.core.privacy.DefaultCapturePolicy
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.MemoryRepository
import com.kareem.secondbrain.feature.ask.AskScreen
import com.kareem.secondbrain.feature.search.SearchScreen
import com.kareem.secondbrain.feature.settings.AppPoliciesScreen
import com.kareem.secondbrain.feature.settings.AppPolicyItem
import com.kareem.secondbrain.feature.settings.SettingsScreen
import com.kareem.secondbrain.feature.timeline.TimelineScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var policyRepository: CapturePolicyRepository
    @Inject lateinit var accessChecker: CaptureAccessChecker
    @Inject lateinit var installedAppCatalog: InstalledAppCatalog

    private val accessSnapshotState = mutableStateOf(CaptureAccessSnapshot(false, false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accessSnapshotState.value = accessChecker.snapshot()
        setContent {
            SecondBrainRoot(
                captureRepository = captureRepository,
                memoryRepository = memoryRepository,
                policyRepository = policyRepository,
                installedAppCatalog = installedAppCatalog,
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

private data class Destination(val route: String, val label: String)
private val destinations = listOf(
    Destination("timeline", "Timeline"),
    Destination("search", "Search"),
    Destination("ask", "Ask"),
    Destination("settings", "Settings"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecondBrainRoot(
    captureRepository: CaptureRepository,
    memoryRepository: MemoryRepository,
    policyRepository: CapturePolicyRepository,
    installedAppCatalog: InstalledAppCatalog,
    access: CaptureAccessSnapshot,
    openNotificationAccess: () -> Unit,
    openAccessibilityAccess: () -> Unit,
    openUsageAccess: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val scope = rememberCoroutineScope()
    val captureState by captureRepository.observeCaptureState().collectAsState(initial = CaptureState(CaptureMode.RUNNING))
    val memories by memoryRepository.observeTimeline(TimelineRequest()).collectAsState(initial = emptyList())
    val persistedPolicies by policyRepository.observePolicies().collectAsState(initial = emptyList())
    val installedApps by produceState(initialValue = emptyList<InstalledAppEntry>(), installedAppCatalog) {
        value = withContext(Dispatchers.Default) { installedAppCatalog.launchableApps() }
    }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val toggleCapture = {
        scope.launch {
            captureRepository.setCaptureMode(
                if (captureState.mode == CaptureMode.RUNNING) CaptureMode.PAUSED else CaptureMode.RUNNING,
            )
        }
        Unit
    }

    Scaffold(
        bottomBar = {
            if (current != "appPolicies") {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = current == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(destination.label.take(1)) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "timeline",
            modifier = Modifier.padding(padding),
        ) {
            composable("timeline") {
                TimelineScreen(
                    memories = memories,
                    captureState = captureState,
                    onToggleCapture = toggleCapture,
                )
            }
            composable("search") { SearchScreen() }
            composable("ask") { AskScreen() }
            composable("settings") {
                SettingsScreen(
                    captureState = captureState,
                    access = access,
                    onToggleCapture = toggleCapture,
                    onNotificationAccess = openNotificationAccess,
                    onAccessibilityAccess = openAccessibilityAccess,
                    onUsageAccess = openUsageAccess,
                    onMicrophoneAccess = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onAppPolicies = { navController.navigate("appPolicies") },
                )
            }
            composable("appPolicies") {
                val persistedByPackage = persistedPolicies.associateBy { it.packageName }
                val appLabels = installedApps.associate { it.packageName to it.label }.toMutableMap()
                memories.mapNotNull { it.sourcePackage }.forEach { packageName ->
                    appLabels.putIfAbsent(packageName, packageName)
                }
                persistedPolicies.forEach { policy -> appLabels.putIfAbsent(policy.packageName, policy.packageName) }
                val items = appLabels.entries
                    .sortedBy { it.value.lowercase() }
                    .map { (packageName, label) ->
                        AppPolicyItem(
                            label = label,
                            policy = persistedByPackage[packageName] ?: DefaultCapturePolicy.forPackage(packageName),
                        )
                    }
                AppPoliciesScreen(
                    items = items,
                    onPolicyChanged = { policy -> scope.launch { policyRepository.set(policy) } },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
