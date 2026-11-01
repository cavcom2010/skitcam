package com.calvinmazhindu.skitcam.trim

import android.widget.FrameLayout
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.review.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrimScreen(editor: TrimViewModel, model: RecordingViewModel, review: SegmentReviewController) {
    val segment = editor.segment ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loader = remember { ThumbnailLoader() }
    var position by remember { mutableLongStateOf(0L) }
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
    LaunchedEffect(review.player, editor.ready) {
        while (isActive && editor.ready) {
            position = review.player?.currentPosition ?: 0
            delay(50)
        }
    }
    val range = editor.draft
    Surface(Modifier.fillMaxSize(), color = Color(0xFF101014), contentColor = Color.White) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Trim segment ${segment.order}", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = close, enabled = !editor.saving) { Text("Cancel") }
                Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF854C55), contentColor = Color.White), onClick = { editor.save(model) {
                    review.close()
                } }, enabled = editor.canSave && review.error == null) { Text(if (editor.saving) "Saving…" else "Save") }
            }
            val preview: @Composable () -> Unit = {
                val player = review.player
                if (player != null) AndroidView(factory = { context ->
                    (LayoutInflater.from(context).inflate(R.layout.segment_player, FrameLayout(context), false) as PlayerView).apply {
                        useController = false; this.player = player; contentDescription = "Trim video preview"
                    }
                }, update = { it.player = player }, onRelease = { it.player = null },
                    modifier = Modifier.fillMaxWidth().height(180.dp))
            }
            val controls: @Composable () -> Unit = {
                editor.notice?.let { Text(it, color = Color(0xFFF2D4BC), style = MaterialTheme.typography.bodySmall) }
                (editor.error ?: review.error)?.let { Text(it, color = Color(0xFFF2D4BC), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (range != null) {
                    Text("Start ${timestamp(range.trimStartMs)} · End ${timestamp(range.trimEndMs)}")
                    Text("Retained ${timestamp(if (range.valid()) range.effectiveDurationMs else 0)} · end exclusive")
                    if (editor.ready) TrimTimeline(segment, range, if (range.valid()) range.trimStartMs + position else 0,
                        loader, editor.ready && !editor.saving, { review.player?.pause(); editor.change(it) }, editor::settled)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF854C55), contentColor = Color.White), onClick = {
                            review.player?.let {
                                if (it.isPlaying) it.pause() else {
                                    if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                                    it.play()
                                }
                            }
                        }, enabled = editor.ready && range.valid() && review.error == null) { Text("Play / Pause") }
                        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { review.player?.pause(); editor.reset() }, enabled = editor.ready && !editor.saving) { Text("Reset Trim") }
                    }
                    if (range.valid()) Slider(value = position.coerceIn(0, range.effectiveDurationMs).toFloat(),
                        onValueChange = { review.player?.seekTo(it.toLong().coerceIn(0, (range.effectiveDurationMs - 1).coerceAtLeast(0))) },
                        valueRange = 0f..range.effectiveDurationMs.toFloat(), enabled = editor.ready,
                        modifier = Modifier.semantics { contentDescription = "Seek retained interval" })
                    BoundaryControl("Start", range.trimStartMs, editor.ready && !editor.saving) { value ->
                        if (range.valid()) editor.change(range.start(value))
                        else editor.change(range.copy(trimStartMs = value))
                        editor.settled()
                    }
                    BoundaryControl("End", range.trimEndMs, editor.ready && !editor.saving) { value ->
                        if (range.valid()) editor.change(range.end(value))
                        else editor.change(range.copy(trimEndMs = value))
                        editor.settled()
                    }
                } else Text("Inspecting source…")
                Text("Preview is approximate; saved boundaries are milliseconds, not verified frame cuts.", style = MaterialTheme.typography.bodySmall)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                if (maxWidth >= 600.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(0.4f).align(Alignment.CenterVertically)) { preview() }
                    Column(Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { preview(); controls() }
            }

        }
    }
}

internal fun timestamp(ms: Long): String = "%02d:%02d.%03d".format(ms / 60000, ms / 1000 % 60, ms % 1000)

