package com.calvinmazhindu.skitcam.playback

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.camera.CameraIcon
import com.calvinmazhindu.skitcam.camera.CameraIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SkitPlaybackScreen(controller: SkitPlaybackController, modifier: Modifier = Modifier, onClose: () -> Unit) {
    BackHandler { onClose() }
    val plan = controller.plan
    val clip = plan?.clips?.getOrNull(controller.currentIndex)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(controller.player, controller.finished, controller.currentIndex) {
        while (isActive && controller.player != null && !controller.finished) {
            position = controller.positionMs
            duration = controller.durationMs
            playing = controller.player?.isPlaying == true
            delay(200)
        }
        position = controller.positionMs
        duration = controller.durationMs
        playing = controller.player?.isPlaying == true
    }
    Surface(modifier.fillMaxSize(), color = com.calvinmazhindu.skitcam.ui.theme.SkitCamColors.Ink,
        contentColor = com.calvinmazhindu.skitcam.ui.theme.SkitCamColors.TextPrimary) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CameraIconButton(CameraIcon.Back, "Close playback") { onClose() }
                Column(Modifier.weight(1f)) {
                    Text("Play skit", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (clip != null) {
                        Text("Clip ${controller.currentIndex + 1} of ${plan.clips.size} · Segment ${clip.order}",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Close playback" }) {
                    Text("Close", color = Color(0xFFF2D4BC))
                }
            }
            if (controller.error != null) {
                Text(controller.error!!, color = Color(0xFFF2D4BC), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            val player = controller.player
            if (player != null) {
                AndroidView(factory = { context ->
                    (LayoutInflater.from(context).inflate(R.layout.segment_player, FrameLayout(context), false) as PlayerView).apply {
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowFastForwardButton(false)
                        setShowRewindButton(false)
                        useController = false
                        contentDescription = "Skit playback"
                        this.player = player
                    }
                }, update = { it.player = player }, onRelease = { it.player = null },
                    modifier = Modifier.fillMaxWidth().weight(1f))
            } else {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (controller.error == null) "Preparing playback…" else "Playback stopped.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (controller.finished) controller.replay()
                    else controller.setPlayWhenReady(!playing)
                }, enabled = controller.player != null, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { contentDescription = if (playing) "Pause skit" else "Play skit" }) {
                    Text(if (playing) "Pause" else "Play")
                }
                Slider(value = position.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = { position = it.toLong() },
                    onValueChangeFinished = { controller.seekTo(position) },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = duration > 0 && controller.player != null,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Seek skit playback" })
                Text("${time(position)} / ${time(duration)}", style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = controller.loop, onClick = controller::toggleLoop,
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(if (controller.loop) "Loop from clip ${plan?.anchorOrder ?: 1}" else "Loop off") })
            }
            if (controller.finished && player != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Finished", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Button(onClick = controller::replay, modifier = Modifier.heightIn(min = 48.dp)) { Text("Replay") }
                }
            }
        }
    }
}

private fun time(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60000, safe / 1000 % 60)
}
