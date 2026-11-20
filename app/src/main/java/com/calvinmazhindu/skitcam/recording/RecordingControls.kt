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
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import com.calvinmazhindu.skitcam.ui.components.SkitCamIconAction
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing
import kotlinx.coroutines.delay

/**
 * The one status line under the controls. Pose and preview advice must never mask a
 * hard capture block, such as a streaming preview with no video profile.
 */
internal fun recordingWarning(state: RecordingState, message: String?, finishing: String,
    batteryPaused: String, batteryAllowsCapture: Boolean, batteryWarning: String?,
    captureBlockedReason: String?, poseCorrect: Boolean, desiredPose: Int?,
    orientationUpright: String, orientationReturn: String?,
    matchesPreview: Boolean, matchPreview: String,
    microphoneGranted: Boolean, microphoneRequested: Boolean,
    microphoneOff: String, microphoneRequest: String): String? = when {
    state == RecordingState.Error -> message
    state == RecordingState.Finalising || state == RecordingState.Stopping -> message ?: finishing
    state == RecordingState.Paused && message == batteryPaused && !batteryAllowsCapture -> batteryPaused
    batteryWarning != null -> batteryWarning
    captureBlockedReason != null &&
        state in setOf(RecordingState.Ready, RecordingState.Completed, RecordingState.Paused) -> captureBlockedReason
    state in setOf(RecordingState.Ready, RecordingState.Completed, RecordingState.Paused) && !poseCorrect ->
        orientationReturn ?: orientationUpright
    state in setOf(RecordingState.Ready, RecordingState.Completed) && !matchesPreview -> matchPreview
    !microphoneGranted -> if (microphoneRequested) microphoneOff else microphoneRequest
    else -> if (state == RecordingState.Completed) null
        else message?.takeUnless { it == RecordingEngine.BATTERY_PAUSED }
}

