package com.calvinmazhindu.skitcam.arrange

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.review.*
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
fun ArrangementScreen(session: Session, editor: ArrangementViewModel, recording: RecordingViewModel,
    review: SegmentReviewController, loader: ThumbnailLoader) {
    val draft = editor.draft ?: return
    val list = rememberLazyListState()
    var listTop by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val speed = with(LocalDensity.current) { 600.dp.toPx() }
    val segments = session.segments.associateBy { it.id }
    val stale = !editor.saving && !draft.matches(session)
    LaunchedEffect(stale) { if (stale) { editor.cancelDrag(); review.close() } }

    fun moveAtPointer() {
        val id = editor.draggingId ?: return
        val current = editor.draft ?: return
        val centre = pointerY - grabOffset + rowHeight / 2f
        val target = list.layoutInfo.visibleItemsInfo.filter { it.key != id }
            .minByOrNull { abs(centre - (it.offset + it.size / 2f)) } ?: return
        val destination = current.ids.indexOf(target.key)
        val source = current.ids.indexOf(id)
        if (source >= 0 && destination >= 0 && ((destination > source && centre > target.offset + target.size / 2f) ||
                (destination < source && centre < target.offset + target.size / 2f))) {
            val first = list.firstVisibleItemIndex
            val offset = list.firstVisibleItemScrollOffset
            editor.move(id, destination)
            if (source == first || destination == first) list.requestScrollToItem(first, offset)
        }
    }
    val currentMove by rememberUpdatedState(::moveAtPointer)
    LaunchedEffect(editor.draggingId) {
        if (editor.draggingId != null) {
            var previous = withFrameNanos { it }
            while (isActive && editor.draggingId != null) {
                val time = withFrameNanos { it }
                val seconds = ((time - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                previous = time
                val height = list.layoutInfo.viewportEndOffset.toFloat()
                val ratio = when {
                    pointerY < edge -> -((edge - pointerY) / edge).coerceIn(0f, 1f)
                    pointerY > height - edge -> ((pointerY - height + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (ratio != 0f) {
                    list.scrollBy(ratio * speed * seconds)
                    currentMove()
                }
            }
        }
    }
    BackHandler {
        if (!editor.saving) {
            if (review.selection != null) review.close() else editor.cancel()
        }
    }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF101014), contentColor = Color.White) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Arrange · ${draft.ids.size} kept", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { review.close(); editor.cancel() }, enabled = !editor.saving,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", color = Color(0xFFF2D4BC)) }
                Button(onClick = { review.close(); editor.done(recording) }, enabled = !editor.saving && editor.draggingId == null && !stale,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF854C55), contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editor.saving) "Saving…" else "Done") }
            }
            Text("Playback order · hold a handle to drag, or use Move up/down.", style = MaterialTheme.typography.bodySmall)
            editor.error?.let { Text(it, color = Color(0xFFF2D4BC), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            val selected = review.selection?.takeIf { it.sessionId == session.id }?.segmentId?.let(segments::get)
            if (stale) {
                Text("The session changed. Cancel and reopen Arrange.", color = Color(0xFFF2D4BC))
            } else if (selected != null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ReviewPanel(session, selected, recording, review, readOnly = true)
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 600.dp
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()
                        .onGloballyPositioned { listTop = it.positionInWindow().y }
                        .semantics { contentDescription = "Arrangement list" },
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(draft.ids, key = { _, id -> id }) { index, id ->
                            val segment = segments[id]
                            if (segment != null) {
                                var handleTop by remember(id) { mutableFloatStateOf(0f) }
                                val dragging = editor.draggingId == id
                                val interactive = !editor.saving && editor.draggingId == null
                                val handle = Modifier.size(48.dp)
                                    .onGloballyPositioned { handleTop = it.positionInWindow().y }
                                    .clearAndSetSemantics {
                                        contentDescription = "Drag segment ${segment.order}"
                                        stateDescription = "Position ${index + 1} of ${draft.ids.size}. Hold and drag."
                                        role = Role.Button
                                        if (editor.saving) disabled()
                                        customActions = if (interactive) buildList {
                                            if (index > 0) add(CustomAccessibilityAction("Move up") { editor.move(id, index - 1); true })
                                            if (index < draft.ids.lastIndex) add(CustomAccessibilityAction("Move down") { editor.move(id, index + 1); true })
                                        } else emptyList()
                                    }.pointerInput(id, editor.saving) {
                                        if (!editor.saving) detectDragGesturesAfterLongPress(
                                            onDragStart = { point ->
                                                val info = list.layoutInfo.visibleItemsInfo.find { it.key == id }
                                                if (info != null && editor.draggingId == null) {
                                                    pointerY = handleTop + point.y - listTop
                                                    grabOffset = pointerY - info.offset
                                                    rowHeight = info.size
                                                    editor.beginDrag(id)
                                                }
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                if (editor.draggingId == id) {
                                                    pointerY += amount.y
                                                    currentMove()
                                                }
                                            },
                                            onDragEnd = { if (editor.draggingId == id) editor.endDrag() },
                                            onDragCancel = { if (editor.draggingId == id) editor.cancelDrag() })
                                    }
                                ArrangementRow(segment, index, draft.ids.size, wide, dragging, interactive, loader, handle = handle,
                                    onPreview = { review.open(session.id, segment) },
                                    onMove = { editor.move(id, it) },
                                    modifier = Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                                        translationY = if (dragging) pointerY - grabOffset -
                                            (list.layoutInfo.visibleItemsInfo.find { it.key == id }?.offset ?: 0) else 0f
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrangementRow(segment: Segment, index: Int, count: Int, wide: Boolean, dragging: Boolean,
    interactive: Boolean, loader: ThumbnailLoader, modifier: Modifier, handle: Modifier, onPreview: () -> Unit,
    onMove: (Int) -> Unit) {
    val thumbnail by produceState<ThumbnailResult>(ThumbnailResult.Loading, segment.id) { value = loader.load(segment) }
    Surface(modifier.fillMaxWidth(), color = if (dragging) Color(0xFF49313A) else Color(0xFF25252B),
        contentColor = Color.White, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    when (val frame = thumbnail) {
                        is ThumbnailResult.Ready -> Image(frame.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        ThumbnailResult.Loading -> Text("…")
                        ThumbnailResult.Unavailable -> Text("Unavailable", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. Segment ${segment.order}", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text("%02d:%02d".format(segment.durationMs / 60000, segment.durationMs / 1000 % 60), style = MaterialTheme.typography.bodySmall)
                }
                if (wide) RowActions(segment, index, count, interactive, onPreview, onMove)
                Box(handle, contentAlignment = Alignment.Center) { Text("≡", style = MaterialTheme.typography.headlineMedium) }
            }
            if (!wide) Row(Modifier.align(Alignment.End)) { RowActions(segment, index, count, interactive, onPreview, onMove) }
        }
    }
}

@Composable
private fun RowActions(segment: Segment, index: Int, count: Int, interactive: Boolean,
    onPreview: () -> Unit, onMove: (Int) -> Unit) {
    TextButton(onClick = onPreview, enabled = interactive, modifier = Modifier.heightIn(min = 48.dp)
        .semantics { contentDescription = "Preview segment ${segment.order}" }) { Text("Preview", color = Color(0xFFF2D4BC).copy(alpha = if (interactive) 1f else 0.3f)) }
    IconButton(onClick = { onMove(index - 1) }, enabled = interactive && index > 0,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Move segment ${segment.order} up" }) { Text("↑", color = Color.White.copy(alpha = if (interactive && index > 0) 1f else 0.3f)) }
    IconButton(onClick = { onMove(index + 1) }, enabled = interactive && index < count - 1,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Move segment ${segment.order} down" }) { Text("↓", color = Color.White.copy(alpha = if (interactive && index < count - 1) 1f else 0.3f)) }
}
