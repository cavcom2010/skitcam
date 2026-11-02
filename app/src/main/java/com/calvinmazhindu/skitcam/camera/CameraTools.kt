package com.calvinmazhindu.skitcam.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.calvinmazhindu.skitcam.video.VideoSettings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import kotlin.math.roundToInt

@Composable
fun CameraTools(model: CameraModel, full: Boolean, adjustmentsEnabled: Boolean, observed: com.calvinmazhindu.skitcam.video.SegmentMediaFacts? = null, sessionOpen: Boolean = false, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(Modifier.then(if(full) Modifier.fillMaxSize() else Modifier.widthIn(max=360.dp).heightIn(max=380.dp)),
        color = androidx.compose.ui.graphics.Color(0xFF18181B), tonalElevation = 4.dp) {
        Column(Modifier.then(if(full) Modifier.safeDrawingPadding() else Modifier)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(if(full) "Camera settings" else "Quick settings",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick=onClose) { Text("Close") }
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Grid",Modifier.weight(1f)); Switch(checked=model.grid,onCheckedChange=model::grid)
            }
            if(model.choice.mode == CameraMode.Photo) {
                Text("Photo timer")
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf(0,3,10).forEach { seconds -> FilterChip(selected=model.timer==seconds,onClick={model.timer(seconds)},
                        label={Text(if(seconds==0) "Off" else "$seconds s")}) }
                }
            }
            if(model.choice.mode==CameraMode.SkitVideo) {
                val video=model.video
                Text("Video capture",style=MaterialTheme.typography.titleSmall)
                if(sessionOpen) Text("Finish this session to change its capture profile.",style=MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    video.qualities.forEach { quality ->
                        FilterChip(selected=video.settings.quality==quality,enabled=video.canEdit() && !video.pending,
                            onClick={video.choose(video.settings.copy(quality=quality))},label={Text(when(quality){"FHD"->"1080p";"HD"->"720p";"UHD"->"4K";else->"480p"})})
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    (listOf<Int?>(null)+video.fpsOptions).forEach { fps ->
                        FilterChip(selected=video.settings.fps==fps,enabled=video.canEdit() && !video.pending,
                            onClick={video.choose(video.settings.copy(fps=fps))},label={Text(fps?.let { "$it target" } ?: "Auto FPS")})
                    }
                }
                if(video.settings.fps==60) Text("60 is a target; frame drops can occur. Auto/30 is recommended for dialogue.",style=MaterialTheme.typography.labelSmall)
                observed?.let { facts ->
                    Text("Last finalised clip · ${facts.width} × ${facts.height} · ${facts.averageFps?.let { "%.2f fps measured".format(it) } ?: "cadence unavailable"}"+
                        if(facts.variableCadence) " · variable timing" else "",style=MaterialTheme.typography.labelSmall)
                }
                if(video.stabilizationSupported) Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Electronic stabilization",Modifier.weight(1f))
                    Switch(modifier=Modifier.semantics { contentDescription="Electronic video stabilization" },checked=video.settings.stabilization,enabled=video.canEdit() && !video.pending && (video.settings.fps ?: 30)<=30 && video.settings.quality!="UHD",
                        onCheckedChange={video.choose(video.settings.copy(stabilization=it))})
                }
                if(video.stabilizationSupported && (video.settings.fps ?: 30)>30) Text("Electronic stabilization requires Auto/30 fps or lower.",style=MaterialTheme.typography.labelSmall)
                if(video.stabilizationSupported) Text(video.stabilizationStatus,style=MaterialTheme.typography.labelSmall)
                if(full) Text("Bitrate: Auto. Codec: SDR automatic; each clip must verify as AVC/AAC.",style=MaterialTheme.typography.labelSmall)
                if(video.exposureLockSupported) TextButton(onClick=video::toggleExposureLock,enabled=adjustmentsEnabled) { Text(video.exposureLock) }
                if(video.autofocusSupported) TextButton(onClick=model::toggleFocusHold,enabled=adjustmentsEnabled) { Text(if(video.focusHold) "Focus held · Auto" else "Hold focus at last tap / centre") }
                else Text("Fixed focus",style=MaterialTheme.typography.labelSmall)
                if(full || video.settings.whiteBalance!="AUTO") {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick={expanded=true},enabled=video.canEdit() && !video.pending) { Text("White balance · ${video.settings.whiteBalance.lowercase()}") }
                        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                            video.whiteBalances.forEach { wb -> DropdownMenuItem(text={Text(wb.lowercase())},onClick={expanded=false;video.choose(video.settings.copy(whiteBalance=wb))}) }
                        }
                    }
                    Text(video.whiteBalanceStatus,style=MaterialTheme.typography.labelSmall)
                }
                video.message?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
            }
            if(model.maxZoom > model.minZoom) {
                Text("Zoom · %.1f×".format(model.zoom))
                Slider(value=model.zoom.coerceIn(model.minZoom,model.maxZoom),onValueChange=model::adjustZoom,
                    valueRange=model.minZoom..model.maxZoom,enabled=adjustmentsEnabled,modifier=Modifier.semantics { contentDescription="Zoom adjustment" })
            }
            if(model.exposureRange.first < model.exposureRange.last && model.exposureStep > 0) {
                Text("Exposure compensation · %+.2f EV".format(model.exposure * model.exposureStep))
                Slider(value=model.exposure.toFloat(),onValueChange={model.adjustExposure(it.roundToInt())},
                    valueRange=model.exposureRange.first.toFloat()..model.exposureRange.last.toFloat(),enabled=adjustmentsEnabled,
                    modifier=Modifier.semantics { contentDescription="Exposure compensation" },
                    steps=(model.exposureRange.last-model.exposureRange.first-1).coerceIn(0,100))
            }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={model.adjustExposure(model.exposure-1)},enabled=adjustmentsEnabled && model.exposure>model.exposureRange.first) { Text("− EV") }
                    TextButton(onClick={model.adjustExposure(0)},enabled=adjustmentsEnabled) { Text("Reset EV") }
                    TextButton(onClick={model.adjustExposure(model.exposure+1)},enabled=adjustmentsEnabled && model.exposure<model.exposureRange.last) { Text("+ EV") }
                }
            if(full) {
                HorizontalDivider()
                Text("${model.choice.lens.name} camera · ${model.resolution}")
                Text(if(model.choice.mode == CameraMode.Photo) "JPEG · Quality priority. Saved selfies are unmirrored. Resolution is selected from supported camera configurations."
                    else "Capture profiles stay locked for the session. FPS labels are requests, not constant-rate guarantees. Measured properties are saved after finalisation.")
                Text("Focus and zoom reset when changing cameras. Grid and timer preferences are remembered.")
                Text("SkitCam · 0.9.0",style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
