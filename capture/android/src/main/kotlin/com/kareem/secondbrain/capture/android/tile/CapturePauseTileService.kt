package com.kareem.secondbrain.capture.android.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kareem.secondbrain.core.model.CaptureMode
import com.kareem.secondbrain.domain.CaptureRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CapturePauseTileService : TileService() {
    @Inject lateinit var captureRepository: CaptureRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    @Volatile private var mode: CaptureMode = CaptureMode.RUNNING

    override fun onStartListening() {
        super.onStartListening()
        observeJob?.cancel()
        observeJob = scope.launch {
            captureRepository.observeCaptureState().collectLatest { state ->
                mode = state.mode
                render(state.mode)
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val next = if (mode == CaptureMode.RUNNING) CaptureMode.PAUSED else CaptureMode.RUNNING
        scope.launch { captureRepository.setCaptureMode(next) }
    }

    private fun render(mode: CaptureMode) {
        qsTile?.apply {
            state = if (mode == CaptureMode.RUNNING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Second Brain"
            subtitle = if (mode == CaptureMode.RUNNING) "Capture on" else "Capture paused"
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
