package com.calvinmazhindu.skitcam.audio

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.calvinmazhindu.skitcam.camera.*

@Composable
fun AudioIndicator(monitor: AudioMonitor, modifier: Modifier=Modifier) {
    val audio by monitor.state.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(audio.health) { while(audio.health==CaptureHealth.Active) { now=SystemClock.elapsedRealtime();delay(250) } }
    val peak=audio.visiblePeak(now)
    val problem=!audio.permission || audio.systemMuted || AudioPolicy.captureFailure(audio.health) || audio.warning!=null
    Column(modifier.width(48.dp)) {
        CameraIconButton(if(problem) CameraIcon.MicOff else CameraIcon.Microphone,"Audio input details. ${audio.title}") { details=true }
        if(audio.health==CaptureHealth.Active && peak!=null) LinearProgressIndicator(progress={peak.toFloat()},modifier=Modifier.fillMaxWidth().height(3.dp)
            .semantics { contentDescription="Sampled microphone peak level; input identity unverified" })
    }
    if(details) AudioDetails(audio) { details=false }
}
@Composable
fun AudioWarning(monitor: AudioMonitor,modifier: Modifier=Modifier) {
    val audio by monitor.state.collectAsStateWithLifecycle()
    val text=audio.warning ?: if(audio.systemMuted || AudioPolicy.captureFailure(audio.health)) audio.title else null
    text?.let { Text(it,modifier.semantics { liveRegion=LiveRegionMode.Polite },color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall) }
}
@Composable
fun AudioSettings(monitor: AudioMonitor) {
    val audio by monitor.state.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    Text("Automatic · managed by Android",style=MaterialTheme.typography.bodyMedium)
    Text("Recording input is not independently confirmed.",style=MaterialTheme.typography.labelSmall)
    TextButton(onClick={details=true}) { Text("Audio input details") }
    if(details) AudioDetails(audio) { details=false }
}
@Composable
private fun AudioDetails(audio: AudioSnapshot,onClose: ()->Unit) {
    var diagnostics by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onClose,title={Text("Microphone")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(audio.title.removePrefix("Microphone · "))
            Text("Android chooses the input automatically. The active recording input is not confirmed.")
            Text("Connected inputs",style=MaterialTheme.typography.titleSmall)
            if(!audio.discoveryAvailable) Text("Discovery unavailable")
            else if(audio.inputs.isEmpty()) Text("No inputs reported")
            AudioPresentation.groups(audio.inputs).forEach { group ->
                Text(group.label)
                Text("${group.endpoints.size} Android endpoint${if(group.endpoints.size==1) "" else "s"} · connected, not confirmed active",style=MaterialTheme.typography.labelSmall)
            }
            audio.warning?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            TextButton(onClick={diagnostics=!diagnostics}) { Text(if(diagnostics) "Hide diagnostics" else "Diagnostics") }
            if(diagnostics) {
                Text("Manual input selection is unavailable with this CameraX recorder. Android observations cannot be attributed to SkitCam's audio session.",style=MaterialTheme.typography.bodySmall)
                Text("Raw endpoints",style=MaterialTheme.typography.titleSmall)
                audio.inputs.forEach { Text("ID ${it.id} · type ${it.androidType ?: "unknown"} · ${it.kind}\n${it.name}\nAddress: ${it.address?.ifBlank { null } ?: "not reported"}",style=MaterialTheme.typography.bodySmall) }
                Text("Android-reported camcorder routes",style=MaterialTheme.typography.titleSmall)
                if(audio.observations.isEmpty()) Text("No active observation")
                audio.observations.forEach { Text("Session ${it.sessionId} → endpoint ${it.device.id} (${it.device.name}); association with SkitCam unverified",style=MaterialTheme.typography.bodySmall) }
                Text("Connected devices and Android route observations do not prove the input used by SkitCam. The meter is a sampled CameraX peak, not calibrated loudness.",style=MaterialTheme.typography.bodySmall)
            }
        }
    },confirmButton={TextButton(onClick=onClose) { Text("Close") }})
}
