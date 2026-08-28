package com.kareem.secondbrain.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kareem.secondbrain.ai.embedding.EmbeddingModelInstaller
import com.kareem.secondbrain.ai.embedding.EmbeddingModelStatus
import com.kareem.secondbrain.ai.gemini.GeminiApiKeyStore
import com.kareem.secondbrain.capture.android.health.CaptureAccessChecker
import com.kareem.secondbrain.capture.android.health.InstalledAppCatalog
import com.kareem.secondbrain.capture.android.health.InstalledAppEntry
import com.kareem.secondbrain.capture.android.voice.VoiceRecordingService
import com.kareem.secondbrain.core.model.CaptureAccessSnapshot
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.core.model.CaptureState
import com.kareem.secondbrain.core.model.SearchRequest
import com.kareem.secondbrain.core.model.TimelineRequest
import com.kareem.secondbrain.core.privacy.DefaultCapturePolicy
import com.kareem.secondbrain.domain.AskRepository
import com.kareem.secondbrain.domain.AssetRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.CaptureResult
import com.kareem.secondbrain.domain.EnrichmentScheduler
import com.kareem.secondbrain.domain.MemoryRepository
import com.kareem.secondbrain.domain.MemorySearchRepository
import com.kareem.secondbrain.feature.ask.AskScreen
import com.kareem.secondbrain.feature.capture.CaptureScreen
import com.kareem.secondbrain.feature.search.SearchScreen
import com.kareem.secondbrain.feature.settings.AppPoliciesScreen
import com.kareem.secondbrain.feature.settings.AppPolicyItem
import com.kareem.secondbrain.feature.settings.SettingsScreen
import com.kareem.secondbrain.feature.timeline.TimelineScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var memorySearchRepository: MemorySearchRepository
    @Inject lateinit var askRepository: AskRepository
    @Inject lateinit var policyRepository: CapturePolicyRepository
    @Inject lateinit var accessChecker: CaptureAccessChecker
    @Inject lateinit var installedAppCatalog: InstalledAppCatalog
    @Inject lateinit var assetRepository: AssetRepository
    @Inject lateinit var enrichmentScheduler: EnrichmentScheduler
    @Inject lateinit var embeddingModelInstaller: EmbeddingModelInstaller
    @Inject lateinit var geminiApiKeyStore: GeminiApiKeyStore

    private val accessSnapshotState = mutableStateOf(CaptureAccessSnapshot(false, false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accessSnapshotState.value = accessChecker.snapshot()
        setContent {
            SecondBrainRoot(
                captureRepository = captureRepository,
                memoryRepository = memoryRepository,
                memorySearchRepository = memorySearchRepository,
                askRepository = askRepository,
                policyRepository = policyRepository,
                installedAppCatalog = installedAppCatalog,
                assetRepository = assetRepository,
                enrichmentScheduler = enrichmentScheduler,
                embeddingModelInstaller = embeddingModelInstaller,
                geminiApiKeyStore = geminiApiKeyStore,
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
    memorySearchRepository: MemorySearchRepository,
    askRepository: AskRepository,
    policyRepository: CapturePolicyRepository,
    installedAppCatalog: InstalledAppCatalog,
    assetRepository: AssetRepository,
    enrichmentScheduler: EnrichmentScheduler,
    embeddingModelInstaller: EmbeddingModelInstaller,
    geminiApiKeyStore: GeminiApiKeyStore,
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
    val cloudAiEnabled by geminiApiKeyStore.observeCloudEnabled().collectAsState()
    val installedApps by produceState(initialValue = emptyList<InstalledAppEntry>(), installedAppCatalog) {
        value = withContext(Dispatchers.Default) { installedAppCatalog.launchableApps() }
    }
    var embeddingModelStatus by remember { mutableStateOf(EmbeddingModelStatus(false)) }
    var embeddingModelMessage by remember { mutableStateOf<String?>(null) }
    var geminiKeyConfigured by remember { mutableStateOf(false) }
    var geminiKeyMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(embeddingModelInstaller) {
        runCatching { embeddingModelInstaller.status() }
            .onSuccess { embeddingModelStatus = it }
            .onFailure { embeddingModelMessage = it.message ?: "Unable to read embedding model status" }
    }
    LaunchedEffect(geminiApiKeyStore) {
        geminiKeyConfigured = runCatching { geminiApiKeyStore.hasKey() }.getOrDefault(false)
    }

    val context = LocalContext.current
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startVoiceService() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceRecordingService::class.java).setAction(VoiceRecordingService.ACTION_START),
        )
    }
    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceService()
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val asset = assetRepository.importContentUri(uri.toString(), mimeType = context.contentResolver.getType(uri))
            val result = captureRepository.ingest(CaptureCommand.Image(Instant.now(), assetId = asset.id, userSaved = true))
            if (result is CaptureResult.Stored) enrichmentScheduler.enqueueOcr(result.eventId, asset.id)
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val asset = assetRepository.importContentUri(uri.toString(), mimeType = context.contentResolver.getType(uri))
            captureRepository.ingest(CaptureCommand.File(Instant.now(), assetId = asset.id, displayName = uri.lastPathSegment))
        }
    }
    val embeddingModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            embeddingModelMessage = "Installing model…"
            runCatching { embeddingModelInstaller.install(uri.toString()) }
                .onSuccess { status ->
                    embeddingModelStatus = status
                    embeddingModelMessage = "Model installed • SHA-256 ${status.sha256?.take(12) ?: "verified"}…"
                }
                .onFailure { error ->
                    embeddingModelMessage = error.message ?: "Model installation failed"
                }
        }
    }
    val toggleCapture = {
        scope.launch {
            captureRepository.setCaptureMode(
                if (captureState.mode == CaptureMode.RUNNING) CaptureMode.PAUSED else CaptureMode.RUNNING,
            )
        }
        Unit
    }

    Scaffold(
        floatingActionButton = {
            if (current != "capture" && current != "appPolicies") {
                FloatingActionButton(onClick = { navController.navigate("capture") }) { Text("+") }
            }
        },
        bottomBar = {
            if (current != "appPolicies" && current != "capture") {
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
            composable("search") {
                SearchScreen(
                    onSearch = { query -> memorySearchRepository.search(SearchRequest(query = query)) },
                )
            }
            composable("ask") {
                AskScreen(onAsk = { question -> askRepository.ask(question) })
            }
            composable("settings") {
                SettingsScreen(
                    captureState = captureState,
                    access = access,
                    embeddingModelInstalled = embeddingModelStatus.installed,
                    embeddingModelSizeBytes = embeddingModelStatus.sizeBytes,
                    embeddingModelMessage = embeddingModelMessage,
                    geminiKeyConfigured = geminiKeyConfigured,
                    cloudAiEnabled = cloudAiEnabled,
                    geminiKeyMessage = geminiKeyMessage,
                    onToggleCapture = toggleCapture,
                    onNotificationAccess = openNotificationAccess,
                    onAccessibilityAccess = openAccessibilityAccess,
                    onUsageAccess = openUsageAccess,
                    onMicrophoneAccess = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onAppPolicies = { navController.navigate("appPolicies") },
                    onInstallEmbeddingModel = { embeddingModelLauncher.launch("*/*") },
                    onSaveGeminiApiKey = { apiKey ->
                        scope.launch {
                            geminiKeyMessage = "Saving key…"
                            runCatching { geminiApiKeyStore.save(apiKey) }
                                .onSuccess {
                                    geminiKeyConfigured = true
                                    geminiKeyMessage = "Gemini key saved securely. Cloud synthesis remains off until enabled."
                                }
                                .onFailure { error -> geminiKeyMessage = error.message ?: "Unable to save Gemini key" }
                        }
                    },
                    onClearGeminiApiKey = {
                        scope.launch {
                            runCatching { geminiApiKeyStore.clear() }
                                .onSuccess {
                                    geminiKeyConfigured = false
                                    geminiKeyMessage = "Gemini key removed and cloud synthesis disabled."
                                }
                                .onFailure { error -> geminiKeyMessage = error.message ?: "Unable to remove Gemini key" }
                        }
                    },
                    onCloudAiEnabledChanged = { enabled ->
                        scope.launch {
                            runCatching { geminiApiKeyStore.setCloudEnabled(enabled) }
                                .onSuccess {
                                    geminiKeyMessage = if (enabled) {
                                        "Cloud synthesis enabled globally. Per-app Allow cloud AI policies still apply."
                                    } else {
                                        "Cloud synthesis disabled. Ask will stay evidence-only."
                                    }
                                }
                                .onFailure { error -> geminiKeyMessage = error.message ?: "Unable to change cloud AI setting" }
                        }
                    },
                )
            }
            composable("capture") {
                CaptureScreen(
                    onAddNote = { text -> scope.launch { captureRepository.ingest(CaptureCommand.Note(Instant.now(), text = text)) } },
                    onAddLink = { url -> scope.launch { captureRepository.ingest(CaptureCommand.Link(Instant.now(), url = url)) } },
                    onStartVoice = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoiceService()
                        } else {
                            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopVoice = {
                        context.startService(
                            Intent(context, VoiceRecordingService::class.java).setAction(VoiceRecordingService.ACTION_STOP),
                        )
                    },
                    onPickImage = { imageLauncher.launch("image/*") },
                    onPickFile = { fileLauncher.launch("*/*") },
                    onBack = { navController.popBackStack() },
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
