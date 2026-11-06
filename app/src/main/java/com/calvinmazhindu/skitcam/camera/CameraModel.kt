package com.calvinmazhindu.skitcam.camera

import android.app.Application
import android.net.Uri
import androidx.camera.core.*
import androidx.core.content.edit
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.calvinmazhindu.skitcam.recording.CameraLens
import com.calvinmazhindu.skitcam.photo.PhotoAspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CameraModel(application: Application) : AndroidViewModel(application) {
    val framing = FramingDiagnostics()
    val video = com.calvinmazhindu.skitcam.video.VideoController(application)
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
    var photoAspectRatio by mutableStateOf(PhotoAspectRatio.FourThree); private set
    var receipt by mutableStateOf<SaveReceipt?>(null); private set
    var savedNotice by mutableStateOf<SavedVideo?>(null); private set
    var boundInfo by mutableStateOf<BoundCameraInfo?>(null); private set
    var acknowledgedExport by mutableStateOf<String?>(null); private set
    var latestUri by mutableStateOf<String?>(null); private set
    var latestType by mutableStateOf("image/jpeg"); private set
    private var latestTime = 0L
    var zoom by mutableFloatStateOf(1f); private set
    var zoomReady by mutableStateOf(false); private set
    var videoAspect by mutableStateOf<Float?>(null); private set
    var previewDisplayRotation by mutableStateOf<Int?>(null); private set
    private var zoomChoice: CameraChoice? = null
    private var requestedZoom = 1f
    private var zoomToken = 0
    private val zoomObserver = Observer<ZoomState> { state ->
        zoom=state.zoomRatio;minZoom=state.minZoomRatio;maxZoom=state.maxZoomRatio
    }
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
    private var lastMeteringPoint: MeteringPoint? = null
    private var initialized by mutableStateOf(false)
    init { viewModelScope.launch {
        withContext(Dispatchers.IO) {
            committed = runCatching { CameraChoice(CameraMode.valueOf(prefs.getString("mode", CameraMode.SkitVideo.name)!!),
                CameraLens.valueOf(prefs.getString("lens_${prefs.getString("mode", CameraMode.SkitVideo.name)}", CameraLens.Rear.name)!!)) }.getOrDefault(CameraChoice())
            grid = prefs.getBoolean("grid", false)
            timer = prefs.getInt("timer", 0).takeIf { it in listOf(0,3,10) } ?: 0
            photoAspectRatio = PhotoAspectRatio.restored(prefs.getString("photo_aspect_ratio",null))
            acknowledgedExport = prefs.getString("acknowledged_export", null)
            receipt = runCatching { prefs.getString("video_return_v1",null)?.let(SaveReceipt::decode) }.getOrNull()
            latestTime = prefs.getLong("media_time",0L)
            latestUri = prefs.getString("media_uri", null)
            latestType = prefs.getString("media_type", "image/jpeg")!!
        }
        loaded = true
    } }
    override fun onCleared() { camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver);video.close();super.onCleared() }
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
    fun bound(value: Camera, size: String, info: BoundCameraInfo? = null, rotation: Int = 0,
        onZoomApplied: (String?) -> Unit = {}) {
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        camera = value; generation++; resolution = size; boundInfo=info
        videoAspect=info?.takeUnless { it.photo }?.let { ViewfinderGeometry.aspect(it.width,it.height,rotation) }
        val state = value.cameraInfo.zoomState.value
        minZoom = state?.minZoomRatio ?: 1f; maxZoom = state?.maxZoomRatio ?: 1f
        requestedZoom=CameraPolicy.zoom(CameraPolicy.zoomForBinding(zoomChoice,choice,requestedZoom),minZoom,maxZoom)
        zoomChoice=choice
        zoomReady=false
        value.cameraInfo.zoomState.observeForever(zoomObserver)
        requestZoom(requestedZoom) { failure ->
            zoomReady=failure==null
            onZoomApplied(failure)
        }
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
    /** Display-only change on the same binding. Keep zoom, EV, metering and AF hold. */
    fun rotated(sensorRotation: Int, displayRotation: Int) {
        videoAspect=boundInfo?.takeUnless { it.photo }?.let { ViewfinderGeometry.aspect(it.width,it.height,sensorRotation) }
        previewDisplayRotation=displayRotation
    }
    fun released() {
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        framing.release();video.release();boundInfo=null;videoAspect=null;previewDisplayRotation=null;zoomReady=false
        lastMeteringPoint=null;camera=null;generation++;zoomToken++;streaming=false;focus=null
    }
    val observedZoom get() = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    fun adjustZoom(ratio: Float) {
        requestZoom(ratio)
    }
    private fun requestZoom(ratio: Float, onApplied: ((String?) -> Unit)? = null) {
        val current = camera ?: return
        val requested = CameraPolicy.zoom(ratio, minZoom, maxZoom)
        requestedZoom=requested
        val binding = generation
        val token = ++zoomToken
        val future = current.cameraControl.setZoomRatio(requested)
        future.addListener({ if (generation == binding && zoomToken==token) {
            val failure=runCatching { future.get() }.exceptionOrNull()
            zoom=current.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            if(failure!=null) {
                requestedZoom=zoom
                message="Zoom adjustment unavailable. Actual zoom: ${zoom}×."
            }
            onApplied?.invoke(if(failure==null) null else message)
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
        val metering=view.meteringPointFactory.createPoint(point.x, point.y)
        lastMeteringPoint=metering
        val token = ++focusToken
        video.holdFocus(false)
        val action = FocusMeteringAction.Builder(metering)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        if (!current.cameraInfo.isFocusMeteringSupported(action)) { message = "Tap focus unavailable on this camera."; return }
        focus = point; focusResult = "Focusing"
        val binding = generation
        val future = current.cameraControl.startFocusAndMetering(action)
        future.addListener({
            if (binding == generation && token == focusToken) {
                focusResult = runCatching { if (future.get().isFocusSuccessful) "Focused" else "Focus not confirmed" }.getOrDefault("Focus unavailable")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(getApplication()))
        viewModelScope.launch { kotlinx.coroutines.delay(2500); if (token == focusToken) focus = null }
    }
    fun toggleFocusHold() {
        val current=camera ?: return
        if(!video.autofocusSupported) return
        val request=++focusToken
        if(video.focusHold) { video.holdFocus(false);current.cameraControl.cancelFocusAndMetering();return }
        val point=lastMeteringPoint ?: SurfaceOrientedMeteringPointFactory(1f,1f).createPoint(.5f,.5f)
        val action=FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
        val token=generation
        val future=current.cameraControl.startFocusAndMetering(action)
        future.addListener({ if(token==generation && request==focusToken) runCatching {
            val success=future.get().isFocusSuccessful
            if(!success) current.cameraControl.cancelFocusAndMetering()
            video.holdFocus(success);message=if(success) "Focus held. Auto releases it." else "Focus hold was not confirmed."
        }.onFailure { video.holdFocus(false);current.cameraControl.cancelFocusAndMetering();message="Focus hold unavailable; Auto restored." } },androidx.core.content.ContextCompat.getMainExecutor(getApplication()))
    }
    fun grid(value: Boolean) { grid = value; prefs.edit { putBoolean("grid", value) } }
    fun photoAspect(value: PhotoAspectRatio, allowed: Boolean) {
        if(!allowed || choice.mode!=CameraMode.Photo || switching || !streaming || value==photoAspectRatio) return
        streaming=false;message=null;photoAspectRatio=value
        prefs.edit { putString("photo_aspect_ratio",value.name) }
    }
    fun timer(value: Int) { require(value in listOf(0,3,10)); timer = value; prefs.edit { putInt("timer", value) } }
    fun exportHandled(id: String) = SaveReturnPolicy.handled(id,receipt,acknowledgedExport)
    fun published(video: SavedVideo, lens: CameraLens) {
        if(!SaveReturnPolicy.accepts(video,receipt)) return
        val already=exportHandled(video.sessionId)
        receipt=SaveReceipt(video,already)
        if(video.createdAtMs>=latestTime) {
            latestTime=video.createdAtMs;latestUri=video.uri;latestType="video/mp4"
        }
        if(!already) {
            val next=CameraChoice(CameraMode.SkitVideo,lens)
            if(choice!=next) { choice=next;streaming=false }
            // Automatic return is deliberately NOT a user preference transition.
        }
        persistMediaState()
    }
    fun cameraReturned() {
        val pending=receipt?.takeIf { !it.handled } ?: return
        receipt=pending.copy(handled=true);acknowledgedExport=pending.video.sessionId
        savedNotice=pending.video
        persistMediaState()
    }
    fun dismissSavedNotice() { savedNotice=null }
    private fun persistMediaState() {
        viewModelScope.launch { preferenceWrites.withLock {
            val current=receipt
            val uri=latestUri;val type=latestType;val time=latestTime;val ack=acknowledgedExport
            val saved=withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO) {
                prefs.edit().putString("video_return_v1",current?.encode()).putString("media_uri",uri)
                    .putString("media_type",type).putLong("media_time",time).putString("acknowledged_export",ack).commit()
            }
            if(!saved) message="Saved-media details could not be remembered. Files are preserved."
        } }
    }
    fun acknowledge(id: String) { acknowledgedExport=id;persistMediaState() }
    fun media(uri: Uri, type: String, createdAtMs: Long) {
        if(createdAtMs < latestTime) return
        latestTime = createdAtMs
        latestUri = uri.toString(); latestType = type
        persistMediaState()
    }
}
