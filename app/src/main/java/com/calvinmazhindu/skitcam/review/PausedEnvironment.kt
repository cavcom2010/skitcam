package com.calvinmazhindu.skitcam.review

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.recording.*
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PausedEnvironment(session: Session, landscape: Boolean, model: RecordingViewModel,
    review: SegmentReviewController, loader: ThumbnailLoader, modifier: Modifier = Modifier,
    onTrim: (Segment) -> Unit = {}, onPlayFromBeginning: (() -> Unit)? = null,
    onPlayFrom: ((Segment) -> Unit)? = null) {
    val selected = review.selection?.takeIf { it.sessionId == session.id }
    val segment = session.segments.find { it.id == selected?.segmentId }
    BackHandler(enabled = selected != null) { review.close() }
    val thumbnailsEnabled = selected == null
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var undo by remember { mutableStateOf<Segment?>(null) }
    val playEnabled = session.retainedCount > 0 && session.validPlaybackOrder()
    fun decide(item: Segment, kept: Boolean) {
        if (review.saving) return
        review.decideById(model, session.id, item.id, kept) { result ->
            if (!result.success) {
                result.message?.let { message -> scope.launch { snackbar.showSnackbar(message) } }
            } else if (kept) {
                if (undo?.id == item.id) undo = null
                scope.launch { snackbar.showSnackbar("Restored") }
            } else {
                undo = item
                scope.launch {
                    val action = snackbar.showSnackbar(
                        message = "Discarded · original file retained",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short)
                    if (action == SnackbarResult.ActionPerformed && undo?.id == item.id && !review.saving) {
                        review.decideById(model, session.id, item.id, true) { undo = null }
                    }
                }
            }
        }
    }
    fun trimIfReadable(item: Segment): (() -> Unit)? =
        if (File(item.path).let { it.isFile && it.canRead() && it.length() > 0 }) {
            { onTrim(item) }
        } else null
    fun playFrom(item: Segment): (() -> Unit)? =
        if (item.kept && onPlayFrom != null) ({ onPlayFrom(item) }) else null

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val clipCount = session.segments.size
                    Text("${session.retainedCount} kept · $clipCount ${if (clipCount == 1) "clip" else "clips"}",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Button(onClick = { onPlayFromBeginning?.invoke() },
                        enabled = playEnabled && onPlayFromBeginning != null && !review.saving,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Play skit from beginning" }) {
                        Text("Play skit")
                    }
                }
                if (!playEnabled) Text("No kept clips to play.", style = MaterialTheme.typography.labelSmall)
            }
            if (landscape) {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (segment != null) ReviewPanel(session, segment, model, review,
                            onTrim = trimIfReadable(segment), onPlayFrom = playFrom(segment))
                    }
                    LazyRow(Modifier.fillMaxWidth().height(112.dp).background(Color.Black.copy(alpha = 0.55f))
                        .padding(4.dp).semantics { contentDescription = "Completed segments, horizontal strip" },
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(session.segments, key = { it.id }) { item ->
                            ThumbnailCard(item, true, loader, thumbnailsEnabled, review.saving,
                                { review.open(session.id, item) }, { decide(item, it) }, playFrom(item))
                        }
                    }
                }
            } else {
                Row(Modifier.weight(1f)) {
                    LazyColumn(Modifier.width(88.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f))
                        .padding(4.dp).semantics { contentDescription = "Completed segments, vertical strip" },
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(session.segments, key = { it.id }) { item ->
                            ThumbnailCard(item, false, loader, thumbnailsEnabled, review.saving,
                                { review.open(session.id, item) }, { decide(item, it) }, playFrom(item))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp), contentAlignment = Alignment.Center) {
                        if (segment != null) ReviewPanel(session, segment, model, review,
                            onTrim = trimIfReadable(segment), onPlayFrom = playFrom(segment))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

@Composable
private fun ThumbnailCard(segment: Segment, landscape: Boolean, loader: ThumbnailLoader,
    enabled: Boolean, saving: Boolean, onOpen: () -> Unit, onDecide: (Boolean) -> Unit,
    onPlayFrom: (() -> Unit)? = null) {
    val thumbnail by produceState<ThumbnailResult>(ThumbnailResult.Loading, segment.path, enabled) {
        if (enabled) value = loader.load(segment)
    }
    var actions by remember(segment.id) { mutableStateOf(false) }
    val disposition = if (segment.kept) "Kept" else "Discarded"
    val availability = if (thumbnail == ThumbnailResult.Unavailable) ", video unavailable" else ""
    Box(Modifier.width(if (landscape) 112.dp else 80.dp).heightIn(min = 100.dp)) {
        Card(onClick = onOpen, enabled = !saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).clearAndSetSemantics {
                contentDescription = "Review segment ${segment.order}, $disposition$availability"
                stateDescription = when (thumbnail) {
                    is ThumbnailResult.Ready -> "Thumbnail ready"
                    ThumbnailResult.Loading -> "Loading thumbnail"
                    ThumbnailResult.Unavailable -> "Thumbnail unavailable"
                }
                role = Role.Button
                onClick(label = "Review segment ${segment.order}") { onOpen(); true }
            }, colors = CardDefaults.cardColors(containerColor = Color(0xFF25252B), contentColor = Color.White)) {
            Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(50.dp).alpha(if (segment.kept) 1f else 0.45f),
                    contentAlignment = Alignment.Center) {
                    when (val frame = thumbnail) {
                        is ThumbnailResult.Ready -> Image(frame.bitmap.asImageBitmap(), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        ThumbnailResult.Loading -> Text("…", color = Color.White)
                        ThumbnailResult.Unavailable -> Text("Unavailable", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("${segment.order} · ${segment.displayDurationMs / 1000}s", style = MaterialTheme.typography.labelSmall)
                Text(disposition, style = MaterialTheme.typography.labelSmall,
                    color = if (segment.kept) Color.White else Color(0xFFF2D4BC))
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { actions = true }, enabled = !saving,
                modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.45f))
                    .semantics { contentDescription = "More actions for segment ${segment.order}" }) {
                Text("⋮", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                if (segment.kept) {
                    DropdownMenuItem(text = { Text("Discard") }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = "Discard segment ${segment.order}" },
                        onClick = { actions = false; onDecide(false) })
                } else {
                    DropdownMenuItem(text = { Text("Restore") }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = "Restore segment ${segment.order}" },
                        onClick = { actions = false; onDecide(true) })
                }
                if (segment.kept && onPlayFrom != null) {
                    DropdownMenuItem(text = { Text("Play from here") }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = "Play from segment ${segment.order}" },
                        onClick = { actions = false; onPlayFrom() })
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ReviewPanel(session: Session, segment: Segment, model: RecordingViewModel,
    review: SegmentReviewController, readOnly: Boolean = false, onTrim: (() -> Unit)? = null,
    onPlayFrom: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp)) {
        val videoHeight = (maxHeight * 0.45f).coerceIn(64.dp, 240.dp)
        Surface(color = Color(0xF218181E), contentColor = Color.White, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (readOnly) "Preview segment ${segment.order}" else
                    "Review segment ${segment.order} · ${if (segment.kept) "Kept" else "Discarded"}",
                    style = MaterialTheme.typography.titleSmall)
                Text(if (segment.invalidTrimJson != null) "Trim needs attention" else
                    "${segment.displayDurationMs} ms${if (segment.trim?.isTrimmed == true) " · Trimmed" else if (segment.trim == null) " · estimated" else ""}",
                    style = MaterialTheme.typography.labelSmall)
                val player = review.player
                if (player != null) {
                    AndroidView(factory = { context ->
                        (LayoutInflater.from(context).inflate(R.layout.segment_player, FrameLayout(context), false) as PlayerView).apply {
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            setShowFastForwardButton(false)
                            setShowRewindButton(false)
                            contentDescription = "Segment ${segment.order} playback"
                            controllerShowTimeoutMs = 0
                            this.player = player
                        }
                    }, update = { it.player = player }, onRelease = { it.player = null },
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = videoHeight))
                    Text(review.playbackLabel, style = MaterialTheme.typography.labelSmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!readOnly) {
                    if (onTrim != null) TextButton(onClick = onTrim, enabled = !review.saving,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Trim") }
                    if (segment.kept) {
                        Button(onClick = { review.decide(model, false) }, enabled = !review.saving,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { selected = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF854C55), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Discard") }
                    } else {
                        Button(onClick = { review.decide(model, true) }, enabled = !review.saving,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { selected = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF854C55), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Restore") }
                    }
                    if (segment.kept && onPlayFrom != null) TextButton(onClick = onPlayFrom, enabled = !review.saving,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Play from here") }
                    }
                    TextButton(onClick = { review.close() }, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)),
                        contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (readOnly) "Back to Arrange" else "Close") }
                }
                if (!segment.kept && session.retainedCount >= RecordingEngine.MAX_SEGMENTS) {
                    Text("100 kept segments. Discard another to restore this one.", style = MaterialTheme.typography.bodySmall)
                }
                review.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF2D4BC),
                        modifier = Modifier.heightIn(max = 72.dp).verticalScroll(rememberScrollState())
                            .semantics { liveRegion = LiveRegionMode.Polite })
                }
                val message = if (review.saving) "Saving decision…" else review.feedback
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF2D4BC), textAlign = TextAlign.Start,
                    modifier = Modifier.heightIn(max = 48.dp).verticalScroll(rememberScrollState())
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
