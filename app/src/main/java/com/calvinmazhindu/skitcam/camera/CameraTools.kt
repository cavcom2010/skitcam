package com.calvinmazhindu.skitcam.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import kotlin.math.roundToInt

@Composable
fun CameraTools(model: CameraModel, full: Boolean, adjustmentsEnabled: Boolean, onClose: () -> Unit) {
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
            if(model.maxZoom > model.minZoom) {
                Text("Zoom · %.1f×".format(model.zoom))
                Slider(value=model.zoom.coerceIn(model.minZoom,model.maxZoom),onValueChange=model::adjustZoom,
                    valueRange=model.minZoom..model.maxZoom,enabled=adjustmentsEnabled,modifier=Modifier.semantics { contentDescription="Zoom adjustment" })
            }
            if(model.exposureRange.first < model.exposureRange.last && model.exposureStep > 0) {
                Text("Exposure · %+.1f EV".format(model.exposure * model.exposureStep))
                Slider(value=model.exposure.toFloat(),onValueChange={model.adjustExposure(it.roundToInt())},
                    valueRange=model.exposureRange.first.toFloat()..model.exposureRange.last.toFloat(),enabled=adjustmentsEnabled,
                    modifier=Modifier.semantics { contentDescription="Exposure compensation" },
                    steps=(model.exposureRange.last-model.exposureRange.first-1).coerceIn(0,100))
            }
            if(full) {
                HorizontalDivider()
                Text("${model.choice.lens.name} camera · ${model.resolution}")
                Text(if(model.choice.mode == CameraMode.Photo) "JPEG · Quality priority. Saved selfies are unmirrored. Resolution is selected from supported camera configurations."
                    else "HD preference with CameraX fallback. Frame rate is automatic. Camera and orientation stay locked for the entire session.")
                Text("Focus and zoom reset when changing cameras. Grid and timer preferences are remembered.")
                Text("SkitCam · 0.8.0",style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
