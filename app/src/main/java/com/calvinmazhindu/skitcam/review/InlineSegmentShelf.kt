package com.calvinmazhindu.skitcam.review

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.arrange.InlineReorderViewModel
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.recording.Session
import kotlinx.coroutines.isActive

/** Pointer input belongs to the viewport, not a lazy item which can be disposed during auto-scroll. */
@Composable
internal fun InlineSegmentShelf(session: Session, editor: InlineReorderViewModel, eligible: Boolean,
    loader: ThumbnailLoader, saving: Boolean, onOpen: (Segment) -> Unit, onDecide: (Segment, Boolean) -> Unit,
    playFrom: (Segment) -> (() -> Unit)?, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val byId = remember(session.segments) { session.segments.associateBy { it.id } }
    val ids = editor.draft?.ids ?: session.orderedSegmentIds
    val currentSession by rememberUpdatedState(session)
    val currentEligible by rememberUpdatedState(eligible)
    val currentIds by rememberUpdatedState(ids)
    val currentById by rememberUpdatedState(byId)
    val currentOnOpen by rememberUpdatedState(onOpen)
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var draggedHeight by remember { mutableIntStateOf(1) }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val speed = with(LocalDensity.current) { 520.dp.toPx() }
    fun unlock() {
        if (currentEligible && editor.draft == null) {
            editor.open(currentSession)
            if (editor.draft != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    fun moveAtPointer() {
        val id = editor.draggingId ?: return
        val draft = editor.draft ?: return
        val centre = pointerY - grabOffset + draggedHeight / 2f
        val source = draft.ids.indexOf(id)
        val target = list.layoutInfo.visibleItemsInfo.filter { it.key != id }.minByOrNull {
            kotlin.math.abs(centre - (it.offset + it.size / 2f))
        } ?: return
        val destination = draft.ids.indexOf(target.key)
        if (source >= 0 && destination >= 0 &&
            ((destination > source && centre > target.offset + target.size / 2f) ||
                (destination < source && centre < target.offset + target.size / 2f))) {
            val first = list.firstVisibleItemIndex
            val offset = list.firstVisibleItemScrollOffset
            editor.move(id, destination)
            if (source == first || destination == first) list.requestScrollToItem(first, offset)
        }
    }
    val currentMove by rememberUpdatedState(::moveAtPointer)
    LaunchedEffect(eligible) { if (!eligible) editor.cancelDrag() }
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
    Box(modifier.width(88.dp).fillMaxHeight().clipToBounds().background(Color.Black.copy(alpha = 0.55f))) {
        LazyColumn(state = list, userScrollEnabled = editor.draggingId == null, modifier = Modifier.fillMaxSize()
            .semantics { contentDescription = "Completed segments, vertical strip" }
            .pointerInput(editor) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val info = list.layoutInfo.visibleItemsInfo.find {
                        down.position.y >= it.offset && down.position.y < it.offset + it.size
                    }
                    val id = info?.key as? String
                    var ended = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { ended = true }
                    }
                    if (ended) {
                        if (up != null && id != null && !editor.saving) {
                            up.consume()
                            currentById[id]?.let { currentOnOpen(it) }
                        }
                    } else if (currentEligible && !editor.saving && id != null && id in currentIds) {
                        try {
                            if (currentById[id] != null) {
                                unlock()
                                if (editor.draft != null) {
                                    pointerY = down.position.y
                                    grabOffset = down.position.y - info.offset
                                    draggedHeight = info.size
                                    editor.beginDrag(id)
                                    currentEvent.changes.forEach { it.consume() }
                                    val completed = drag(down.id) { change ->
                                        if (currentEligible && editor.draggingId != null) {
                                            pointerY += change.positionChange().y
                                            change.consume()
                                            currentMove()
                                        }
                                    }
                                    if (completed) editor.endDrag() else editor.cancelDrag()
                                }
                            }
                        } finally {
                            if (editor.draggingId != null) editor.cancelDrag()
                        }
                    }
                }
            }, contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                val item = byId[id]
                if (item != null) {
                    val dragging = editor.draggingId == id
                    val actionsEnabled = eligible && !editor.saving && editor.draggingId == null
                    // A faint placeholder also avoids stale "invisible" accessibility nodes after alpha=0.
                    Box(Modifier.animateItem().alpha(if (dragging) 0.25f else 1f)) {
                        ThumbnailCard(item, false, loader, true, saving || editor.saving,
                            { onOpen(item) }, { onDecide(item, it) }, playFrom(item),
                            reorderPosition = "${index + 1} of ${ids.size}",
                            onUnlock = if (actionsEnabled && editor.draft == null) ({ unlock() }) else null,
                            onMoveUp = if (actionsEnabled && editor.draft != null && index > 0)
                                ({ editor.move(id, index - 1) }) else null,
                            onMoveDown = if (actionsEnabled && editor.draft != null && index < ids.lastIndex)
                                ({ editor.move(id, index + 1) }) else null,
                            showMenu = editor.draft == null, pointerClickEnabled = false)
                    }
                    if (dragging) Box(Modifier.fillMaxWidth().height(3.dp)
                        .background(Color(0xFFF2D4BC)).semantics { contentDescription = "Insertion position ${index + 1}" })
                }
            }
        }
        val dragged = editor.draggingId?.let(byId::get)
        if (dragged != null) {
            Box(Modifier.align(Alignment.TopStart).padding(horizontal = 4.dp).graphicsLayer {
                val height = list.layoutInfo.viewportEndOffset.toFloat()
                translationY = (pointerY - grabOffset).coerceIn(0f, (height - draggedHeight).coerceAtLeast(0f))
                shadowElevation = 12.dp.toPx()
            }.clearAndSetSemantics {}) {
                ThumbnailCard(dragged, false, loader, true, true, {}, {}, showMenu = false, pointerClickEnabled = false)
            }
        }
    }
}