@Composable
fun RecordingControls(activity: ComponentActivity, model: RecordingViewModel, orientation: CameraOrientationController,
    previewRotation: Int?, cameraGranted: Boolean, captureBlockedReason: String? = null,
    showShortcuts: Boolean = false, verticalShutter: Boolean = false, modeSelector: @Composable () -> Unit = {},
    leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {},
    beforeRecording: () -> Unit = {}, onBackground: () -> Unit = beforeRecording,
    navigate: (() -> Unit) -> Unit = { it() },
    onFinish: () -> Unit, finishEnabled: Boolean = true) {
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
    val battery by model.battery.collectAsStateWithLifecycle()
    val batteryAllowsCapture = model.batteryPolicy.allowsCapture(battery)
    val canStart = cameraGranted && cameraReady && poseCorrect && matchesPreview && !commandPending && batteryAllowsCapture
    val canResume = cameraGranted && cameraReady && poseCorrect && matchesPreview && !commandPending &&
        snapshot.count < RecordingEngine.MAX_SEGMENTS && batteryAllowsCapture
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
    val resumeRecordingLabel = stringResource(R.string.resume_recording)
    var announcement by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot.state) {
        announcement = when (snapshot.state) {
            RecordingState.Recording -> if (snapshot.count > 1) recordingResumed else recordingStarted
            RecordingState.Paused -> if (snapshot.message == RecordingEngine.BATTERY_PAUSED) null else recordingPaused
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
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            if (fresh) {
                Text(if (verticalShutter) stringResource(R.string.camera_ready_short) else readyToRecord, modifier = Modifier
                    .background(SkitCamColors.Ink.copy(alpha=.9f), MaterialTheme.shapes.small)
                    .padding(horizontal=8.dp, vertical=2.dp)
                    .semantics { contentDescription = readyToRecord },
                    color = SkitCamColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, maxLines = 1)
            } else {
                val seconds=snapshot.durationMs/1000
                val time = "%02d:%02d".format(seconds/60,seconds%60)
                val countLabel = "Segment count. ${snapshot.count} kept. Skit duration $time."
                if (verticalShutter) {
                    Row(Modifier.background(SkitCamColors.Ink.copy(alpha=.85f), MaterialTheme.shapes.small)
                        .padding(horizontal=8.dp, vertical=2.dp).clearAndSetSemantics {
                            contentDescription=countLabel
                            liveRegion=LiveRegionMode.Polite
                        },
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(time, style=MaterialTheme.typography.labelMedium, color=SkitCamColors.TextPrimary)
                        CameraSymbol(CameraIcon.Check, Modifier.size(14.dp), SkitCamColors.Success)
                        Text("${snapshot.count}", style=MaterialTheme.typography.labelMedium, color=SkitCamColors.TextPrimary)
                    }
                } else Text("$time · ${snapshot.count} kept",color=SkitCamColors.TextPrimary,
                    style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center,maxLines=1,
                    overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier=Modifier.background(SkitCamColors.Ink.copy(alpha=.85f), MaterialTheme.shapes.small)
                        .padding(horizontal=8.dp, vertical=2.dp).semantics {
                        contentDescription=countLabel
                        liveRegion=LiveRegionMode.Polite
                    })
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
                RecordingState.Paused -> Shutter(canResume, resumeRecordingLabel, CameraIcon.Play) {
                    navigate { releaseReview();if(microphone) model.resume(pose) else requestAudio() }
                }
                else -> Box(Modifier.size(SkitCamSpacing.shutter), contentAlignment = Alignment.Center) {
                    // Keep the shutter's footprint; a smaller spinner would shrink the
                    // dock and visibly move the controls on START.
                    CircularProgressIndicator(Modifier.size(36.dp).semantics {
                        contentDescription = when(snapshot.state) {
                            RecordingState.Starting -> startingCamera
                            RecordingState.Finalising, RecordingState.Stopping -> finishingClip
                            else -> readyToRecord
                        }
                    })
                }
            }
        }
        val activeSession = snapshot.state in setOf(RecordingState.Recording,RecordingState.Paused,RecordingState.Error)
        // While a session is open the mode, gallery and lens controls are disabled.
        // The landscape rail is too short to keep those dead controls above Finish
        // on tall-aspect displays, so omit them there.
        val compactCaptureRail = verticalShutter && activeSession
        val finish: @Composable () -> Unit = {
            SkitCamIconAction(
                icon = CameraIcon.Stop,
                label = stringResource(R.string.finish_skit),
                onClick = onFinish,
                enabled = finishEnabled,
                emphasized = true
            )
        }
        CaptureDockRow(
            vertical = verticalShutter,
            leading = if (compactCaptureRail) ({}) else leading,
            modeSelector = if (compactCaptureRail) ({}) else modeSelector,
            shutter = primary,
            trailing = if (activeSession) finish else if (snapshot.openSession) ({}) else trailing
        )
        // A stable status slot keeps the shutter/lens-to-Finish position from jumping.
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.TopCenter) {
            val warning = recordingWarning(
                state = snapshot.state,
                message = snapshot.message,
                finishing = finishingClip,
                batteryPaused = RecordingEngine.BATTERY_PAUSED,
                batteryAllowsCapture = batteryAllowsCapture,
                batteryWarning = if (!batteryAllowsCapture ||
                    battery.knownPercentage == model.batteryPolicy.warningAt.toDouble())
                    model.batteryPolicy.warning(battery, snapshot.state) else null,
                captureBlockedReason = captureBlockedReason,
                poseCorrect = poseCorrect,
                desiredPose = desiredPose,
                orientationUpright = stringResource(R.string.status_orientation_upright),
                orientationReturn = desiredPose?.let { stringResource(R.string.status_orientation_return, it) },
                matchesPreview = matchesPreview,
                matchPreview = stringResource(R.string.status_match_preview),
                microphoneGranted = microphone,
                microphoneRequested = requested,
                microphoneOff = stringResource(R.string.status_microphone_off),
                microphoneRequest = stringResource(R.string.status_microphone_request)
            )
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.xxs)) {
                announcement?.let {
                    Text(it, color = SkitCamColors.Warm, style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, modifier = Modifier
                            .background(SkitCamColors.Ink.copy(alpha=.9f), MaterialTheme.shapes.small)
                            .padding(horizontal=6.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                warning?.let {
                    Text(it, color = SkitCamColors.Warning, style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, modifier = Modifier
                            .background(SkitCamColors.Ink.copy(alpha=.9f), MaterialTheme.shapes.small)
                            .padding(horizontal=6.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}
