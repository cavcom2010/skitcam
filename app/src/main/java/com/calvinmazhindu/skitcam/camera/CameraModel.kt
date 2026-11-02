package com.calvinmazhindu.skitcam.camera

import android.app.Application
import android.net.Uri
import androidx.camera.core.*
import androidx.core.content.edit
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calvinmazhindu.skitcam.recording.CameraLens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CameraModel(application: Application) : AndroidViewModel(application) {
    private val preferenceWrites = Mutex()
    private val prefs = application.getSharedPreferences("camera_preferences", 0)
    var loaded by mutableStateOf(false); private set
    var choice by mutableStateOf(CameraChoice()); private set
    var committed by mutableStateOf(CameraChoice()); private set
    var switching by mutableStateOf(false); private set
    var streaming by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var grid by mutableStateOf(false); private set
    var timer by mutableIntStateOf(0); private set
    var acknowledgedExport by mutableStateOf<String?>(null); private set
    var latestUri by mutableStateOf<String?>(null); private set
    var latestType by mutableStateOf("image/jpeg"); private set
    private var latestTime = 0L
    var zoom by mutableFloatStateOf(1f); private set
    var minZoom by mutableFloatStateOf(1f); private set
    var maxZoom by mutableFloatStateOf(1f); private set
    var exposure by mutableIntStateOf(0); private set
    var exposureRange by mutableStateOf(0..0); private set
    var exposureStep by mutableFloatStateOf(0f); private set
    var resolution by mutableStateOf(""); private set
    var hasOtherLens by mutableStateOf(false)
    var focus by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null); private set
    var focusResult by mutableStateOf("Focusing"); private set
    private var camera: Camera? = null
    private var generation = 0
    private var focusToken = 0
    private var initialized by mutableStateOf(false)
    init { viewModelScope.launch {
        withContext(Dispatchers.IO) {
            committed = runCatching { CameraChoice(CameraMode.valueOf(prefs.getString("mode", CameraMode.SkitVideo.name)!!),
                CameraLens.valueOf(prefs.getString("lens_${prefs.getString("mode", CameraMode.SkitVideo.name)}", CameraLens.Rear.name)!!)) }.getOrDefault(CameraChoice())
            grid = prefs.getBoolean("grid", false)
            timer = prefs.getInt("timer", 0).takeIf { it in listOf(0,3,10) } ?: 0
            acknowledgedExport = prefs.getString("acknowledged_export", null)
            latestTime = prefs.getLong("media_time",0L)
            latestUri = prefs.getString("media_uri", null)
            latestType = prefs.getString("media_type", "image/jpeg")!!
        }
        loaded = true
    } }
    fun initialize(snapshot: com.calvinmazhindu.skitcam.recording.RecordingSnapshot, needsExport: Boolean) {
        if (initialized || !loaded) return
        choice = CameraPolicy.initial(committed, snapshot, needsExport)
        committed = choice
        initialized = true
    }
    val ready get() = loaded && initialized
    fun select(mode: CameraMode = choice.mode, lens: CameraLens? = null, allowed: Boolean) {
        if (!allowed || switching || !streaming) return
        val desiredLens = lens ?: if (mode == choice.mode) choice.lens else runCatching {
            CameraLens.valueOf(prefs.getString("lens_${mode.name}", CameraLens.Rear.name)!!)
        }.getOrDefault(CameraLens.Rear)
        val next = CameraChoice(mode, desiredLens)
        if (next == choice) return
        switching = true; streaming = false; message = null; choice = next
    }
    fun bound(value: Camera, size: String) {
        camera = value; generation++; resolution = size
        val state = value.cameraInfo.zoomState.value
        minZoom = state?.minZoomRatio ?: 1f; maxZoom = state?.maxZoomRatio ?: 1f
        zoom = state?.zoomRatio ?: 1f
        val ev = value.cameraInfo.exposureState
        exposureRange = ev.exposureCompensationRange.let { it.lower..it.upper }
        exposureStep = ev.exposureCompensationStep.toFloat(); exposure = ev.exposureCompensationIndex
        focus = null
    }
    fun streamed() {
        if (camera == null || streaming) return
        streaming = true
        if (switching) {
            committed = choice
            val selected = choice
            viewModelScope.launch { val saved = preferenceWrites.withLock { withContext(Dispatchers.IO) {
                prefs.edit().putString("mode", selected.mode.name).putString("lens_${selected.mode.name}", selected.lens.name).commit()
            } }; switching = false; if (!saved) message = "Camera changed, but the preference could not be saved." }
        }
    }
    fun failed(reason: String) {
        message = reason; streaming = false
        if (switching) { choice = committed; switching = false }
    }
    fun idle() { streaming = false; focusToken++; focus = null }
    fun released() { camera = null; generation++; streaming = false; focus = null }
    val observedZoom get() = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    fun adjustZoom(ratio: Float) {
        val current = camera ?: return
        val requested = CameraPolicy.zoom(ratio, minZoom, maxZoom)
        zoom = requested
        val token = generation
        val future = current.cameraControl.setZoomRatio(requested)
        future.addListener({ if (generation == token) runCatching { future.get() }.onFailure {
            zoom = current.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            message = "Zoom adjustment unavailable."
        } }, androidx.core.content.ContextCompat.getMainExecutor(getApplication()))
    }
    fun adjustExposure(index: Int) {
        val current = camera ?: return
        val token = generation
        val future = current.cameraControl.setExposureCompensationIndex(index.coerceIn(exposureRange))
        future.addListener({ if (token == generation) runCatching { exposure = future.get() }.onFailure {
            message = "Exposure adjustment unavailable."
        } }, androidx.core.content.ContextCompat.getMainExecutor(getApplication()))
    }
    fun focus(view: PreviewView, point: androidx.compose.ui.geometry.Offset) {
        val current = camera ?: return
        val action = FocusMeteringAction.Builder(view.meteringPointFactory.createPoint(point.x, point.y))
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        if (!current.cameraInfo.isFocusMeteringSupported(action)) { message = "Tap focus unavailable on this camera."; return }
        focus = point; focusResult = "Focusing"
        val token = ++focusToken; val binding = generation
        val future = current.cameraControl.startFocusAndMetering(action)
        future.addListener({
            if (binding == generation && token == focusToken) {
                focusResult = runCatching { if (future.get().isFocusSuccessful) "Focused" else "Focus not confirmed" }.getOrDefault("Focus unavailable")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(getApplication()))
        viewModelScope.launch { kotlinx.coroutines.delay(2500); if (token == focusToken) focus = null }
    }
    fun grid(value: Boolean) { grid = value; prefs.edit { putBoolean("grid", value) } }
    fun timer(value: Int) { require(value in listOf(0,3,10)); timer = value; prefs.edit { putInt("timer", value) } }
    fun acknowledge(id: String) { acknowledgedExport = id; prefs.edit { putString("acknowledged_export", id) } }
    fun media(uri: Uri, type: String, createdAtMs: Long) {
        if(createdAtMs < latestTime) return
        latestTime = createdAtMs
        latestUri = uri.toString(); latestType = type
        prefs.edit { putString("media_uri", uri.toString()); putString("media_type", type); putLong("media_time",createdAtMs) }
    }
}
