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
import androidx.compose.ui.platform.LocalDensity
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
        // Gesture values are read through updated state: keying pointerInput to the
        // trim range would cancel the drag on its own first update.
        val latestStart by rememberUpdatedState(start)
        val latestEnd by rememberUpdatedState(end)
        val latestPlay by rememberUpdatedState(play)
        val latestSpan by rememberUpdatedState(source)
        val latestWidthPx by rememberUpdatedState(widthPx)
        val latestFrame by rememberUpdatedState(frameStepMs.coerceAtLeast(1L))
        val latestSeek by rememberUpdatedState(onSeek)
        val latestTrimStart by rememberUpdatedState(onTrimStart)
        val latestTrimEnd by rememberUpdatedState(onTrimEnd)
        val latestDragStart by rememberUpdatedState(onDragStart)
        val latestDragEnd by rememberUpdatedState(onDragEnd)
        val grabPx = with(LocalDensity.current) { 24.dp.toPx() }
        val snapPx = with(LocalDensity.current) { 10.dp.toPx() }
        fun edgeX(value: Long) = value.toDouble() / latestSpan * latestWidthPx

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

        TrimEdgeHandle(
            centerX = xDp(start),
            timelineWidth = width,
            value = start,
            span = source,
            enabled = enabled,
            description = "Trim start handle",
            frameStepMs = frameStepMs,
            stepDirection = 1L,
            onStep = { onDragStart(); onTrimStart(it); onDragEnd() }
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
            onStep = { onDragStart(); onTrimEnd(it); onDragEnd() }
        )

        if (enabled) {
            // One full-width gesture surface. Dragging maps the finger's absolute
            // position to time instead of accumulating deltas, so a grip moving
            // under the finger cannot feed back into its own measurement.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Hit-test the visible 48dp boxes, which are clamped inside the
                    // timeline; a grip at 0ms sits half a target inward.
                    val startLeft = (edgeX(latestStart).toFloat() - grabPx)
                        .coerceIn(0f, (latestWidthPx - 2 * grabPx).coerceAtLeast(0f))
                    val endLeft = (edgeX(latestEnd).toFloat() - grabPx)
                        .coerceIn(0f, (latestWidthPx - 2 * grabPx).coerceAtLeast(0f))
                    val startCenter = startLeft + grabPx
                    val endCenter = endLeft + grabPx
                    val x0 = down.position.x
                    val startHit = abs(x0 - startCenter) <= grabPx
                    val endHit = abs(x0 - endCenter) <= grabPx
                    val trimming = startHit || endHit
                    val startEdge = startHit &&
                        (!endHit || abs(x0 - startCenter) <= abs(x0 - endCenter))
                    down.consume()
                    if (!trimming) {
                        latestSeek(sourceAt(down.position.x).coerceIn(latestStart, latestEnd))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { change.consume(); break }
                            latestSeek(sourceAt(change.position.x).coerceIn(latestStart, latestEnd))
                            change.consume()
                        }
                        return@awaitEachGesture
                    }
                    var dragging = false
                    var snapped = false
                    var travelled = 0f
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        travelled += abs(change.position.x - lastX)
                        lastX = change.position.x
                        if (!dragging && travelled > viewConfiguration.touchSlop) {
                            dragging = true
                            latestDragStart()
                        }
                        if (dragging) {
                            val retained = minOf(500L, latestSpan)
                            val playX = edgeX(latestPlay)
                            val thresholdPx = snapPx * if (snapped) 2f else 1f
                            val canSnap = if (startEdge) latestPlay <= latestEnd - retained
                                else latestPlay >= latestStart + retained
                            val raw = sourceAt(change.position.x)
                            // Snap to the playhead within 10dp; stay snapped until the
                            // finger leaves twice that distance, so the grip does not flicker.
                            snapped = canSnap && abs(change.position.x - playX) <= thresholdPx
                            val target = if (snapped) latestPlay else raw
                            val value = snapToFrame(target, latestFrame).coerceIn(0L, latestSpan)
                            if (startEdge) latestTrimStart(value) else latestTrimEnd(value)
                        }
                        change.consume()
                    }
                    if (dragging) {
                        latestDragEnd()
                    } else {
                        // A tap on a grip nudges that boundary inward by one frame.
                        val base = if (startEdge) latestStart else latestEnd
                        val stepped = (base + if (startEdge) latestFrame else -latestFrame)
                            .coerceIn(0L, latestSpan)
                        if (stepped != base) {
                            latestDragStart()
                            if (startEdge) latestTrimStart(stepped) else latestTrimEnd(stepped)
                            latestDragEnd()
                        }
                    }
                }
            })
        }
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
    onStep: (Long) -> Unit
) {
    val offset = (centerX - 24.dp).coerceIn(0.dp, (timelineWidth - 48.dp).coerceAtLeast(0.dp))
    // Visual/semantic target only: the timeline's single gesture surface handles
    // dragging, so this 48dp node is never moved mid-gesture.
    Box(
        Modifier.offset(x = offset).width(48.dp).fillMaxHeight()
            .semantics {
                contentDescription = description
                stateDescription = "${formatPreciseTime(value)}. Tap to step " +
                    "${if (stepDirection > 0) "later" else "earlier"} one frame; drag to adjust."
                if (enabled) customActions = listOf(
                    CustomAccessibilityAction("Move earlier one frame") {
                        onStep((value - frameStepMs).coerceAtLeast(0)); true
                    },
                    CustomAccessibilityAction("Move later one frame") {
                        onStep((value + frameStepMs).coerceIn(0L, span)); true
                    }
                )
            }
    )
}
