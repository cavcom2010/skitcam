package com.calvinmazhindu.skitcam.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.calvinmazhindu.skitcam.camera.ViewfinderGeometry
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.video.ImmersiveVideoScaffold
import com.calvinmazhindu.skitcam.ui.video.VideoScreenHeader
import com.calvinmazhindu.skitcam.ui.video.VideoSurface
import com.calvinmazhindu.skitcam.ui.video.VideoTransportControls
import com.calvinmazhindu.skitcam.ui.video.formatClipDuration
import com.calvinmazhindu.skitcam.ui.video.rememberVideoPlaybackState
import com.calvinmazhindu.skitcam.ui.video.segmentVideoAspect
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PausedEnvironment(session: Session, landscape: Boolean, model: RecordingViewModel,
    review: SegmentReviewController, loader: ThumbnailLoader, modifier: Modifier = Modifier,
    onTrim: (Segment) -> Unit = {}, onPlayFromBeginning: (() -> Unit)? = null,
    onPlayFrom: ((Segment) -> Unit)? = null, immersiveReview: Boolean = true) {
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
        if (immersiveReview && segment != null) {
            ImmersiveReview(session, segment, model, review, loader,
                onTrim = trimIfReadable(segment), onPlayFrom = playFrom(segment),
                onDecide = { item, kept -> decide(item, kept) },
                playFrom = { item -> playFrom(item) })
        } else {
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
                                onTrim = trimIfReadable(segment), onPlayFrom = playFrom(segment), horizontal = true)
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
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

private fun segmentStatusColor(kept: Boolean) = if (kept) SkitCamColors.Success else SkitCamColors.Error

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
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
                .border(1.5.dp, segmentStatusColor(segment.kept), MaterialTheme.shapes.small)
                .clearAndSetSemantics {
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
                Text("${segment.order} · ${formatClipDuration(segment.displayDurationMs)}", style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun ImmersiveReview(session: Session, segment: Segment, model: RecordingViewModel,
    review: SegmentReviewController, loader: ThumbnailLoader,
    onTrim: (() -> Unit)?, onPlayFrom: (() -> Unit)?, onDecide: (Segment, Boolean) -> Unit,
    playFrom: (Segment) -> (() -> Unit)?) {
    val density = LocalDensity.current
    val playback = rememberVideoPlaybackState(review.player)
    fun togglePlayback() {
        val player = review.player ?: return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }
    @Composable
    fun videoControls(modifier: Modifier = Modifier) {
        VideoTransportControls(
            playing = playback.playing,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            enabled = review.player != null && review.error == null,
            onPlayPause = ::togglePlayback,
            onSeek = { review.player?.seekTo(it) },
            overlay = true,
            seekContentDescription = "Seek segment ${segment.order}",
            modifier = modifier
        )
    }
    ImmersiveVideoScaffold {
        val wide = maxWidth > maxHeight
        val aspect = segmentVideoAspect(segment, review.videoAspect, if (wide) 16f / 9f else 9f / 16f)
        val shelfHeight = if (density.fontScale > 1.5f) 116.dp else 100.dp
        if (wide) {
            val railWidth = minOf(if (density.fontScale > 1.5f) 240.dp else 200.dp, maxWidth * 0.38f)
            Row(Modifier.fillMaxSize()) {
                VideoSurface(
                    player = review.player,
                    aspect = aspect,
                    contentDescription = "Segment ${segment.order} playback",
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(6.dp)
                ) { videoControls(Modifier.align(Alignment.BottomCenter)) }
                Column(Modifier.width(railWidth).fillMaxHeight().padding(end = 4.dp)) {
                    VideoScreenHeader(
                        title = reviewTitle(segment, false),
                        subtitle = reviewSubtitle(segment),
                        onClose = { review.close() }
                    )
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)) {
                        item {
                            ReviewActions(session, segment, model, review, false, onTrim, onPlayFrom,
                                showClose = false)
                        }
                        item { Text("Clips", style = MaterialTheme.typography.labelSmall) }
                        items(session.segments, key = { it.id }) { item ->
                            ReviewThumbnailCard(item, loader, true, review.saving,
                                { review.open(session.id, item) }, { onDecide(item, it) }, playFrom(item),
                                row = true, selected = item.id == segment.id, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                VideoScreenHeader(
                    title = reviewTitle(segment, false),
                    subtitle = reviewSubtitle(segment),
                    onClose = { review.close() }
                )
                VideoSurface(
                    player = review.player,
                    aspect = aspect,
                    contentDescription = "Segment ${segment.order} playback",
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)
                ) { videoControls(Modifier.align(Alignment.BottomCenter)) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    ReviewActions(session, segment, model, review, false, onTrim, onPlayFrom,
                        showClose = false)
                }
                ReviewShelfRow(session, segment.id, review, loader, shelfHeight, onDecide, playFrom)
            }
        }
    }
}

@Composable
private fun ReviewShelfRow(session: Session, selectedId: String, review: SegmentReviewController,
    loader: ThumbnailLoader, height: Dp, onDecide: (Segment, Boolean) -> Unit,
    playFrom: (Segment) -> (() -> Unit)?) {
    LazyRow(Modifier.fillMaxWidth().height(height).background(Color.Black.copy(alpha = 0.55f))
        .padding(4.dp).semantics { contentDescription = "Completed segments, horizontal strip" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(session.segments, key = { it.id }) { item ->
            ReviewThumbnailCard(item, loader, true, review.saving,
                { review.open(session.id, item) }, { onDecide(item, it) }, playFrom(item),
                row = false, selected = item.id == selectedId, modifier = Modifier.fillMaxHeight().width(96.dp))
        }
    }
}

@Composable
private fun ReviewThumbnailCard(segment: Segment, loader: ThumbnailLoader, enabled: Boolean,
    saving: Boolean, onOpen: () -> Unit, onDecide: (Boolean) -> Unit,
    onPlayFrom: (() -> Unit)?, row: Boolean, selected: Boolean = false,
    modifier: Modifier = Modifier) {
    val thumbnail by produceState<ThumbnailResult>(ThumbnailResult.Loading, segment.path, enabled) {
        if (enabled) value = loader.load(segment)
    }
    var actions by remember(segment.id) { mutableStateOf(false) }
    val disposition = if (segment.kept) "Kept" else "Discarded"
    val availability = if (thumbnail == ThumbnailResult.Unavailable) ", video unavailable" else ""
    Box(modifier.then(if (selected) Modifier.border(2.dp, SkitCamColors.Warm,
        MaterialTheme.shapes.small).padding(2.dp) else Modifier)) {
        Card(onClick = onOpen, enabled = !saving,
            border = BorderStroke(1.5.dp, segmentStatusColor(segment.kept)),
            modifier = Modifier.fillMaxWidth()
                .then(if (row) Modifier.heightIn(min = 68.dp) else Modifier.fillMaxSize())
                .clearAndSetSemantics {
                    contentDescription = "Review segment ${segment.order}, $disposition$availability"
                    this.selected = selected
                    stateDescription = buildString {
                        val ready = when (thumbnail) {
                            is ThumbnailResult.Ready -> "Thumbnail ready"
                            ThumbnailResult.Loading -> "Loading thumbnail"
                            ThumbnailResult.Unavailable -> "Thumbnail unavailable"
                        }
                        if (selected) append("Selected. ")
                        append(ready)
                    }
                    role = Role.Button
                    onClick(label = "Review segment ${segment.order}") { onOpen(); true }
                }, colors = CardDefaults.cardColors(
                    containerColor = if (selected) SkitCamColors.SurfacePressed else Color(0xFF25252B),
                    contentColor = Color.White)) {
            if (row) Row(Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThumbnailFrame(thumbnail, Modifier.size(52.dp), dimmed = !segment.kept)
                Column(Modifier.weight(1f)) {
                    Text("${segment.order} · ${formatClipDuration(segment.displayDurationMs)}",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ThumbnailMenu(segment, saving, actions, { actions = true }, { actions = false }, onDecide, onPlayFrom)
            } else {
                Column(Modifier.fillMaxSize().padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ThumbnailFrame(thumbnail, Modifier.fillMaxWidth().height(48.dp), dimmed = !segment.kept)
                    Text("${segment.order} · ${formatClipDuration(segment.displayDurationMs)}",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!row) Box(Modifier.align(Alignment.TopEnd)) {
            ThumbnailMenu(segment, saving, actions, { actions = true }, { actions = false }, onDecide, onPlayFrom)
        }
    }
}

@Composable
private fun ThumbnailFrame(thumbnail: ThumbnailResult, modifier: Modifier, dimmed: Boolean = false) {
    val baseAlpha = if (thumbnail is ThumbnailResult.Ready) 1f else 0.7f
    Box(modifier.alpha(if (dimmed) baseAlpha * 0.45f else baseAlpha), contentAlignment = Alignment.Center) {
        when (thumbnail) {
            is ThumbnailResult.Ready -> Image(thumbnail.bitmap.asImageBitmap(), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            ThumbnailResult.Loading -> Text("…", color = Color.White)
            ThumbnailResult.Unavailable -> Text("Unavailable", color = Color.White,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ThumbnailMenu(segment: Segment, saving: Boolean, expanded: Boolean,
    onExpand: () -> Unit, onDismiss: () -> Unit, onDecide: (Boolean) -> Unit,
    onPlayFrom: (() -> Unit)?) {
    Box {
        IconButton(onClick = onExpand, enabled = !saving,
            modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.45f))
                .semantics { contentDescription = "More actions for segment ${segment.order}" }) {
            Text("⋮", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (segment.kept) {
                DropdownMenuItem(text = { Text("Discard") }, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Discard segment ${segment.order}" },
                    onClick = { onDismiss(); onDecide(false) })
            } else {
                DropdownMenuItem(text = { Text("Restore") }, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Restore segment ${segment.order}" },
                    onClick = { onDismiss(); onDecide(true) })
            }
            if (segment.kept && onPlayFrom != null) {
                DropdownMenuItem(text = { Text("Play from here") }, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Play from segment ${segment.order}" },
                    onClick = { onDismiss(); onPlayFrom() })
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ReviewPanel(session: Session, segment: Segment, model: RecordingViewModel,
    review: SegmentReviewController, readOnly: Boolean = false, onTrim: (() -> Unit)? = null,
    onPlayFrom: (() -> Unit)? = null, horizontal: Boolean = false) {
    val aspect = segmentVideoAspect(segment, review.videoAspect, if (horizontal) 16f / 9f else 9f / 16f)
    val playback = rememberVideoPlaybackState(review.player)
    fun togglePreviewPlayback() {
        val player = review.player ?: return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }
    @Composable
    fun previewControls(modifier: Modifier = Modifier) {
        VideoTransportControls(
            playing = playback.playing,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            enabled = review.player != null && review.error == null,
            onPlayPause = ::togglePreviewPlayback,
            onSeek = { review.player?.seekTo(it) },
            overlay = true,
            seekContentDescription = "Seek preview",
            modifier = modifier
        )
    }
    if (horizontal) {
        Surface(Modifier.fillMaxSize().padding(4.dp), color = Color(0xF218181E), contentColor = Color.White,
            shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints(Modifier.weight(0.62f).fillMaxHeight()) {
                    val fitted = ViewfinderGeometry.fit(maxWidth.value, maxHeight.value, aspect)
                    if (fitted != null) Box(Modifier.size(fitted.width.dp, fitted.height.dp).align(Alignment.Center)) {
                        VideoSurface(review.player, aspect,
                            "Preview segment ${segment.order}", Modifier.fillMaxSize()) {
                            previewControls(Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
                Column(Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { ReviewHeader(segment, readOnly) }
                        TextButton(onClick = { review.close() }, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC))) {
                            Text(if (readOnly) "Back to Arrange" else "Close")
                        }
                    }
                    if (review.player != null) Text(review.playbackLabel, style = MaterialTheme.typography.labelSmall)
                    ReviewActions(session, segment, model, review, readOnly, onTrim, onPlayFrom, showClose = false)
                }
            }
        }
    } else {
        Surface(Modifier.fillMaxSize().padding(4.dp), color = Color(0xF218181E), contentColor = Color.White,
            shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReviewHeader(segment, readOnly)
                if (review.player != null) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val fitted = ViewfinderGeometry.fit(maxWidth.value, maxHeight.value, aspect)
                        if (fitted != null) Box(Modifier.size(fitted.width.dp, fitted.height.dp).align(Alignment.Center)) {
                            VideoSurface(review.player, aspect,
                            "Preview segment ${segment.order}", Modifier.fillMaxSize()) {
                            previewControls(Modifier.align(Alignment.BottomCenter))
                        }
                        }
                    }
                    Text(review.playbackLabel, style = MaterialTheme.typography.labelSmall)
                } else Spacer(Modifier.weight(1f))
                ReviewActions(session, segment, model, review, readOnly, onTrim, onPlayFrom)
            }
        }
    }
}

private fun reviewTitle(segment: Segment, readOnly: Boolean): String =
    if (readOnly) "Preview segment ${segment.order}" else
        "Review segment ${segment.order} · ${if (segment.kept) "Kept" else "Discarded"}"

private fun reviewSubtitle(segment: Segment): String =
    if (segment.invalidTrimJson != null) "Trim needs attention" else
        "${formatClipDuration(segment.displayDurationMs)}${if (segment.trim?.isTrimmed == true) " · Trimmed" else if (segment.trim == null) " · estimated" else ""}"

@Composable
private fun ReviewHeader(segment: Segment, readOnly: Boolean) {
    Text(reviewTitle(segment, readOnly), style = MaterialTheme.typography.titleSmall)
    Text(reviewSubtitle(segment), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun ReviewActions(session: Session, segment: Segment, model: RecordingViewModel,
    review: SegmentReviewController, readOnly: Boolean, onTrim: (() -> Unit)?,
    onPlayFrom: (() -> Unit)?, showClose: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!readOnly) {
            if (onTrim != null) TextButton(onClick = onTrim, enabled = !review.saving,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Trim") }
            if (segment.kept) {
                OutlinedButton(onClick = { review.decide(model, false) }, enabled = !review.saving,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = false },
                    border = BorderStroke(1.dp, SkitCamColors.Error.copy(alpha = 0.75f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SkitCamColors.Error),
                    contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Discard") }
            } else {
                Button(onClick = { review.decide(model, true) }, enabled = !review.saving,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = false },
                    colors = ButtonDefaults.buttonColors(containerColor = SkitCamColors.RoseStrong,
                        contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Restore") }
            }
            if (segment.kept && onPlayFrom != null) TextButton(onClick = onPlayFrom, enabled = !review.saving,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Play from here") }
        }
        if (showClose) TextButton(onClick = { review.close() }, modifier = Modifier.heightIn(min = 48.dp),
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
