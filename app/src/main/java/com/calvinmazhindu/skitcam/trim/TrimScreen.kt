package com.calvinmazhindu.skitcam.trim

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.review.*
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing
import com.calvinmazhindu.skitcam.ui.video.*
import kotlinx.coroutines.*
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrimScreen(editor: TrimViewModel, model: RecordingViewModel, review: SegmentReviewController) {
    val segment = editor.segment ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loader = remember { ThumbnailLoader() }
    val playback = rememberVideoPlaybackState(review.player)
    var precision by remember(segment.id) { mutableStateOf(false) }
    val close = {
        if (!editor.saving) {
            editor.close(); review.close()
        }
    }
    BackHandler { close() }
    LaunchedEffect(segment.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var ownedPlayer: androidx.media3.exoplayer.ExoPlayer? = null
            try {
                editor.suspendPlayback()
                val facts = withContext(Dispatchers.IO) {
                    SessionFileStore(File(segment.path).parentFile!!.parentFile!!).sourceFacts(segment.path)
                }
                review.open(editor.sessionId!!, segment, fullSource = true)
                ownedPlayer = review.player
                withTimeout(15_000) {
                    while (review.player?.playbackState != Player.STATE_READY) {
                        review.error?.let { error(it) }
                        delay(50)
                    }
                }
                val player = review.player ?: error("Player unavailable")
                check(player.isCurrentMediaItemSeekable) { "This source cannot be seeked safely." }
                editor.resolve(facts, player.duration)
                awaitCancellation()
            } catch (_: TimeoutCancellationException) {
                editor.fail("Video preparation timed out. Cancel and reopen Trim.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                editor.fail(e.message ?: "Cannot open Trim.")
            } finally {
                if (editor.segment?.id == segment.id) editor.suspendPlayback()
                if (review.player === ownedPlayer && review.selection?.segmentId == segment.id) review.close()
            }
        }
    }
    LaunchedEffect(review.error) { review.error?.let { if (editor.ready) editor.fail(it) } }
    LaunchedEffect(editor.revision, editor.ready) {
        val range = editor.draft
        if (editor.ready && range?.valid() == true) review.clip(segment, range)
    }
    val range = editor.draft
    val aspect = segmentVideoAspect(segment, review.videoAspect, 9f / 16f)
    fun togglePlayback() {
        val player = review.player ?: return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }
    val editorEnabled = editor.ready && !editor.saving
    val save = {
        editor.save(model) { review.close() }
    }
    ImmersiveVideoScaffold {
        val wide = maxWidth > maxHeight
        val railWidth = minOf(if (LocalDensity.current.fontScale > 1.5f) 360.dp else 320.dp, maxWidth * 0.45f)
        val panelMaxHeight = if (wide) maxHeight else maxHeight * 0.55f
        val panel: @Composable (Modifier) -> Unit = { modifier ->
            TrimEditorPanel(editor, segment, review, loader, range, playback, precision,
                { precision = !precision }, editorEnabled, save,
                modifier.fillMaxWidth().heightIn(max = panelMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp))
        }
        val header: @Composable () -> Unit = {
            VideoScreenHeader(
                title = "Trim segment ${segment.order}",
                subtitle = range?.let { "Source ${formatPreciseTime(it.sourceDurationMs)}" } ?: "Inspecting source…",
                closeLabel = "Cancel",
                onClose = close,
                actions = {
                    Button(
                        onClick = save,
                        enabled = editor.canSave && review.error == null,
                        modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget),
                        colors = ButtonDefaults.buttonColors(containerColor = SkitCamColors.RoseStrong,
                            contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) { Text(if (editor.saving) "Saving…" else "Save") }
                }
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    header()
                    VideoSurface(
                        player = review.player,
                        aspect = aspect,
                        contentDescription = "Trim video preview",
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        VideoTransportControls(
                            playing = playback.playing,
                            positionMs = playback.positionMs,
                            durationMs = range?.effectiveDurationMs ?: playback.durationMs,
                            enabled = editorEnabled && range?.valid() == true,
                            onPlayPause = ::togglePlayback,
                            onSeek = { review.player?.seekTo(it) },
                            showProgress = false,
                            overlay = true,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
                Column(Modifier.width(railWidth).fillMaxHeight()) {
                    panel(Modifier.fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                VideoSurface(
                    player = review.player,
                    aspect = aspect,
                    contentDescription = "Trim video preview",
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)
                ) {
                    VideoTransportControls(
                        playing = playback.playing,
                        positionMs = playback.positionMs,
                        durationMs = range?.effectiveDurationMs ?: playback.durationMs,
                        enabled = editorEnabled && range?.valid() == true,
                        onPlayPause = ::togglePlayback,
                        onSeek = { review.player?.seekTo(it) },
                        showProgress = false,
                        overlay = true,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
                panel(Modifier)
            }
        }
    }
}

@Composable
private fun TrimEditorPanel(
    editor: TrimViewModel,
    segment: Segment,
    review: SegmentReviewController,
    loader: ThumbnailLoader,
    range: TrimRange?,
    playback: VideoPlaybackState,
    precision: Boolean,
    onTogglePrecision: () -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        editor.notice?.let {
            Text(it, color = SkitCamColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
        (editor.error ?: review.error)?.let {
            Text(it, color = SkitCamColors.Error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (range != null) {
            Text(
                "Start ${formatPreciseTime(range.trimStartMs)} · End ${formatPreciseTime(range.trimEndMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = SkitCamColors.TextPrimary
            )
            Text(
                "Retained ${formatPreciseTime(if (range.valid()) range.effectiveDurationMs else 0)}",
                style = MaterialTheme.typography.labelSmall,
                color = SkitCamColors.TextSecondary
            )
            if (editor.ready) {
                TrimTimeline(
                    segment = segment,
                    range = range,
                    playhead = if (range.valid()) (range.trimStartMs + playback.positionMs)
                        .coerceAtMost(range.sourceDurationMs) else 0,
                    loader = loader,
                    enabled = enabled,
                    change = { review.player?.pause(); editor.change(it) },
                    settled = editor::settled
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { review.player?.pause(); editor.reset() },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)
                ) { Text("Reset Trim", color = SkitCamColors.Warm) }
                TextButton(
                    onClick = onTogglePrecision,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)
                ) { Text(if (precision) "Hide precision controls" else "Precision controls",
                    color = SkitCamColors.Warm) }
            }
            if (precision) {
                BoundaryControl("Start", range.trimStartMs, enabled) { value ->
                    if (range.valid()) editor.change(range.start(value))
                    else editor.change(range.copy(trimStartMs = value))
                    editor.settled()
                }
                BoundaryControl("End", range.trimEndMs, enabled) { value ->
                    if (range.valid()) editor.change(range.end(value))
                    else editor.change(range.copy(trimEndMs = value))
                    editor.settled()
                }
                Text("Preview is approximate; saved boundaries are verified milliseconds.",
                    style = MaterialTheme.typography.labelSmall, color = SkitCamColors.TextMuted)
                TextButton(onClick = onSave, enabled = editor.canSave && review.error == null,
                    modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) {
                    Text(if (editor.saving) "Saving…" else "Save", color = SkitCamColors.Warm)
                }
            }
        } else {
            Text("Inspecting source…", style = MaterialTheme.typography.bodySmall,
                color = SkitCamColors.TextSecondary)
        }
    }
}

@Composable
private fun BoundaryControl(label: String, value: Long, enabled: Boolean, change: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text("$label (ms)") }, singleLine = true,
            enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(150.dp))
        TextButton(onClick = { text.toLongOrNull()?.takeIf { it in 0..TrimRange.MAX_MS }?.let(change) },
            enabled = enabled, modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) { Text("Set $label") }
        TextButton(onClick = { change((value - 100).coerceAtLeast(0)) }, enabled = enabled,
            modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)
                .semantics { contentDescription = "$label minus 100 milliseconds" }) { Text("−100 ms") }
        TextButton(onClick = { change((value + 100).coerceAtMost(TrimRange.MAX_MS)) }, enabled = enabled,
            modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)
                .semantics { contentDescription = "$label plus 100 milliseconds" }) { Text("+100 ms") }
    }
}

@Composable
private fun TrimTimeline(segment: Segment, range: TrimRange, playhead: Long, loader: ThumbnailLoader,
    enabled: Boolean, change: (TrimRange) -> Unit, settled: () -> Unit) {
    var zoom by remember(segment.id) { mutableIntStateOf(1) }
    var pan by remember(segment.id) { mutableFloatStateOf(0f) }
    val span = (range.sourceDurationMs / zoom).coerceAtLeast(1)
    val windowStart = ((range.sourceDurationMs - span) * pan.toDouble()).toLong()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow {
            TextButton(onClick = { zoom = (zoom * 2).coerceAtMost(64) }, enabled = enabled && zoom < 64,
                modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) { Text("Zoom in") }
            TextButton(onClick = { zoom = (zoom / 2).coerceAtLeast(1) }, enabled = enabled && zoom > 1,
                modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) { Text("Zoom out") }
            TextButton(onClick = {
                pan = if (range.sourceDurationMs == span) 0f else
                    (range.trimStartMs.toDouble() / (range.sourceDurationMs - span)).toFloat().coerceIn(0f, 1f)
            }, modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) { Text("Show start") }
            TextButton(onClick = {
                pan = if (range.sourceDurationMs == span) 0f else
                    ((range.trimEndMs - span).toDouble() / (range.sourceDurationMs - span)).toFloat().coerceIn(0f, 1f)
            }, modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) { Text("Show end") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(112.dp)
            .background(Color.Black.copy(alpha = 0.35f))
            .semantics { contentDescription = "Trim timeline" }) {
            val timelineWidth = maxWidth
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            fun xFraction(ms: Long): Float =
                ((ms - windowStart).toDouble() / span).toFloat().coerceIn(0f, 1f)
            val startX = xFraction(range.trimStartMs) * timelineWidth.value
            val endX = xFraction(range.trimEndMs) * timelineWidth.value
            val playX = xFraction(playhead) * timelineWidth.value
            Row(Modifier.fillMaxSize()) {
                repeat(8) { index ->
                    val sample = windowStart + (span.toDouble() * (index + 0.5) / 8).toLong()
                    val frame by produceState<ThumbnailResult>(ThumbnailResult.Loading, segment.id, sample) {
                        value = loader.load(segment, sample)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        (frame as? ThumbnailResult.Ready)?.let {
                            Image(it.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } ?: Text("·", color = Color.White)
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val start = startX.dp.toPx()
                val end = endX.dp.toPx()
                val play = playX.dp.toPx()
                drawRect(Color.Black.copy(alpha = 0.62f), size = Size(start, size.height))
                drawRect(Color.Black.copy(alpha = 0.62f), topLeft = Offset(end, 0f),
                    size = Size((size.width - end).coerceAtLeast(0f), size.height))
                drawRect(SkitCamColors.Warm.copy(alpha = 0.18f), topLeft = Offset(start, 0f),
                    size = Size((end - start).coerceAtLeast(0f), size.height))
                drawLine(SkitCamColors.Warm, Offset(start, 0f), Offset(start, size.height), 5.dp.toPx())
                drawLine(SkitCamColors.Warm, Offset(end, 0f), Offset(end, size.height), 5.dp.toPx())
                drawLine(Color.White, Offset(play, 0f), Offset(play, size.height), 2.dp.toPx())
            }
            TrimHandle(
                centerX = startX.dp,
                timelineWidth = timelineWidth,
                value = range.trimStartMs,
                span = span,
                enabled = enabled,
                description = "Trim start handle",
                semanticsValue = formatPreciseTime(range.trimStartMs),
                onChange = { value -> change(range.start(value)) },
                onSettled = settled
            )
            TrimHandle(
                centerX = endX.dp,
                timelineWidth = timelineWidth,
                value = range.trimEndMs,
                span = span,
                enabled = enabled,
                description = "Trim end handle",
                semanticsValue = formatPreciseTime(range.trimEndMs),
                onChange = { value -> change(range.end(value)) },
                onSettled = settled
            )
        }
        if (zoom > 1) Slider(pan, { pan = it }, valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Timeline position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${formatPreciseTime(windowStart)} – ${formatPreciseTime(windowStart + span)} · ${zoom}×",
                style = MaterialTheme.typography.labelSmall, color = SkitCamColors.TextSecondary)
            Text("Retained ${formatPreciseTime(if (range.valid()) range.effectiveDurationMs else 0)}",
                style = MaterialTheme.typography.labelSmall, color = SkitCamColors.TextSecondary)
        }
    }
}

@Composable
private fun TrimHandle(centerX: Dp, timelineWidth: Dp, value: Long, span: Long, enabled: Boolean,
    description: String, semanticsValue: String, onChange: (Long) -> Unit, onSettled: () -> Unit) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    val latestSettled by rememberUpdatedState(onSettled)
    val latestSpan by rememberUpdatedState(span)
    val latestWidth by rememberUpdatedState(timelineWidth.value)
    var base by remember { mutableLongStateOf(0L) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    val offset = (centerX - 24.dp).coerceIn(0.dp, (timelineWidth - 48.dp).coerceAtLeast(0.dp))
    Box(
        Modifier.offset(x = offset).width(48.dp).fillMaxHeight()
            .semantics {
                contentDescription = description
                stateDescription = semanticsValue
                customActions = listOf(
                    CustomAccessibilityAction("Move earlier 100 milliseconds") {
                        latestChange((latestValue - 100).coerceAtLeast(0)); latestSettled(); true
                    },
                    CustomAccessibilityAction("Move later 100 milliseconds") {
                        latestChange(latestValue + 100); latestSettled(); true
                    }
                )
            }
            .pointerInput(enabled, description) {
                if (enabled) detectDragGestures(
                    onDragStart = { base = latestValue; accumulated = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        accumulated += amount.x
                        if (latestWidth > 0f) {
                            val delta = (accumulated / latestWidth * latestSpan).toLong()
                            latestChange(base + delta)
                        }
                    },
                    onDragEnd = { latestSettled() },
                    onDragCancel = { latestSettled() }
                )
            }
    )
}
