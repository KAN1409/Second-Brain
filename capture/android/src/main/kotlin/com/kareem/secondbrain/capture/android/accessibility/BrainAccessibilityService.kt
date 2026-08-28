package com.kareem.secondbrain.capture.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.AppSessionRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureHealthRepository
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class BrainAccessibilityService : AccessibilityService() {
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var policyRepository: CapturePolicyRepository
    @Inject lateinit var appSessions: AppSessionRepository
    @Inject lateinit var healthRepository: CaptureHealthRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: Job? = null
    @Volatile private var captureRunning = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || !captureRunning) return
            serviceScope.launch {
                val at = Instant.now()
                appSessions.closeOpenSession(at)?.let { previous ->
                    captureRepository.ingest(CaptureCommand.AppActivity(at, previous.packageName, enteredForeground = false))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            captureRepository.observeCaptureState().collectLatest { state ->
                captureRunning = state.mode == CaptureMode.RUNNING
            }
        }
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch { healthRepository.setAccessibilityConnected(true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!captureRunning || event == null) return
        val sourcePackage = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val now = Instant.now()
        if (sourcePackage == packageName) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                serviceScope.launch {
                    appSessions.closeOpenSession(now)?.let { previous ->
                        captureRepository.ingest(
                            CaptureCommand.AppActivity(now, previous.packageName, enteredForeground = false),
                        )
                    }
                }
            }
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val foregroundPackage = resolveForegroundApplicationPackage(sourcePackage)
            serviceScope.launch { trackForegroundPackage(foregroundPackage, now) }
        }

        if (event.eventType !in SCREEN_EVENT_TYPES) return
        val eventType = event.eventType
        val eventClass = event.className?.toString()
        val contentChangeTypes = event.contentChangeTypes

        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(SCREEN_DEBOUNCE_MS)
            if (!captureRunning) return@launch
            val appWindow = foregroundApplicationWindow() ?: return@launch
            val root = appWindow.root ?: return@launch
            val activePackage = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return@launch
            if (activePackage == packageName) return@launch
            val policy = policyRepository.get(activePackage)
            if (!policy.accessibility) return@launch

            val extracted = AccessibleTextExtractor.extract(root)
            if (extracted.text.isBlank()) return@launch
            val metadata = JSONObject().apply {
                put("eventType", eventType)
                put("className", eventClass ?: JSONObject.NULL)
                put("contentChangeTypes", contentChangeTypes)
                put("visitedNodes", extracted.visitedNodes)
                put("passwordNodesSkipped", extracted.passwordNodesSkipped)
                put("source", "accessibility_tree")
            }.toString()

            captureRepository.ingest(
                CaptureCommand.Screen(
                    occurredAt = Instant.now(),
                    packageName = activePackage,
                    accessibleText = extracted.text,
                    metadataJson = metadata,
                ),
            )
        }
    }

    private fun foregroundApplicationWindow(): AccessibilityWindowInfo? =
        windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer },
            )
            .firstOrNull { window ->
                window.root?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { it != packageName } == true
            }

    private fun resolveForegroundApplicationPackage(fallback: String): String =
        foregroundApplicationWindow()
            ?.root
            ?.packageName
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?: fallback

    private suspend fun trackForegroundPackage(packageName: String, at: Instant) {
        val policy = policyRepository.get(packageName)
        if (!policy.usage) {
            val previous = appSessions.closeOpenSession(at)
            previous?.let {
                captureRepository.ingest(CaptureCommand.AppActivity(at, it.packageName, enteredForeground = false))
            }
            return
        }

        val transition = appSessions.switchForeground(packageName, at) ?: return
        transition.previous?.let { previous ->
            captureRepository.ingest(CaptureCommand.AppActivity(at, previous.packageName, enteredForeground = false))
        }
        captureRepository.ingest(CaptureCommand.AppActivity(at, packageName, enteredForeground = true))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        debounceJob?.cancel()
        runCatching { unregisterReceiver(screenOffReceiver) }
        serviceScope.launch { healthRepository.setAccessibilityConnected(false) }
            .invokeOnCompletion { serviceScope.cancel() }
        super.onDestroy()
    }

    private companion object {
        const val SCREEN_DEBOUNCE_MS = 750L
        val SCREEN_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )
    }
}
