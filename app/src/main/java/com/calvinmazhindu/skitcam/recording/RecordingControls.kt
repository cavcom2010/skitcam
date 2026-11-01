package com.calvinmazhindu.skitcam.recording

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RecordingControls(activity: ComponentActivity, model: RecordingViewModel, cameraGranted: Boolean,
    beforeRecording: () -> Unit = {}) {
    val snapshot by model.snapshots.collectAsStateWithLifecycle()
    val cameraReady by model.cameraAvailability.collectAsStateWithLifecycle()
    var pose by remember { mutableStateOf<Int?>(null) }
    val releaseReview by rememberUpdatedState(beforeRecording)
    fun audioGranted() = ContextCompat.checkSelfPermission(activity,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var microphone by remember { mutableStateOf(audioGranted()) }
    val preferences = remember { activity.getSharedPreferences("microphone_permission", 0) }
    var requested by remember { mutableStateOf(preferences.getBoolean("requested", false)) }
    var rationale by remember { mutableStateOf(activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphone = it
        rationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(activity, model) {
        val tracker = OrientationTracker(activity) { pose = it }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    microphone = audioGranted()
                    rationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    if (tracker.canDetectOrientation()) tracker.enable()
                    else pose = null
                }
                Lifecycle.Event.ON_PAUSE -> {
                    releaseReview()
                    tracker.stop()
                    model.cameraReady = false
                }
                else -> Unit
            }
        }
        val beforeStart: (Int) -> Unit = { degrees ->
            releaseReview()
            check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                "Camera screen left the foreground before recording started."
            }
            activity.lockRecordingOrientation(degrees)
        }
        model.port.beforeStart = beforeStart
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) tracker.enable()
        onDispose {
            tracker.stop()
            activity.lifecycle.removeObserver(observer)
            if (model.port.beforeStart === beforeStart) model.port.beforeStart = null
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(snapshot.openSession, snapshot.session?.orientationDegrees) {
        if (snapshot.openSession) activity.lockRecordingOrientation(snapshot.session!!.orientationDegrees)
        else activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(snapshot.state) {
        if (snapshot.state in setOf(RecordingState.Starting, RecordingState.Recording,
                RecordingState.Finalising, RecordingState.Stopping)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    val desiredPose = snapshot.session?.takeIf { snapshot.openSession }?.orientationDegrees
    val poseCorrect = pose != null && (desiredPose == null || pose == desiredPose)
    @Suppress("DEPRECATION")
    val matchesPreview = pose != null && CameraRecordingPort.rotationFor(pose!!) == activity.windowManager.defaultDisplay.rotation
    val canStart = cameraGranted && cameraReady && poseCorrect && matchesPreview
    val canResume = cameraGranted && cameraReady && poseCorrect && snapshot.count < RecordingEngine.MAX_SEGMENTS
    val requestAudio = {
        if (requested && !rationale) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")))
        } else {
            requested = true
            preferences.edit().putBoolean("requested", true).apply()
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(snapshot.state.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
        val seconds = snapshot.durationMs / 1000
        Text("%02d:%02d · %d/100 kept".format(seconds / 60, seconds % 60, snapshot.count),
            color = Color.White, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        when (snapshot.state) {
            RecordingState.Ready, RecordingState.Completed -> Button(
                onClick = { releaseReview(); if (microphone) model.start(pose) else requestAudio() }, enabled = canStart) { Text("START") }
            RecordingState.Recording -> Button(onClick = { model.pause() }) { Text("PAUSE") }
            RecordingState.Paused -> Button(
                onClick = { releaseReview(); if (microphone) model.resume(pose) else requestAudio() }, enabled = canResume) { Text("RESUME") }
            else -> Unit
        }
        if (snapshot.state !in setOf(RecordingState.Ready, RecordingState.Completed)) {
            OutlinedButton(onClick = { releaseReview(); model.stop() }, enabled = !snapshot.unresolved && snapshot.state == RecordingState.Error ||
                snapshot.state in setOf(RecordingState.Recording, RecordingState.Paused)) { Text("STOP") }
        }
        if (snapshot.state == RecordingState.Paused) {
            TextButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Arrange · later")
            }
        }
        val warning = when {
            snapshot.state == RecordingState.Error -> snapshot.message
            snapshot.state == RecordingState.Completed -> snapshot.message
            snapshot.state in setOf(RecordingState.Ready, RecordingState.Paused) && !poseCorrect ->
                if (desiredPose == null) "Hold the phone upright and steady to enable START."
                else "Wrong orientation. Return to the original $desiredPose° direction to RESUME."
            snapshot.state == RecordingState.Ready && !matchesPreview -> "Rotate the phone to match the preview orientation before START."
            !microphone -> if (requested) "Microphone access is off. Tap START or RESUME to allow it in permissions or settings."
                else "Audio is required. START requests microphone permission; press START again after allowing it."
            else -> snapshot.message
        }
        if (warning != null) Text(warning, color = Color(0xFFF2D4BC),
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Suppress("DEPRECATION")
private fun ComponentActivity.lockRecordingOrientation(degrees: Int) {
    val rotation = windowManager.defaultDisplay.rotation
    val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val naturalPortrait = if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) portrait else !portrait
    requestedOrientation = if (naturalPortrait) when (degrees) {
        90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        270 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else when (degrees) {
        90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