@Composable
private fun BoundaryControl(label: String, value: Long, enabled: Boolean, change: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text("$label (ms)") }, singleLine = true,
            enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(150.dp))
        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { text.toLongOrNull()?.takeIf { it in 0..TrimRange.MAX_MS }?.let(change) }, enabled = enabled) { Text("Set $label") }
        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { change((value - 100).coerceAtLeast(0)) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label minus 100 milliseconds" }) { Text("−100 ms") }
        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { change((value + 100).coerceAtMost(TrimRange.MAX_MS)) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label plus 100 milliseconds" }) { Text("+100 ms") }
    }
}

@Composable
private fun TrimTimeline(segment: Segment, range: TrimRange, playhead: Long, loader: ThumbnailLoader,
    enabled: Boolean, change: (TrimRange) -> Unit, settled: () -> Unit) {
    var zoom by remember(segment.id) { mutableIntStateOf(1) }
    var pan by remember(segment.id) { mutableFloatStateOf(0f) }
    val span = (range.sourceDurationMs / zoom).coerceAtLeast(1)
    val windowStart = ((range.sourceDurationMs - span) * pan.toDouble()).toLong()
    val latest by rememberUpdatedState(range)
    val update by rememberUpdatedState(change)
    Column {
        FlowRow {
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { zoom = (zoom * 2).coerceAtMost(64) }, enabled = enabled && zoom < 64) { Text("Zoom in") }
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { zoom = (zoom / 2).coerceAtLeast(1) }, enabled = enabled && zoom > 1) { Text("Zoom out") }
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { pan = if (range.sourceDurationMs == span) 0f else (range.trimStartMs.toDouble() / (range.sourceDurationMs - span)).toFloat().coerceIn(0f,1f) }) { Text("Show start") }
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)), onClick = { pan = if (range.sourceDurationMs == span) 0f else ((range.trimEndMs - span).toDouble() / (range.sourceDurationMs - span)).toFloat().coerceIn(0f,1f) }) { Text("Show end") }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(80.dp).semantics { contentDescription = "Trim timeline" }) {
            Row(Modifier.fillMaxSize()) {
                repeat(8) { index ->
                    val sample = windowStart + (span.toDouble() * (index + 0.5) / 8).toLong()
                    val frame by produceState<ThumbnailResult>(ThumbnailResult.Loading, segment.id, sample) {
                        value = loader.load(segment, sample)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        (frame as? ThumbnailResult.Ready)?.let { Image(it.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                            ?: Text("·")
                    }
                }
            }
            Canvas(Modifier.fillMaxSize().pointerInput(segment.id, enabled, windowStart, span) {
                if (enabled) {
                    var startHandle = true
                    detectDragGestures(onDragStart = { point ->
                        val startX = (latest.trimStartMs - windowStart).toDouble() / span * size.width
                        val endX = (latest.trimEndMs - windowStart).toDouble() / span * size.width
                        startHandle = abs(point.x - startX) <= abs(point.x - endX)
                    }, onDrag = { event, _ ->
                        event.consume()
                        val ms = windowStart + ((event.position.x / size.width).toDouble().coerceIn(0.0, 1.0) * span).toLong()
                        if (latest.valid()) update(if (startHandle) latest.start(ms) else latest.end(ms))
                    }, onDragEnd = settled, onDragCancel = settled)
                }
            }) {
                fun x(ms: Long) = ((ms - windowStart).toDouble() / span * size.width).toFloat().coerceIn(0f,size.width)
                val start = x(range.trimStartMs); val end = x(range.trimEndMs)
                drawRect(Color.Black.copy(alpha = 0.7f), size = Size(start, size.height))
                drawRect(Color.Black.copy(alpha = 0.7f), topLeft = Offset(end,0f), size = Size(size.width-end,size.height))
                if (range.trimStartMs in windowStart..windowStart+span) drawLine(Color(0xFFF2D4BC), Offset(start,0f), Offset(start,size.height), 6.dp.toPx())
                if (range.trimEndMs in windowStart..windowStart+span) drawLine(Color(0xFFF2D4BC), Offset(end,0f), Offset(end,size.height), 6.dp.toPx())
                drawLine(Color.White, Offset(x(playhead),0f),Offset(x(playhead),size.height),2.dp.toPx())
            }
        }
        if (zoom > 1) Slider(pan, { pan = it }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "Timeline position" })
        Text("${timestamp(windowStart)} – ${timestamp(windowStart + span)} · ${zoom}×", style = MaterialTheme.typography.labelSmall)
    }
}
