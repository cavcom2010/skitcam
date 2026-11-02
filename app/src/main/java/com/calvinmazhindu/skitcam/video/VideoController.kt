package com.calvinmazhindu.skitcam.video

import android.content.Context
import android.hardware.camera2.*
import android.os.*
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import androidx.camera.video.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Owns only AE_LOCK and AWB_MODE interop options. Never overrides AF, FPS, ISO or shutter. */
@OptIn(ExperimentalSessionConfig::class)
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class VideoController(private val context: Context) {
    private val preferences=context.getSharedPreferences("video_preferences",0)
    private val writer=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    var revision by mutableIntStateOf(0);private set
    var settings by mutableStateOf(VideoSettings());private set
    var qualities by mutableStateOf<List<String>>(emptyList());private set
    var fpsOptions by mutableStateOf<List<Int>>(emptyList());private set
    var reportedFps by mutableStateOf<List<String>>(emptyList());private set
    var stabilizationSupported by mutableStateOf(false);private set
    var whiteBalances by mutableStateOf(listOf("AUTO"));private set
    var exposureLockSupported by mutableStateOf(false);private set
    var autofocusSupported by mutableStateOf(false);private set
    var exposureLock by mutableStateOf("Auto exposure");private set
    var focusHold by mutableStateOf(false);private set
    var sensorFpsRange by mutableStateOf<String?>(null);private set
    var sensorFrameDurationNs by mutableStateOf<Long?>(null);private set
    var stabilizationStatus by mutableStateOf("Off");private set
    var whiteBalanceStatus by mutableStateOf("Auto");private set
    var message by mutableStateOf<String?>(null)
    var canEdit: () -> Boolean = { false }
    var beforeChange: () -> Unit = {}
    private var saving=false
    var pending by mutableStateOf(false);private set
    private var locked=false
    var cameraId by mutableStateOf("");private set
    private var selected: VideoSettings?=null
    private var previous: VideoSettings?=null
    private var camera: Camera?=null
    private var generation=0
    // A callback from the previous stream cannot acknowledge a new request.
    private var appliedRevision=-1
    private var requestedAe=false
    private var wbApplied=false
    private var aeConfirmed=false
    private var lastSample=0L
    private var legacy=false
    private var failedRequest: VideoSettings?=null
    private val wbValues=mapOf("AUTO" to CaptureRequest.CONTROL_AWB_MODE_AUTO,"DAYLIGHT" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        "CLOUDY" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,"INCANDESCENT" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        "FLUORESCENT" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)

    fun desired(info: CameraInfo, session: Session?, oldMedia: SegmentMediaFacts?): VideoSettings {
        val c2=Camera2CameraInfo.from(info)
        cameraId=c2.cameraId
        val caps=Recorder.getVideoCapabilities(info)
        qualities=caps.getSupportedQualities(DynamicRange.SDR).mapNotNull { q -> qualityName(q) }
        stabilizationSupported=caps.isStabilizationSupported
        whiteBalances=wbValues.filter { (_,v) -> c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(v)==true }.keys.toList()
        exposureLockSupported=c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)==true
        autofocusSupported=(c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)>0 &&
            c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)==true
        locked=session!=null && session.state!=RecordingState.Completed
        legacy=locked && session?.captureProfile==null
        val desired=if(locked) {
            require(session!!.invalidProfileJson==null) { "The saved capture profile is invalid. STOP preserves the files." }
            session.captureProfile?.let {
                require(it.cameraId==cameraId) { "The original camera is unavailable. RESUME is blocked." }
                it.settings
            } ?: run {
                require(oldMedia!=null) { "Cannot identify the original video profile. STOP preserves this session." }
                val quality=when(maxOf(oldMedia.width,oldMedia.height)) { 3840 -> "UHD";1920 -> "FHD";1280 -> "HD";else -> "SD" }
                VideoSettings(quality)
            }
        } else selected ?: runCatching { preferences.getString("camera_$cameraId",null)?.let { VideoJson.settings(JSONObject(it)) } }.getOrNull() ?: VideoSettings()
        require(desired.valid())
        if(locked) require(desired.quality in qualities && (!desired.stabilization || stabilizationSupported) && desired.whiteBalance in whiteBalances) {
            "Original capture settings are unavailable. RESUME is blocked; STOP retains the clips."
        }
        val effective=if(!locked && (desired.quality !in qualities || desired.whiteBalance !in whiteBalances || (desired.stabilization && !stabilizationSupported))) {
            message="Saved profile unavailable. Using a supported Auto profile."
            VideoSettings(VideoProfilePolicy.fallback(qualities) ?: error("No SDR video quality available."))
        } else desired
        settings=effective
        return effective
    }
    fun configuration(info: CameraInfo, preview: Preview, capture: VideoCapture<Recorder>): Boolean {
        val ranges=info.getSupportedFrameRateRanges(SessionConfig(listOf(preview,capture)))
        reportedFps=ranges.map { "${it.lower}–${it.upper}" }.sorted()
        // Configuration-specific requests, not global AE ranges or high-speed modes. Moto G54
        // FHD/60 was independently recorded; labels remain targets on other compatible hardware.
        fpsOptions=listOf(24,25,30,60).filter { fps -> (fps<=30 || !settings.stabilization) && ranges.any { it.lower==fps && it.upper==fps } }
        if(settings.fps!=null && settings.fps !in fpsOptions) {
            if(locked || selected!=null) error("Requested FPS is unsupported for this camera/quality. RESUME/settings change blocked.")
            settings=settings.copy(fps=null);message="Saved FPS unavailable; using Auto FPS.";return false
        }
        return true
    }
    fun choose(value: VideoSettings) {
        if(!canEdit() || pending || value==settings) return
        if(!value.valid() || value.quality !in qualities || value.whiteBalance !in whiteBalances ||
            (value.fps!=null && value.fps !in fpsOptions) || (value.stabilization && !stabilizationSupported)) {
            message="This capture combination is unsupported. Change FPS or stabilization first.";return
        }
        beforeChange()
        previous=settings;selected=value;pending=true;failedRequest=null;message=null;revision++
    }
    fun failed(reason: String) {
        message=reason
        if(pending && previous!=null && selected!=failedRequest) {
            failedRequest=selected;selected=previous;settings=previous!!;previous=null;pending=false;revision++
        }
    }
    fun bound(value: Camera) {
        if(!pending) selected=null
        appliedRevision=revision
        camera=value;generation++;lastSample=0;requestedAe=false;aeConfirmed=false;focusHold=false
        sensorFpsRange=null;sensorFrameDurationNs=null
        exposureLock="Auto exposure";wbApplied=settings.whiteBalance=="AUTO"
        stabilizationStatus=if(settings.stabilization) "On requested · verify during recording" else "Off · optical stabilization is device-managed"
        whiteBalanceStatus=if(wbApplied) "Auto" else "Applying ${settings.whiteBalance.lowercase()}…"
        if(!wbApplied) applyOptions()
    }
    fun profile(width: Int,height: Int) = CaptureProfile(cameraId,settings,width,height,legacy)
    fun streamed() {
        if(!pending || !wbApplied || saving || appliedRevision!=revision) return
        saving=true
        val saved=settings;val id=cameraId
        writer.execute {
            val success=preferences.edit().putString("camera_$id",VideoJson.settings(saved).toString()).commit()
            main.post { saving=false;if(settings==saved && cameraId==id) { pending=false;previous=null;selected=null;if(!success) message="Profile applied, but preference saving failed." } }
        }
    }
    val ready get() = camera!=null && wbApplied && !pending && appliedRevision==revision
    fun release() {
        val old=camera
        appliedRevision=-1
        generation++;camera=null;focusHold=false;requestedAe=false;aeConfirmed=false;exposureLock="Auto exposure";wbApplied=false
        // Only this adapter uses interop options. CameraX controls remain independent.
        old?.let { runCatching { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() } }
    }
    fun close() { release();main.removeCallbacksAndMessages(null);writer.shutdown() }
    fun background() {
        focusHold=false;requestedAe=false;aeConfirmed=false;exposureLock="Auto exposure"
        camera?.cameraControl?.cancelFocusAndMetering()
        if(camera!=null) applyOptions()
    }
    fun holdFocus(value: Boolean) { focusHold=value }
    fun toggleExposureLock() {
        if(!exposureLockSupported || camera==null) return
        requestedAe=!requestedAe;aeConfirmed=false;exposureLock=if(requestedAe) "Locking exposure…" else "Unlocking exposure…"
        applyOptions()
    }
    private fun applyOptions() {
        val current=camera ?: return
        val token=generation
        val options=CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK,requestedAe)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,wbValues.getValue(settings.whiteBalance)).build()
        val future=Camera2CameraControl.from(current.cameraControl).setCaptureRequestOptions(options)
        future.addListener({ if(token==generation) runCatching { future.get() }.onFailure {
            message="Camera control was not confirmed: ${it.message}";requestedAe=false;aeConfirmed=false;exposureLock="Exposure unverified"
            if(settings.whiteBalance!="AUTO") failed("White balance could not be applied. Previous profile restored.")
        } },ContextCompat.getMainExecutor(context))
        main.postDelayed({ if(token==generation && (!wbApplied || (requestedAe && !aeConfirmed))) {
            if(!wbApplied) failed("White balance was not confirmed. Profile change rejected.")
            else { requestedAe=false;exposureLock="Exposure lock unverified; returning to Auto";applyOptions() }
        } },3000)
    }
    fun callback(): CameraCaptureSession.CaptureCallback {
        val token=generation+1
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession,request: CaptureRequest,result: TotalCaptureResult) {
                val now=SystemClock.elapsedRealtime()
                if(now-lastSample<200) return
                lastSample=now
                val ae=result.get(CaptureResult.CONTROL_AE_LOCK)==true && result.get(CaptureResult.CONTROL_AE_STATE)==CaptureResult.CONTROL_AE_STATE_LOCKED
                val wb=result.get(CaptureResult.CONTROL_AWB_MODE)
                val eis=result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                val af=result.get(CaptureResult.CONTROL_AF_STATE)
                val fps=result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.toString()
                val frameDuration=result.get(CaptureResult.SENSOR_FRAME_DURATION)
                main.post { if(token==generation && camera!=null) {
                    sensorFpsRange=fps;sensorFrameDurationNs=frameDuration
                    aeConfirmed=ae;exposureLock=if(ae && requestedAe) "Exposure locked" else if(requestedAe) "Locking exposure…" else "Auto exposure"
                    if(wb==wbValues[settings.whiteBalance]) { wbApplied=true;whiteBalanceStatus=if(settings.whiteBalance=="AUTO") "Auto" else "${settings.whiteBalance.lowercase()} · camera confirmed";streamed() }
                    if(settings.stabilization) stabilizationStatus=if(eis==CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON) "On · camera confirmed" else "On requested · not confirmed in current stream"
                    if(focusHold && af!=CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED && af!=CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) focusHold=false
                } }
            }
        }
    }
    fun diagnostic() = JSONObject().put("cameraId",cameraId).put("qualities",org.json.JSONArray(qualities)).put("fpsRanges",org.json.JSONArray(reportedFps))
        .put("fpsTargets",org.json.JSONArray(fpsOptions)).put("stabilizationReported",stabilizationSupported).put("autofocus",autofocusSupported)
        .put("aeLock",exposureLockSupported).put("whiteBalances",org.json.JSONArray(whiteBalances)).put("settings",VideoJson.settings(settings))
        .put("stabilizationStatus",stabilizationStatus).put("whiteBalanceStatus",whiteBalanceStatus).put("exposureStatus",exposureLock).put("focusHeld",focusHold).put("sensorFpsRange",sensorFpsRange).put("sensorFrameDurationNs",sensorFrameDurationNs).put("requestedRevision",revision).put("appliedRevision",appliedRevision)
    companion object {
        fun qualityName(q: Quality): String? = when(q) { Quality.HD -> "HD";Quality.FHD -> "FHD";Quality.UHD -> "UHD";Quality.SD -> "SD";else -> null }
    }
}
