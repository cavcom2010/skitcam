package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(30.dp).alpha(if (enabled) 1f else 0.55f)
                .clip(RoundedCornerShape(9.dp))
                .background(SkitCamColors.Ink.copy(alpha = 0.64f))) {
                Canvas(Modifier.fillMaxSize()) {
                    val startX = x(start)
                    val endX = x(end)
                    // Neutral source, orange removed ranges and a green retained range.
                    drawRect(SkitCamColors.TextMuted.copy(alpha = 0.20f), size = size)
                    drawRect(SkitCamColors.Warning.copy(alpha = 0.58f), size = Size(startX, size.height))
                    drawRect(SkitCamColors.Warning.copy(alpha = 0.58f), topLeft = Offset(endX, 0f),
                        size = Size((size.width - endX).coerceAtLeast(0f), size.height))
                    drawTrimHatch(0f, startX, size.height)
                    drawTrimHatch(endX, size.width, size.height)
                    drawRect(SkitCamColors.Success.copy(alpha = 0.62f), topLeft = Offset(startX, 0f),
                        size = Size((endX - startX).coerceAtLeast(0f), size.height))
                    drawRect(SkitCamColors.Success.copy(alpha = 0.9f), topLeft = Offset(startX, 0f),
                        size = Size((endX - startX).coerceAtLeast(0f), size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    // Each grip doubles as a one-frame nudge: the left grip points
                    // right (trim inward), the right grip points left (trim inward).
                    drawGrip(startX, size.height, pointsRight = true)
                    drawGrip(endX, size.height, pointsRight = false)
                    val playX = x(play)
                    drawLine(SkitCamColors.Warm, Offset(playX, 0f), Offset(playX, size.height), 2.dp.toPx())
                    drawCircle(SkitCamColors.Ink, 6.dp.toPx(), Offset(playX, size.height / 2f))
                    drawCircle(SkitCamColors.Warm, 4.dp.toPx(), Offset(playX, size.height / 2f))
                }
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
            stepDirection = 1L,
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
            stepDirection = -1L,
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

private fun DrawScope.drawTrimHatch(left: Float, right: Float, height: Float) {
    if (right <= left) return
    val spacing = 9.dp.toPx()
    var x = left - height
    while (x < right) {
        drawLine(SkitCamColors.Ink.copy(alpha = 0.16f), Offset(x, height), Offset(x + height, 0f),
            1.dp.toPx())
        x += spacing
    }
}

private fun DrawScope.drawGrip(centerX: Float, height: Float, pointsRight: Boolean) {
    val barWidth = 14.dp.toPx()
    val barHeight = height * 0.84f
    val top = (height - barHeight) / 2f
    val cx = centerX.coerceIn(barWidth / 2f, (size.width - barWidth / 2f).coerceAtLeast(barWidth / 2f))
    drawRoundRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset(cx - barWidth / 2f - 1.dp.toPx(), top - 1.dp.toPx()),
        size = Size(barWidth + 2.dp.toPx(), barHeight + 2.dp.toPx()),
        cornerRadius = CornerRadius((barWidth + 2.dp.toPx()) / 2f))
    drawRoundRect(Color.White, topLeft = Offset(cx - barWidth / 2f, top),
        size = Size(barWidth, barHeight), cornerRadius = CornerRadius(barWidth / 2f))
    val chevronY = height / 2f
    val chevronHeight = 3.dp.toPx()
    val chevronWidth = 2.5.dp.toPx()
    val stroke = 1.6.dp.toPx()
    val tip = cx + if (pointsRight) chevronWidth else -chevronWidth
    val back = cx - if (pointsRight) chevronWidth else -chevronWidth
    drawLine(SkitCamColors.Ink, Offset(back, chevronY - chevronHeight), Offset(tip, chevronY),
        stroke, cap = StrokeCap.Round)
    drawLine(SkitCamColors.Ink, Offset(tip, chevronY), Offset(back, chevronY + chevronHeight),
        stroke, cap = StrokeCap.Round)
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
    stepDirection: Long,
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
                stateDescription = "${formatPreciseTime(latestValue)}. Tap to step " +
                    "${if (stepDirection > 0) "later" else "earlier"} one frame; drag to adjust."
                customActions = listOf(
                    CustomAccessibilityAction("Move earlier one frame") {
                        latestChange((latestValue - latestFrame).coerceAtLeast(0)); latestEnd(); true
                    },
                    CustomAccessibilityAction("Move later one frame") {
                        latestChange((latestValue + latestFrame).coerceIn(0L, latestSpan)); latestEnd(); true
                    }
                )
            }
            .pointerInput(enabled, description, stepDirection) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var dragging = false
                    var total = 0f
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        total += dx
                        if (!dragging && abs(total) > viewConfiguration.touchSlop) {
                            dragging = true
                            base = latestValue
                            accumulated = 0f
                            latestStart()
                        } else if (dragging) {
                            accumulated += dx
                            if (latestWidth > 0f) {
                                val delta = (accumulated / latestWidth * latestSpan).toLong()
                                val raw = (base + delta).coerceIn(0L, latestSpan)
                                latestChange(snapToFrame(raw, latestFrame).coerceIn(0L, latestSpan))
                            }
                        }
                        change.consume()
                    }
                    if (dragging) {
                        latestEnd()
                    } else {
                        // Tapping is an edit: pause before capturing the playhead, then
                        // step only the selected boundary.
                        latestStart()
                        val stepped = (latestValue + stepDirection * latestFrame).coerceIn(0L, latestSpan)
                        if (stepped != latestValue) latestChange(stepped)
                        latestEnd()
                    }
                }
            }
    )
}
