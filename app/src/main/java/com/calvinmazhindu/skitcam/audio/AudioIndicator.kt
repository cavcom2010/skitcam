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

@Composable
fun AudioIndicator(monitor: AudioMonitor, modifier: Modifier = Modifier) {
    val audio by monitor.state.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(audio.health) {
        while(audio.health == CaptureHealth.Active) { now = SystemClock.elapsedRealtime();delay(250) }
    }
    val peak = audio.visiblePeak(now)
    Column(modifier.widthIn(max = 400.dp)) {
        TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "Audio input details. ${audio.title}" }) {
            Text(audio.title, style = MaterialTheme.typography.labelMedium)
        }
        if(audio.health == CaptureHealth.Active) {
            if(peak != null) LinearProgressIndicator(progress = { peak.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp)
                .semantics { contentDescription = "Sampled microphone peak level" })
            else Text("Audio level unavailable", style = MaterialTheme.typography.labelSmall)
        }
        audio.warning?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
    if(details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Audio input") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(audio.title)
            Text("Routing: Automatic — managed by Android. Manual microphone selection is not supported by this recording pipeline.")
            Text("Connected recording inputs")
            if(!audio.discoveryAvailable) Text("Device discovery unavailable")
            else if(audio.inputs.isEmpty()) Text("No recording inputs reported")
            audio.inputs.forEach { Text("${it.kind.name}: ${it.name} · connected, not confirmed active") }
            Text("Android-reported camcorder inputs")
            if(audio.observations.isEmpty()) Text("Unavailable or not recording")
            audio.observations.distinctBy { it.device.id }.forEach { Text("${it.device.name} · association with SkitCam unverified") }
            Text("A connected receiver does not prove that its microphone is recording. To use the phone microphone, disconnect external inputs, resume explicitly and check a test recording.")
            Text("The peak meter is sampled from CameraX while recording. It is not a calibrated loudness meter and cannot identify the microphone.")
            audio.warning?.let { Text(it) }
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
}
