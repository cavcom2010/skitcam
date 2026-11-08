package com.calvinmazhindu.skitcam.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import com.calvinmazhindu.skitcam.BuildConfig
import com.calvinmazhindu.skitcam.video.SegmentMediaFacts
import com.calvinmazhindu.skitcam.audio.*
import kotlin.math.roundToInt

@Composable
fun CameraTools(model: CameraModel, full: Boolean, adjustmentsEnabled: Boolean, observed: SegmentMediaFacts?=null,
    sessionOpen: Boolean=false,onMore: (() -> Unit)?=null,audio: AudioMonitor?=null,onClose: ()->Unit) {
    BackHandler(onBack=onClose)
    val maxQuickHeight=with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp()*.45f }
    var advanced by remember { mutableStateOf(false) }
    val video=model.video
    Surface(if(full) Modifier.fillMaxSize() else Modifier.widthIn(max=360.dp).heightIn(max=maxQuickHeight),
        color=androidx.compose.ui.graphics.Color(0xFF18181D),tonalElevation=4.dp) {
        Column(Modifier.then(if(full) Modifier.safeDrawingPadding() else Modifier).verticalScroll(rememberScrollState())
            .padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(if(full) "Settings" else "Quick settings",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick=onClose) { Text("Close") }
            }
            @Composable fun Heading(title: String) { HorizontalDivider();Text(title,style=MaterialTheme.typography.titleSmall) }
            @Composable fun VideoControls() {
            if(full) Heading("Video")
            if(model.choice.mode==CameraMode.SkitVideo) {
                if(sessionOpen) Text("Session profile locked until STOP.",style=MaterialTheme.typography.labelSmall)
                if(full) Text("Quality",style=MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    video.qualities.forEach { quality -> FilterChip(selected=video.settings.quality==quality,enabled=video.canEdit() && !video.pending,
                        onClick={video.choose(video.settings.copy(quality=quality))},label={Text(when(quality){"FHD"->"1080p";"HD"->"720p";"UHD"->"4K";else->"480p"},maxLines=1)}) }
                }
                if(full) Text("Frame rate",style=MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    (listOf<Int?>(null)+video.fpsOptions).forEach { fps -> FilterChip(selected=video.settings.fps==fps,enabled=video.canEdit() && !video.pending,
                        onClick={video.choose(video.settings.copy(fps=fps))},label={Text(fps?.let { "$it fps target" } ?: "Auto",maxLines=1)}) }
                }
                if(video.settings.fps==60) Text("60 is best effort; drops can occur. Auto/30 recommended for dialogue.",style=MaterialTheme.typography.labelSmall)
                if(video.stabilizationSupported) Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Stabilisation",Modifier.weight(1f))
                    Switch(checked=video.settings.stabilization,onCheckedChange={video.choose(video.settings.copy(stabilization=it))},
                        enabled=video.canEdit() && !video.pending && (video.settings.fps ?: 30)<=30 && video.settings.quality!="UHD",
                        modifier=Modifier.semantics { contentDescription="Electronic video stabilization" })
                }
                if((video.settings.fps ?: 30)>30) Text("Stabilisation requires Auto/30 or lower.",style=MaterialTheme.typography.labelSmall)
                if(full) {
                    var wbMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick={wbMenu=true},enabled=video.canEdit() && !video.pending) { Text("White balance · ${video.settings.whiteBalance.lowercase()}") }
                        DropdownMenu(expanded=wbMenu,onDismissRequest={wbMenu=false}) {
                            video.whiteBalances.forEach { wb -> DropdownMenuItem(text={Text(wb.lowercase())},onClick={wbMenu=false;video.choose(video.settings.copy(whiteBalance=wb))}) }
                        }
                    }
                    Text(video.whiteBalanceStatus,style=MaterialTheme.typography.labelSmall)
                }
                video.message?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
            } else if(full) Text("Open Skit Video to change its capture profile.",style=MaterialTheme.typography.bodySmall)
            }
            if(!full && model.choice.mode==CameraMode.SkitVideo) VideoControls()
            if(full) Heading("Camera")
            if(full) Text("${model.choice.lens.name} camera")
            if(model.maxZoom>model.minZoom) {
                Text("Zoom · %.1f×".format(model.zoom))
                Slider(value=model.zoom.coerceIn(model.minZoom,model.maxZoom),onValueChange=model::adjustZoom,valueRange=model.minZoom..model.maxZoom,
                    enabled=adjustmentsEnabled,modifier=Modifier.semantics { contentDescription="Zoom adjustment" })
            }
            if(model.exposureRange.first<model.exposureRange.last && model.exposureStep>0) {
                Text("Exposure · %+.2f EV".format(model.exposure*model.exposureStep))
                Slider(value=model.exposure.toFloat(),onValueChange={model.adjustExposure(it.roundToInt())},
                    valueRange=model.exposureRange.first.toFloat()..model.exposureRange.last.toFloat(),enabled=adjustmentsEnabled,
                    steps=(model.exposureRange.last-model.exposureRange.first-1).coerceIn(0,100),modifier=Modifier.semantics { contentDescription="Exposure compensation" })
                if(full) Row {
                    TextButton(onClick={model.adjustExposure(model.exposure-1)},enabled=adjustmentsEnabled) { Text("− EV") }
                    TextButton(onClick={model.adjustExposure(0)},enabled=adjustmentsEnabled) { Text("Reset EV") }
                    TextButton(onClick={model.adjustExposure(model.exposure+1)},enabled=adjustmentsEnabled) { Text("+ EV") }
                }
            }
            if(model.choice.mode==CameraMode.SkitVideo) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if(video.exposureLockSupported) TextButton(onClick=video::toggleExposureLock,enabled=adjustmentsEnabled) { Text(video.exposureLock,maxLines=1) }
                    if(video.autofocusSupported) TextButton(onClick=model::toggleFocusHold,enabled=adjustmentsEnabled) { Text(if(video.focusHold) "Focus held · Auto" else "Hold focus",maxLines=1) }
                    else Text("Fixed focus")
                }
            }
            if(full || model.choice.mode==CameraMode.Photo) {
                if(full) Heading("Photo")
                Text("Timer")
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(0,3,10).forEach { seconds -> FilterChip(selected=model.timer==seconds,onClick={model.timer(seconds)},label={Text(if(seconds==0) "Off" else "$seconds s",maxLines=1)}) }
                }
                if(full) Text("JPEG · quality priority",style=MaterialTheme.typography.labelSmall)
            }
            if(full) VideoControls()
            if(full) { Heading("Audio");audio?.let { AudioSettings(it) } }
            if(full) Heading("Preferences")
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Grid",Modifier.weight(1f));Switch(model.grid,model::grid,modifier=Modifier.semantics { contentDescription="Grid overlay" }) }
            if(!full && onMore!=null) TextButton(onClick=onMore) { Text("More settings") }
            if(full) {
                Heading("Advanced / Technical Information")
                TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "Hide information" else "Show information") }
                if(advanced) {
                    Text("${model.choice.lens.name} camera · ${model.resolution}")
                    Text("Bitrate: Auto. SDR capture; finalised clips must verify as AVC/AAC. FPS values are requests, not constant-rate guarantees.")
                    Text(video.stabilizationStatus)
                    observed?.let { Text("Last clip: ${it.width} × ${it.height}, ${it.averageFps?.let { fps -> "%.2f fps measured".format(fps) } ?: "cadence unavailable"}; ${it.averageVideoBitrate/1000} kbps video payload.") }
                    Text("Session profiles remain locked through pause and recovery. Focus/zoom reset on camera changes; saved selfies are unmirrored. Original segments and metadata are retained.")
                    Text("SkitCam · ${BuildConfig.VERSION_NAME}")
                }
            }
        }
    }
}
