package com.calvinmazhindu.skitcam.recording

import androidx.core.content.edit
import androidx.core.net.toUri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.camera.CameraIcon
import com.calvinmazhindu.skitcam.camera.CameraSymbol
import com.calvinmazhindu.skitcam.ui.camera.CaptureDockRow
import com.calvinmazhindu.skitcam.ui.components.SkitCamSecondaryButton
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing
import kotlinx.coroutines.delay

@Composable
fun RecordingControls(activity: ComponentActivity, model: RecordingViewModel, orientation: CameraOrientationController,
    previewRotation: Int?, cameraGranted: Boolean,
    showShortcuts: Boolean = false, verticalShutter: Boolean = false, modeSelector: @Composable () -> Unit = {},
    leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {},
    beforeRecording: () -> Unit = {}, onArrange: () -> Unit = {}, onBackground: () -> Unit = beforeRecording) {
    val snapshot by model.snapshots.collectAsStateWithLifecycle()
    val cameraReady by model.cameraAvailability.collectAsStateWithLifecycle()
    val commandPending by model.commandPending.collectAsStateWithLifecycle()
    val pose = orientation.pose
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
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    microphone = audioGranted()
                    rationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    releaseOnBackground()
                    model.cameraReady = false
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(snapshot.state) {
        if (snapshot.state in setOf(RecordingState.Starting, RecordingState.Recording,
                RecordingState.Finalising, RecordingState.Stopping)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    val desiredPose = snapshot.session?.takeIf { snapshot.openSession }?.orientationDegrees
    val poseCorrect = pose != null && (desiredPose == null || pose == desiredPose)
    val matchesPreview = CameraOrientationPolicy.matchesDisplay(pose,orientation.displayRotation) && previewRotation==orientation.displayRotation
    val canStart = cameraGranted && cameraReady && poseCorrect && matchesPreview && !commandPending
    val canResume = cameraGranted && cameraReady && poseCorrect && matchesPreview && snapshot.count < RecordingEngine.MAX_SEGMENTS
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

    val recordingStarted = stringResource(R.string.recording_started)
    val recordingPaused = stringResource(R.string.recording_paused)
    val recordingResumed = stringResource(R.string.recording_resumed)
    val finishingClip = stringResource(R.string.finishing_clip)
    val startingCamera = stringResource(R.string.starting_camera)
    val readyToRecord = stringResource(R.string.status_ready_to_record)
    val stopRecordingLabel = stringResource(R.string.stop_recording)
    val resumeRecordingLabel = stringResource(R.string.resume_recording)
    var announcement by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot.state) {
        announcement = when (snapshot.state) {
            RecordingState.Recording -> if (snapshot.count > 1) recordingResumed else recordingStarted
            RecordingState.Paused -> recordingPaused
            RecordingState.Finalising, RecordingState.Stopping -> finishingClip
            else -> null
        }
        if (announcement != null) {
            delay(2500)
            announcement = null
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.xs)) {
        val fresh = snapshot.state in setOf(RecordingState.Ready,RecordingState.Completed)
        Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
            if (fresh) {
                Text(readyToRecord, color = SkitCamColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center)
            } else {
                val seconds=snapshot.durationMs/1000
                Text("%02d:%02d · %d kept".format(seconds/60,seconds%60,snapshot.count),color=SkitCamColors.TextPrimary,
                    style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
            }
        }
        @Composable fun Shutter(enabled: Boolean, label: String, icon: CameraIcon, onClick: ()->Unit) {
            Button(onClick=onClick,enabled=enabled,shape=CircleShape,modifier=Modifier.size(SkitCamSpacing.shutter)
                .semantics { contentDescription=label },contentPadding=PaddingValues(0.dp),
                colors=ButtonDefaults.buttonColors(
                    containerColor=SkitCamColors.RoseStrong,
                    contentColor=Color.White,
                    disabledContainerColor=SkitCamColors.SurfacePressed,
                    disabledContentColor=SkitCamColors.TextMuted
                )) {
                CameraSymbol(icon,Modifier.size(36.dp),if(enabled) Color.White else SkitCamColors.TextMuted)
            }
        }
        val primary: @Composable () -> Unit = {
            when (snapshot.state) {
                RecordingState.Ready, RecordingState.Completed -> {
                    Shutter(canStart,stringResource(R.string.start_recording),CameraIcon.Record) {
                        releaseReview()
                        if(!microphone) requestAudio()
                        else pose?.let { if(orientation.lockForStart(it)) model.start(it) }
                    }
                }
                RecordingState.Recording -> Shutter(true,stringResource(R.string.pause_recording),CameraIcon.Pause) { model.pause() }
                RecordingState.Paused -> Button(
                    onClick={ releaseReview();if(microphone) model.resume(pose) else requestAudio() },
                    enabled=canResume,
                    shape=CircleShape,
                    modifier=Modifier.size(SkitCamSpacing.shutter).semantics {
                        contentDescription=resumeRecordingLabel
                    },
                    contentPadding=PaddingValues(0.dp),
                    colors=ButtonDefaults.buttonColors(
                        containerColor=SkitCamColors.RoseStrong,
                        contentColor=Color.White,
                        disabledContainerColor=SkitCamColors.SurfacePressed,
                        disabledContentColor=SkitCamColors.TextMuted
                    )
                ) { Text("RESUME", style=MaterialTheme.typography.labelMedium, maxLines=1, textAlign=TextAlign.Center) }
                else -> CircularProgressIndicator(Modifier.size(36.dp).semantics {
                    contentDescription = when(snapshot.state) {
                        RecordingState.Starting -> startingCamera
                        RecordingState.Finalising, RecordingState.Stopping -> finishingClip
                        else -> readyToRecord
                    }
                })
            }
        }
        CaptureDockRow(
            vertical = verticalShutter,
            leading = leading,
            modeSelector = modeSelector,
            shutter = primary,
            trailing = trailing
        )
        val activeSession = snapshot.state in setOf(RecordingState.Recording,RecordingState.Paused,RecordingState.Error)
        Box(Modifier.fillMaxWidth().heightIn(min = SkitCamSpacing.touchTarget), contentAlignment = Alignment.Center) {
            if (activeSession) {
                val finish: @Composable () -> Unit = {
                    SkitCamSecondaryButton(
                        text = stringResource(R.string.finish_recording),
                        onClick = { val tap=android.os.SystemClock.elapsedRealtimeNanos();releaseReview();model.stop("ui",tap) },
                        enabled = snapshot.state == RecordingState.Recording || !snapshot.unresolved,
                        modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget).semantics {
                            contentDescription = stopRecordingLabel
                        }
                    )
                }
                val arrange: @Composable () -> Unit = {
                    TextButton(onClick = onArrange, enabled = snapshot.state == RecordingState.Paused && snapshot.count >= 2 && !snapshot.unresolved,
                        modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) {
                        Text(stringResource(R.string.arrange), color = SkitCamColors.Warm)
                    }
                }
                if (verticalShutter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.xs)) {
                        finish(); arrange()
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(SkitCamSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        finish(); arrange()
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.TopCenter) {
            val warning = when {
                snapshot.state == RecordingState.Error -> snapshot.message
                snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed, RecordingState.Paused) && !poseCorrect ->
                    if (desiredPose == null) stringResource(R.string.status_orientation_upright)
                    else stringResource(R.string.status_orientation_return, desiredPose)
                snapshot.state in setOf(RecordingState.Ready, RecordingState.Completed) && !matchesPreview -> stringResource(R.string.status_match_preview)
                !microphone -> if (requested) stringResource(R.string.status_microphone_off)
                    else stringResource(R.string.status_microphone_request)
                snapshot.state == RecordingState.Finalising || snapshot.state == RecordingState.Stopping -> finishingClip
                else -> if(snapshot.state == RecordingState.Completed) null else snapshot.message
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.xxs)) {
                announcement?.let {
                    Text(it, color = SkitCamColors.Warm, style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                warning?.let {
                    Text(it, color = SkitCamColors.Warning, style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}
