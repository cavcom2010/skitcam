package com.calvinmazhindu.skitcam.playback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.video.*

@Composable
fun SkitPlaybackScreen(controller: SkitPlaybackController, modifier: Modifier = Modifier, onClose: () -> Unit) {
    BackHandler { onClose() }
    val plan = controller.plan
    val clip = plan?.clips?.getOrNull(controller.currentIndex)
    val playback = rememberVideoPlaybackState(controller.player)
    fun togglePlayback() {
        if (controller.finished) controller.replay()
        else controller.setPlayWhenReady(!playback.playing)
    }
    ImmersiveVideoScaffold(modifier) {
        val wide = maxWidth > maxHeight
        val aspect = controller.videoAspect ?: if (wide) 16f / 9f else 9f / 16f
        Column(Modifier.fillMaxSize()) {
            VideoScreenHeader(
                title = "Play skit",
                subtitle = clip?.let { "Clip ${controller.currentIndex + 1} of ${plan.clips.size} · Segment ${it.order}" },
                closeContentDescription = "Close playback",
                onClose = onClose
            )
            controller.error?.let {
                Text(it, color = SkitCamColors.Error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            VideoSurface(
                player = controller.player,
                aspect = aspect,
                contentDescription = "Skit playback",
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)
            ) {
                if (controller.player == null) {
                    Text(
                        if (controller.error == null) "Preparing playback…" else "Playback stopped.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SkitCamColors.TextSecondary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                FilterChip(
                    selected = controller.loop,
                    onClick = controller::toggleLoop,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .heightIn(min = 48.dp),
                    label = {
                        Text(if (controller.loop) "Loop from clip ${plan?.anchorOrder ?: 1}" else "Loop off",
                            maxLines = 1)
                    }
                )
                if (controller.finished && controller.player != null) {
                    TextButton(
                        onClick = controller::replay,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .heightIn(min = 48.dp)
                    ) { Text("Replay", color = SkitCamColors.Warm) }
                }
                VideoTransportControls(
                    playing = playback.playing,
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    enabled = controller.player != null,
                    onPlayPause = ::togglePlayback,
                    onSeek = controller::seekTo,
                    overlay = true,
                    seekContentDescription = "Seek skit playback",
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}
