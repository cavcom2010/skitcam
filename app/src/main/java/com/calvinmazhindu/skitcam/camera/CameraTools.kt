package com.calvinmazhindu.skitcam.camera

import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.calvinmazhindu.skitcam.BuildConfig
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.audio.*
import com.calvinmazhindu.skitcam.photo.PhotoAspectRatio
import com.calvinmazhindu.skitcam.ui.components.SkitCamDialogTitle
import com.calvinmazhindu.skitcam.ui.components.SkitCamSecondaryButton
import com.calvinmazhindu.skitcam.ui.components.SkitCamSectionHeading
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing
import com.calvinmazhindu.skitcam.video.SegmentMediaFacts
import kotlin.math.roundToInt

@Composable
fun CameraTools(model: CameraModel, full: Boolean, adjustmentsEnabled: Boolean, observed: SegmentMediaFacts?=null,
    sessionOpen: Boolean=false,onMore: (() -> Unit)?=null,audio: AudioMonitor?=null,onClose: ()->Unit) {
    val maxQuickHeight=with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp()*.55f }
    var advanced by remember { mutableStateOf(false) }
    val video=model.video
    val photo=model.choice.mode==CameraMode.Photo
    val title=stringResource(if(full) R.string.settings else R.string.quick_settings)
    val focus=remember { FocusRequester() }
    LaunchedEffect(full) { focus.requestFocus() }
    Dialog(onDismissRequest=onClose, properties=DialogProperties(usePlatformDefaultWidth=false, decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top=if(full) 0.dp else 56.dp),
            contentAlignment=if(full) Alignment.Center else Alignment.TopCenter) {
            Surface(
                if(full) Modifier.fillMaxSize() else Modifier.widthIn(max=420.dp).heightIn(max=maxQuickHeight),
                color=SkitCamColors.InkSoft,
                contentColor=SkitCamColors.TextPrimary,
                shape=if(full) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.large,
                tonalElevation=6.dp
            ) {
                Column(
                    Modifier.then(if(full) Modifier else Modifier).verticalScroll(rememberScrollState())
                        .focusRequester(focus).focusable()
                        .semantics { paneTitle=title }
                        .padding(SkitCamSpacing.lg),
                    verticalArrangement=Arrangement.spacedBy(SkitCamSpacing.sm)
                ) {
                    SkitCamDialogTitle(title,onClose)
                    if(photo) {
                        if(full) SkitCamSectionHeading(stringResource(R.string.quick_photo))
                        Text(stringResource(R.string.aspect_ratio),style=MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(SkitCamSpacing.sm)) {
                            val landscape=LocalWindowInfo.current.containerSize.width>LocalWindowInfo.current.containerSize.height
                            PhotoAspectRatio.entries.forEach { ratio ->
                                FilterChip(
                                    selected=model.photoAspectRatio==ratio,
                                    enabled=adjustmentsEnabled && !model.switching && model.streaming,
                                    onClick={ model.photoAspect(ratio,adjustmentsEnabled && !model.switching && model.streaming) },
                                    label={ Text(ratio.label(landscape),maxLines=1) }
                                )
                            }
                        }
                        Text(stringResource(R.string.timer),style=MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(SkitCamSpacing.sm)) {
                            listOf(0,3,10).forEach { seconds ->
                                FilterChip(selected=model.timer==seconds,onClick={model.timer(seconds)},
                                    label={Text(if(seconds==0) "Off" else "$seconds s",maxLines=1)})
                            }
                        }
                        if(full) Text(stringResource(R.string.jpeg_quality),style=MaterialTheme.typography.labelSmall,color=SkitCamColors.TextSecondary)
                        GridSetting(model)
                    }
                    if(!photo) {
                        if(full) SkitCamSectionHeading(stringResource(R.string.quick_video))
                        if(sessionOpen) Text(stringResource(R.string.session_profile_locked),style=MaterialTheme.typography.labelSmall,color=SkitCamColors.Warning)
                        Text(stringResource(R.string.quality),style=MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(SkitCamSpacing.sm)) {
                            video.qualities.forEach { quality -> FilterChip(selected=video.settings.quality==quality,enabled=video.canEdit() && !video.pending,
                                onClick={video.choose(video.settings.copy(quality=quality))},label={Text(when(quality){"FHD"->"1080p";"HD"->"720p";"UHD"->"4K";else->"480p"},maxLines=1)}) }
                        }
                        Text(stringResource(R.string.frame_rate),style=MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(SkitCamSpacing.sm)) {
                            (listOf<Int?>(null)+video.fpsOptions).forEach { fps -> FilterChip(selected=video.settings.fps==fps,enabled=video.canEdit() && !video.pending,
                                onClick={video.choose(video.settings.copy(fps=fps))},label={Text(fps?.let { "$it fps target" } ?: "Auto",maxLines=1)}) }
                        }
                        if(video.settings.fps==60) Text(stringResource(R.string.fps_best_effort),style=MaterialTheme.typography.labelSmall,color=SkitCamColors.TextSecondary)
                        if(video.stabilizationSupported) Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(stringResource(R.string.stabilisation),Modifier.weight(1f))
                            Switch(checked=video.settings.stabilization,onCheckedChange={video.choose(video.settings.copy(stabilization=it))},
                                enabled=video.canEdit() && !video.pending && (video.settings.fps ?: 30)<=30 && video.settings.quality!="UHD",
                                modifier=Modifier.semantics { contentDescription="Electronic video stabilization" })
                        }
                        if((video.settings.fps ?: 30)>30) Text(stringResource(R.string.stabilisation_requires_auto),style=MaterialTheme.typography.labelSmall,color=SkitCamColors.TextSecondary)
                        video.message?.let { Text(it,style=MaterialTheme.typography.labelSmall,color=SkitCamColors.Warning) }
                    }
                    SkitCamSectionHeading(stringResource(R.string.quick_camera))
                    if(full) Text("${model.choice.lens.name} camera")
                    if(model.maxZoom>model.minZoom) {
                        Text("${stringResource(R.string.zoom)} · %.1f×".format(model.zoom),style=MaterialTheme.typography.labelLarge)
                        Slider(value=model.zoom.coerceIn(model.minZoom,model.maxZoom),onValueChange=model::adjustZoom,valueRange=model.minZoom..model.maxZoom,
                            enabled=adjustmentsEnabled,modifier=Modifier.semantics { contentDescription="Zoom adjustment" })
                    }
                    if(model.exposureRange.first<model.exposureRange.last && model.exposureStep>0) {
                        SkitCamSectionHeading(stringResource(R.string.quick_advanced))
                        Text("${stringResource(R.string.exposure)} · %+.2f EV".format(model.exposure*model.exposureStep),style=MaterialTheme.typography.labelLarge)
                        Slider(value=model.exposure.toFloat(),onValueChange={model.adjustExposure(it.roundToInt())},
                            valueRange=model.exposureRange.first.toFloat()..model.exposureRange.last.toFloat(),enabled=adjustmentsEnabled,
                            steps=(model.exposureRange.last-model.exposureRange.first-1).coerceIn(0,100),modifier=Modifier.semantics { contentDescription="Exposure compensation" })
                        if(full) Row {
                            TextButton(onClick={model.adjustExposure(model.exposure-1)},enabled=adjustmentsEnabled) { Text("− EV") }
                            TextButton(onClick={model.adjustExposure(0)},enabled=adjustmentsEnabled) { Text("Reset EV") }
                            TextButton(onClick={model.adjustExposure(model.exposure+1)},enabled=adjustmentsEnabled) { Text("+ EV") }
                        }
                    }
                    if(!photo) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            if(video.exposureLockSupported) TextButton(onClick=video::toggleExposureLock,enabled=adjustmentsEnabled) { Text(video.exposureLock,maxLines=1) }
                            if(video.autofocusSupported) TextButton(onClick=model::toggleFocusHold,enabled=adjustmentsEnabled) { Text(if(video.focusHold) "Focus held · Auto" else "Hold focus",maxLines=1) }
                            else Text("Fixed focus")
                        }
                        if(full) {
                            var wbMenu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick={wbMenu=true},enabled=video.canEdit() && !video.pending) { Text("${stringResource(R.string.white_balance)} · ${video.settings.whiteBalance.lowercase()}") }
                                DropdownMenu(expanded=wbMenu,onDismissRequest={wbMenu=false}) {
                                    video.whiteBalances.forEach { wb -> DropdownMenuItem(text={Text(wb.lowercase())},onClick={wbMenu=false;video.choose(video.settings.copy(whiteBalance=wb))}) }
                                }
                            }
                            Text(video.whiteBalanceStatus,style=MaterialTheme.typography.labelSmall,color=SkitCamColors.TextSecondary)
                        }
                    }
                    if(full) {
                        SkitCamSectionHeading(stringResource(R.string.quick_audio))
                        audio?.let { AudioSettings(it) }
                        SkitCamSectionHeading(stringResource(R.string.quick_technical))
                        TextButton(onClick={advanced=!advanced}) { Text(stringResource(if(advanced) R.string.hide_information else R.string.show_information),color=SkitCamColors.Warm) }
                        if(advanced) {
                            Text("${model.choice.lens.name} camera · ${model.resolution}")
                            Text("Bitrate: Auto. SDR capture; finalised clips must verify as AVC/AAC. FPS values are requests, not constant-rate guarantees.")
                            Text(video.stabilizationStatus)
                            observed?.let { Text("Last clip: ${it.width} × ${it.height}, ${it.averageFps?.let { fps -> "%.2f fps measured".format(fps) } ?: "cadence unavailable"}; ${it.averageVideoBitrate/1000} kbps video payload.") }
                            Text("Session profiles remain locked through pause and recovery. Focus/zoom reset on camera changes; saved selfies are unmirrored. Original segments and metadata are retained.")
                            Text("SkitCam · ${BuildConfig.VERSION_NAME}")
                        }
                    }
                    if(!full && onMore!=null) SkitCamSecondaryButton(stringResource(R.string.more_settings),onMore,Modifier.fillMaxWidth())
                    if(!full) Text("Changes apply when the camera is idle. Recording and recovery locks are respected.",style=MaterialTheme.typography.labelSmall,color=SkitCamColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun GridSetting(model: CameraModel) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(stringResource(R.string.grid),Modifier.weight(1f))
        Switch(model.grid,model::grid,modifier=Modifier.semantics { contentDescription="Grid overlay" })
    }
}
