package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

/**
 * One-line trim control. The full source is the track; the retained range is green,
 * trimmed-out ranges are orange, and the playhead is a thin light line. Handles are
 * draggable and expose 48 dp touch targets without adding editor controls.
 */
@Composable
fun VideoTrimBar(
    positionMs: Long,
    sourceDurationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onTrimStart: (Long) -> Unit,
    onTrimEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
    frameStepMs: Long = 33L,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {}
) {
    val source = sourceDurationMs.coerceAtLeast(1L)
    val start = trimStartMs.coerceIn(0L, source)
    val end = trimEndMs.coerceIn(start, source)
    val play = positionMs.coerceIn(0L, source)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(48.dp).semantics {
            contentDescription = "Trim timeline"
            stateDescription = "Retained ${formatPreciseTime(start)} to ${formatPreciseTime(end)}. " +
                "Playhead ${formatPreciseTime(play)}"
        }
    ) {
        val width = maxWidth
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun x(ms: Long) = (ms.toDouble() / source * widthPx).toFloat()
        fun xDp(ms: Long) = (ms.toDouble() / source * width.value).toFloat().dp
        fun sourceAt(px: Float) = (px / widthPx * source).toLong().coerceIn(0L, source)

        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50))
            .background(SkitCamColors.TextMuted.copy(alpha = 0.32f))) {
            Canvas(Modifier.fillMaxSize()) {
                val startX = x(start)
                val endX = x(end)
                drawRect(SkitCamColors.Warning.copy(alpha = 0.62f), size = Size(startX, size.height))
                drawRect(SkitCamColors.Warning.copy(alpha = 0.62f), topLeft = Offset(endX, 0f),
                    size = Size((size.width - endX).coerceAtLeast(0f), size.height))
                drawRect(SkitCamColors.Success.copy(alpha = 0.62f), topLeft = Offset(startX, 0f),
                    size = Size((endX - startX).coerceAtLeast(0f), size.height))
                val playX = x(play)
                drawLine(SkitCamColors.Warm, Offset(playX, 0f), Offset(playX, size.height), 3.dp.toPx())
                drawGrip(startX, size.height)
                drawGrip(endX, size.height)
                drawCircle(SkitCamColors.Warm, 5.dp.toPx(), Offset(playX, size.height / 2f))
                drawCircle(SkitCamColors.Ink, 1.5.dp.toPx(), Offset(playX, size.height / 2f))
            }
        }

        if (enabled) {
            Box(Modifier.fillMaxSize().pointerInput(source, start, end) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSeek(sourceAt(down.position.x).coerceIn(start, end))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        onSeek(sourceAt(change.position.x).coerceIn(start, end))
                        change.consume()
                    }
                }
            })
        }

        TrimEdgeHandle(
            centerX = xDp(start),
            timelineWidth = width,
            value = start,
            span = source,
            enabled = enabled,
            description = "Trim start handle",
            frameStepMs = frameStepMs,
            onChange = onTrimStart,
            onDragStart = onDragStart,
            onDragEnd = onDragEnd
        )
        TrimEdgeHandle(
            centerX = xDp(end),
            timelineWidth = width,
            value = end,
            span = source,
            enabled = enabled,
            description = "Trim end handle",
            frameStepMs = frameStepMs,
            onChange = onTrimEnd,
            onDragStart = onDragStart,
            onDragEnd = onDragEnd
        )
    }
}

internal fun frameStepMs(averageFps: Double?): Long {
    val fps = averageFps?.takeIf { it > 1.0 } ?: 30.0
    return (1_000.0 / fps).roundToLong().coerceIn(1L, 100L)
}

internal fun snapToFrame(valueMs: Long, frameStepMs: Long): Long {
    val step = frameStepMs.coerceAtLeast(1L)
    return (valueMs.toDouble() / step).roundToLong() * step
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrip(centerX: Float, height: Float) {
    val barWidth = 14.dp.toPx()
    val barHeight = height * 0.78f
    val top = (height - barHeight) / 2f
    drawRoundRect(Color.White, topLeft = Offset(centerX - barWidth / 2f, top),
        size = Size(barWidth, barHeight), cornerRadius = CornerRadius(barWidth / 2f))
    val chevronY = height / 2f
    val chevronHeight = 3.dp.toPx()
    val chevronWidth = 2.dp.toPx()
    val stroke = 1.5.dp.toPx()
    drawLine(SkitCamColors.Ink, Offset(centerX - 1.5.dp.toPx(), chevronY - chevronHeight),
        Offset(centerX - 1.5.dp.toPx() - chevronWidth, chevronY), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(SkitCamColors.Ink, Offset(centerX - 1.5.dp.toPx() - chevronWidth, chevronY),
        Offset(centerX - 1.5.dp.toPx(), chevronY + chevronHeight), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(SkitCamColors.Ink, Offset(centerX + 1.5.dp.toPx(), chevronY - chevronHeight),
        Offset(centerX + 1.5.dp.toPx() + chevronWidth, chevronY), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(SkitCamColors.Ink, Offset(centerX + 1.5.dp.toPx() + chevronWidth, chevronY),
        Offset(centerX + 1.5.dp.toPx(), chevronY + chevronHeight), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

@Composable
private fun TrimEdgeHandle(
    centerX: Dp,
    timelineWidth: Dp,
    value: Long,
    span: Long,
    enabled: Boolean,
    description: String,
    frameStepMs: Long,
    onChange: (Long) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit
) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    val latestStart by rememberUpdatedState(onDragStart)
    val latestEnd by rememberUpdatedState(onDragEnd)
    val latestSpan by rememberUpdatedState(span)
    val latestWidth by rememberUpdatedState(timelineWidth.value)
    val latestFrame by rememberUpdatedState(frameStepMs.coerceAtLeast(1L))
    var base by remember { mutableLongStateOf(0L) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    val offset = (centerX - 24.dp).coerceIn(0.dp, (timelineWidth - 48.dp).coerceAtLeast(0.dp))
    Box(
        Modifier.offset(x = offset).width(48.dp).fillMaxHeight()
            .semantics {
                contentDescription = description
                stateDescription = formatPreciseTime(latestValue)
                customActions = listOf(
                    CustomAccessibilityAction("Move earlier one frame") {
                        latestChange((latestValue - latestFrame).coerceAtLeast(0)); latestEnd(); true
                    },
                    CustomAccessibilityAction("Move later one frame") {
                        latestChange(latestValue + latestFrame); latestEnd(); true
                    }
                )
            }
            .pointerInput(enabled, description) {
                if (enabled) detectDragGestures(
                    onDragStart = { base = latestValue; accumulated = 0f; latestStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        accumulated += amount.x
                        if (latestWidth > 0f) {
                            val delta = (accumulated / latestWidth * latestSpan).toLong()
                            val raw = (base + delta).coerceIn(0L, latestSpan)
                            latestChange(snapToFrame(raw, latestFrame).coerceIn(0L, latestSpan))
                        }
                    },
                    onDragEnd = { latestEnd() },
                    onDragCancel = { latestEnd() }
                )
            }
    )
}
