package com.calvinmazhindu.skitcam.recording

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle

/** One foreground sensor/display owner for Photo, Video, recovery and finishing. Main thread only. */
class CameraOrientationController(private val activity: ComponentActivity) {
    var pose by mutableStateOf<Int?>(null); private set
    var displayRotation by mutableIntStateOf(readRotation()); private set
    var ready by mutableStateOf(false); private set
    private var pendingStart: Int? = null
    private var appliedRequest: CameraOrientationPolicy.Request? = null
    private var lockedPose: Int? = null
    private var observing=false
    private val manager=activity.getSystemService(DisplayManager::class.java)
    private val naturalPortrait = (activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT) ==
        (displayRotation==Surface.ROTATION_0 || displayRotation==Surface.ROTATION_180)
    private val tracker=OrientationTracker(activity) { pose=it }
    private val listener=object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) { if(displayId==activity.window.decorView.display?.displayId) refreshDisplay() }
    }
    @Suppress("DEPRECATION")
    private fun readRotation() = activity.window.decorView.display?.rotation ?: activity.windowManager.defaultDisplay.rotation
    fun refreshDisplay() {
        displayRotation=readRotation()
        ready=CameraOrientationPolicy.cameraMayBind(appliedRequest,lockedPose,displayRotation)
    }
    fun start() {
        refreshDisplay()
        if(observing) return
        observing=true
        manager.registerDisplayListener(listener,Handler(Looper.getMainLooper()))
        if(tracker.canDetectOrientation()) tracker.enable()
    }
    fun stop() {
        if(observing) manager.unregisterDisplayListener(listener)
        observing=false;tracker.stop();pose=null
    }
    fun update(snapshot: RecordingSnapshot,recovered: Boolean,finishing: Boolean,commandPending: Boolean) {
        if(snapshot.openSession || !commandPending) pendingStart=null
        val request=CameraOrientationPolicy.request(snapshot,recovered,finishing,pendingStart,naturalPortrait)
        if(request!=null) apply(request)
        appliedRequest=request
        lockedPose=if(request==CameraOrientationPolicy.Request.FullSensor) null else
            snapshot.session?.takeIf { snapshot.openSession || finishing }?.orientationDegrees ?: pendingStart
        // A cold landscape recovery can start in a portrait window. Do not bind a
        // portrait Preview while waiting for Android to apply the restored lock.
        ready=CameraOrientationPolicy.cameraMayBind(request,lockedPose,displayRotation)
    }
    private fun apply(request: CameraOrientationPolicy.Request) {
        val value=when(request) {
            CameraOrientationPolicy.Request.FullSensor -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            CameraOrientationPolicy.Request.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            CameraOrientationPolicy.Request.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            CameraOrientationPolicy.Request.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            CameraOrientationPolicy.Request.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
        if(activity.requestedOrientation!=value) activity.requestedOrientation=value
    }
    fun lockForStart(degrees: Int): Boolean {
        refreshDisplay()
        if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || pose!=degrees ||
            !CameraOrientationPolicy.matchesDisplay(pose,displayRotation)) return false
        pendingStart=degrees
        apply(CameraOrientationPolicy.lock(degrees,naturalPortrait))
        return true
    }
    /** Revalidate after the engine worker hop, immediately before opening the Recorder. */
    fun beforeCapture(degrees: Int) {
        check(lockForStart(degrees)) { "The device moved before recording began. Match the preview orientation and try again." }
    }
    fun diagnostic() = org.json.JSONObject().put("requestedOrientation",activity.requestedOrientation)
        .put("displayRotation",displayRotation).put("configurationOrientation",activity.resources.configuration.orientation)
        .put("rawPose",tracker.rawDegrees).put("filteredPose",pose).put("sensorAvailable",tracker.canDetectOrientation())
        .put("activityIdentity",System.identityHashCode(activity)).put("ready",ready)
}
