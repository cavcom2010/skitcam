package com.calvinmazhindu.skitcam.recording

import androidx.core.content.edit
import androidx.core.net.toUri
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
import androidx.compose.ui.semantics.*
import com.calvinmazhindu.skitcam.camera.CameraSymbol
import com.calvinmazhindu.skitcam.camera.CameraIcon
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RecordingControls(activity: ComponentActivity, model: RecordingViewModel, cameraGranted: Boolean,
    showShortcuts: Boolean = false, verticalShutter: Boolean = false, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {},
    beforeRecording: () -> Unit = {}, onArrange: () -> Unit = {}, onBackground: () -> Unit = beforeRecording) {
    val snapshot by model.snapshots.collectAsStateWithLifecycle()
    val cameraReady by model.cameraAvailability.collectAsStateWithLifecycle()
    var pose by remember { mutableStateOf<Int?>(null) }
    val releaseReview by rememberUpdatedState(beforeRecording)
    val releaseOnBackground by rememberUpdatedState(onBackground)
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
                    releaseOnBackground()
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
                "package:${activity.packageName}".toUri()))
        } else {
            requested = true
            preferences.edit { putBoolean("requested", true) }
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val fresh = snapshot.state in setOf(RecordingState.Ready,RecordingState.Completed)
        if(!fresh) {
            val seconds=snapshot.durationMs/1000
            Text("%02d:%02d · %d kept".format(seconds/60,seconds%60,snapshot.count),color=Color.White,
                style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
        }
        @Composable fun Shutter(enabled: Boolean, label: String, icon: CameraIcon, onClick: ()->Unit) {
            Button(onClick=onClick,enabled=enabled,shape=androidx.compose.foundation.shape.CircleShape,modifier=Modifier.size(80.dp)
                .semantics { contentDescription=label },contentPadding=PaddingValues(0.dp),
                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFAE3447),contentColor=Color.White)) {
                CameraSymbol(icon,Modifier.size(36.dp),if(enabled) Color.White else Color.Gray)
            }
        }
        when (snapshot.state) {
            RecordingState.Ready, RecordingState.Completed -> {
                @Composable fun IdleShutter() { Shutter(canStart,"Start recording",CameraIcon.Record) { releaseReview();if(microphone) model.start(pose) else requestAudio() } }
                if(verticalShutter) Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    leading();IdleShutter();trailing()
                } else Row(Modifier.fillMaxWidth(),horizontalArrangement=if(showShortcuts) Arrangement.SpaceBetween else Arrangement.Center,
                    verticalAlignment=Alignment.CenterVertically) { leading();IdleShutter();trailing() }
            }
            RecordingState.Recording -> Shutter(true,"Pause recording",CameraIcon.Pause) { model.pause() }
            RecordingState.Paused -> Button(contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),onClick={releaseReview();if(microphone) model.resume(pose) else requestAudio()},enabled=canResume,
                modifier=Modifier.heightIn(min=56.dp).semantics { contentDescription="Resume recording" }) { Text("RESUME",maxLines=1) }
            else -> CircularProgressIndicator(Modifier.size(36.dp).semantics { contentDescription=snapshot.state.name })
        }
        if (snapshot.state !in setOf(RecordingState.Ready, RecordingState.Completed)) {
            if(snapshot.state==RecordingState.Recording) IconButton(onClick={releaseReview();model.stop()},modifier=Modifier.size(48.dp)
                .semantics { contentDescription="Stop recording" }) { CameraSymbol(CameraIcon.Stop) }
            else OutlinedButton(onClick={releaseReview();model.stop()},enabled=!snapshot.unresolved && snapshot.state in setOf(RecordingState.Error,RecordingState.Paused),
                modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription="Stop recording" }) { Text("STOP") }
        }
        if (snapshot.state == RecordingState.Paused) {
            TextButton(onClick = onArrange, enabled = snapshot.count >= 2 && !snapshot.unresolved,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Arrange")
            }
        }
        val warning = when {
            snapshot.state == RecordingState.Error -> snapshot.message
            snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed, RecordingState.Paused) && !poseCorrect ->
                if (desiredPose == null) "Hold upright to record"
                else "Wrong orientation. Return to the original $desiredPose° direction to RESUME."
            snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed) && !matchesPreview -> "Match the preview orientation"
            !microphone -> if (requested) "Microphone access is off. Tap START or RESUME to allow it in permissions or settings."
                else "Audio is required. START requests microphone permission; press START again after allowing it."
            else -> if(snapshot.state == RecordingState.Completed) null else snapshot.message
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
