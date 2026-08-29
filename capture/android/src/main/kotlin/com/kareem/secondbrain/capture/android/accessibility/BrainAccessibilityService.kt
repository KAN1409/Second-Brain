package com.kareem.secondbrain.capture.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.view.Display
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.kareem.secondbrain.capture.android.intelligence.RelayIntelligenceV3
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.AppSessionRepository
import com.kareem.secondbrain.domain.CaptureCommand
import com.kareem.secondbrain.domain.CaptureHealthRepository
import com.kareem.secondbrain.domain.CapturePolicyRepository
import com.kareem.secondbrain.domain.CaptureRepository
import com.kareem.secondbrain.domain.EnrichmentScheduler
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
import java.io.File
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class BrainAccessibilityService : AccessibilityService() {
    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var policyRepository: CapturePolicyRepository
    @Inject lateinit var appSessions: AppSessionRepository
    @Inject lateinit var healthRepository: CaptureHealthRepository
    @Inject lateinit var enrichmentScheduler: EnrichmentScheduler

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: Job? = null
    @Volatile private var captureRunning = false
    private val lastScreenshotOcrAt = mutableMapOf<String, Long>()
    private lateinit var intelligence: RelayIntelligenceV3
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || !captureRunning) return
            serviceScope.launch {
                val at = Instant.now()
                appSessions.closeOpenSession(at)?.let { previous ->
                    runCatching { intelligence.observeAppActivity(previous.packageName, false, at.toEpochMilli()) }
                    captureRepository.ingest(CaptureCommand.AppActivity(at, previous.packageName, enteredForeground = false))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        intelligence = RelayIntelligenceV3.forContext(applicationContext)
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
                        runCatching { intelligence.observeAppActivity(previous.packageName, false, now.toEpochMilli()) }
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
            if (extracted.text.length < 20) {
                if (policy.ocr && extracted.passwordNodesSkipped == 0) {
                    scheduleScreenshotOcr(activePackage)
                }
                return@launch
            }
            val capturedAt = Instant.now()
            val v3 = runCatching {
                intelligence.observeScreen(
                    packageName = activePackage,
                    accessibleText = extracted.text,
                    className = eventClass,
                    eventType = eventType,
                    occurredAtEpochMs = capturedAt.toEpochMilli(),
                )
            }.getOrNull()
            val metadata = JSONObject().apply {
                put("eventType", eventType)
                put("className", eventClass ?: JSONObject.NULL)
                put("contentChangeTypes", contentChangeTypes)
                put("visitedNodes", extracted.visitedNodes)
                put("passwordNodesSkipped", extracted.passwordNodesSkipped)
                put("source", "accessibility_tree")
                if (v3 != null) put("relay_intelligence_v3", v3)
            }.toString()

            captureRepository.ingest(
                CaptureCommand.Screen(
                    occurredAt = capturedAt,
                    packageName = activePackage,
                    accessibleText = extracted.text,
                    metadataJson = metadata,
                ),
            )
        }
    }

    private fun scheduleScreenshotOcr(activePackage: String) {
        val now = System.currentTimeMillis()
        val previous = lastScreenshotOcrAt[activePackage] ?: 0L
        if (now - previous < SCREENSHOT_OCR_THROTTLE_MS) return
        lastScreenshotOcrAt[activePackage] = now

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardwareBuffer.close()
                    } ?: return
                    val dir = File(cacheDir, "temporary-ocr").apply { mkdirs() }
                    val file = File(dir, "screen-${activePackage.hashCode()}-$now.png")
                    try {
                        file.outputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    } catch (_: Throwable) {
                        file.delete()
                        return
                    } finally {
                        bitmap.recycle()
                    }
                    serviceScope.launch {
                        enrichmentScheduler.enqueueTemporaryScreenshotOcr(
                            packageName = activePackage,
                            occurredAt = Instant.ofEpochMilli(now),
                            absolutePath = file.absolutePath,
                        )
                    }
                }

                override fun onFailure(errorCode: Int) = Unit
            },
        )
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
                runCatching { intelligence.observeAppActivity(it.packageName, false, at.toEpochMilli()) }
                captureRepository.ingest(CaptureCommand.AppActivity(at, it.packageName, enteredForeground = false))
            }
            return
        }

        val transition = appSessions.switchForeground(packageName, at) ?: return
        transition.previous?.let { previous ->
            runCatching { intelligence.observeAppActivity(previous.packageName, false, at.toEpochMilli()) }
            captureRepository.ingest(CaptureCommand.AppActivity(at, previous.packageName, enteredForeground = false))
        }
        runCatching { intelligence.observeAppActivity(packageName, true, at.toEpochMilli()) }
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
        const val SCREENSHOT_OCR_THROTTLE_MS = 5_000L
        val SCREEN_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )
    }
}
