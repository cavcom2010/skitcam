package com.calvinmazhindu.skitcam.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.camera.CameraIcon
import com.calvinmazhindu.skitcam.camera.CameraSymbol
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamShapes
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing

@Composable
internal fun PlaybackSegmentRail(clips: List<PlaybackClip>, activeIndex: Int, onSeek: (Int) -> Unit) {
    if (activeIndex !in clips.indices) return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex)
    LaunchedEffect(activeIndex) { listState.animateScrollToItem(activeIndex) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.labelLarge
        val measurer = rememberTextMeasurer()
        // All pills have the same width, including at large font scales. A change of
        // state (and thus icon or text colour) won't move the rail under a finger.
        val digitWidth = ('0'..'9').maxOf { measurer.measure("$it", style).size.width }
        val numberWidth = with(LocalDensity.current) { (digitWidth * clips.size.toString().length).toDp() }
        val pillWidth = (numberWidth + 36.dp).coerceAtLeast(SkitCamSpacing.touchTarget).coerceAtMost(maxWidth)
        val edgePadding = ((maxWidth - pillWidth) / 2).coerceAtLeast(0.dp)
        val description = stringResource(R.string.playback_segment_rail)
        LazyRow(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            state = listState,
            contentPadding = PaddingValues(horizontal = edgePadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(clips, key = { _, clip -> clip.segmentId }) { index, _ ->
                val active = index == activeIndex
                val completed = index < activeIndex
                val name = stringResource(R.string.playback_segment_position, index + 1)
                val status = stringResource(when {
                    active -> R.string.playback_segment_current
                    completed -> R.string.playback_segment_completed
                    else -> R.string.playback_segment_upcoming
                })
                val tint = when {
                    active -> SkitCamColors.Warning
                    completed -> SkitCamColors.Success
                    else -> SkitCamColors.TextSecondary
                }
                val fill = if (completed) SkitCamColors.Success.copy(alpha = .18f) else SkitCamColors.Surface
                val jump = { onSeek(index) }
                Box(
                    modifier = Modifier.width(pillWidth).heightIn(min = SkitCamSpacing.touchTarget)
                        .clearAndSetSemantics {
                            contentDescription = name
                            stateDescription = status
                            selected = active
                            role = Role.Button
                            onClick(action = { jump(); true })
                        }
                        .clickable(onClick = jump),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                            .background(fill, SkitCamShapes.pill)
                            .then(if (active) Modifier.border(1.dp, tint, SkitCamShapes.pill) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (active || completed) {
                            CameraSymbol(if (active) CameraIcon.Play else CameraIcon.Check, Modifier.size(14.dp), tint)
                        }
                        Text("${index + 1}", color = tint, style = style, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
