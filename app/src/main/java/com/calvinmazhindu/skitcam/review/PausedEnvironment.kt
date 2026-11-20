package com.calvinmazhindu.skitcam.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.calvinmazhindu.skitcam.camera.ViewfinderGeometry
import com.calvinmazhindu.skitcam.camera.CameraIcon
import com.calvinmazhindu.skitcam.camera.CameraSymbol
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.arrange.InlineReorderViewModel
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.trim.TrimViewModel
import com.calvinmazhindu.skitcam.ui.components.SegmentStatusMark
import com.calvinmazhindu.skitcam.ui.components.segmentStatusColor
import com.calvinmazhindu.skitcam.ui.components.SkitCamIconAction
import com.calvinmazhindu.skitcam.ui.components.SkitCamConfirmation
import com.calvinmazhindu.skitcam.ui.components.SkitCamPrimaryButton
import com.calvinmazhindu.skitcam.ui.components.SkitCamSecondaryButton
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.video.ImmersiveVideoScaffold
import com.calvinmazhindu.skitcam.ui.video.VideoScreenHeader
import com.calvinmazhindu.skitcam.ui.video.VideoSurface
import com.calvinmazhindu.skitcam.ui.video.VideoTransportControls
import com.calvinmazhindu.skitcam.ui.video.VideoTrimBar
import com.calvinmazhindu.skitcam.ui.video.frameStepMs
import com.calvinmazhindu.skitcam.ui.video.formatClipDuration
import com.calvinmazhindu.skitcam.ui.video.rememberVideoPlaybackState
import com.calvinmazhindu.skitcam.ui.video.segmentVideoAspect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

@Composable
fun PausedEnvironment(session: Session, landscape: Boolean, model: RecordingViewModel,
    review: SegmentReviewController, loader: ThumbnailLoader, trim: TrimViewModel,
    modifier: Modifier = Modifier,
    onPlayFromBeginning: (() -> Unit)? = null,
    onPlayFrom: ((Segment) -> Unit)? = null, immersiveReview: Boolean = true,
    reorder: InlineReorderViewModel? = null, reorderEligible: Boolean = false,
    navigateReorder: (() -> Unit) -> Unit = { it() }) {
    val selected = review.selection?.takeIf { it.sessionId == session.id }
    val segment = session.segments.find { it.id == selected?.segmentId }
    fun openReview(item: Segment) {
        navigateReorder {
            trim.open(session, item)
            review.open(session.id, item, fullSource = true)
        }
    }
    BackHandler(enabled = selected != null && !(immersiveReview && segment != null)) {
        trim.close(); review.close()
    }
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
    fun playFrom(item: Segment): (() -> Unit)? =
        if (item.kept && onPlayFrom != null) ({ onPlayFrom(item) }) else null

    Box(modifier) {
        if (immersiveReview && segment != null) {
            ImmersiveReview(session, segment, model, review, loader, trim,
                onDecide = { item, kept -> decide(item, kept) },
                playFrom = { item -> playFrom(item) },
                onOpenSegment = { item -> openReview(item) })
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    if (reorder?.draft != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(reorder.notice ?: "Reorder unlocked",
                                Modifier.weight(1f).background(SkitCamColors.Ink.copy(alpha=.9f), MaterialTheme.shapes.small)
                                    .padding(6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { reorder.cancel() }, enabled = !reorder.saving,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                            Button(onClick = { reorder.done(model) },
                                enabled = reorderEligible && !reorder.saving && reorder.draggingId == null,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (reorder.saving) "Saving…" else "Done") }
                        }
                    }
                    reorder?.error?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val clipCount = session.segments.size
                        Text("${session.retainedCount} kept · $clipCount ${if (clipCount == 1) "clip" else "clips"}",
                            Modifier.weight(1f).background(SkitCamColors.Ink.copy(alpha=.9f), MaterialTheme.shapes.small)
                                .padding(6.dp), style = MaterialTheme.typography.labelMedium)
                        SkitCamIconAction(CameraIcon.PlayFromStart, stringResource(R.string.play_skit),
                            onClick = { onPlayFromBeginning?.invoke() },
                            enabled = playEnabled && onPlayFromBeginning != null && !review.saving)
                    }
                    if (!playEnabled) Text("No kept clips to play.", style = MaterialTheme.typography.labelSmall)
                }
                if (reorder != null) {
                    Row(Modifier.weight(1f)) {
                        InlineSegmentShelf(session, reorder, reorderEligible, loader, review.saving,
                            ::openReview, ::decide, ::playFrom)
                        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp), contentAlignment = Alignment.Center) {
                            if (segment != null) ReviewPanel(session, segment, model, review, onPlayFrom = playFrom(segment))
                        }
                    }
                } else if (landscape) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (segment != null) ReviewPanel(session, segment, model, review,
                                onPlayFrom = playFrom(segment), horizontal = true)
                        }
                        LazyRow(Modifier.fillMaxWidth().height(112.dp).background(Color.Black.copy(alpha = 0.55f))
                            .padding(4.dp).semantics { contentDescription = "Completed segments, horizontal strip" },
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(session.segments, key = { it.id }) { item ->
                                ThumbnailCard(item, true, loader, thumbnailsEnabled, review.saving,
                                    { openReview(item) }, { decide(item, it) }, playFrom(item))
                            }
                        }
                    }
                } else {
                    Row(Modifier.weight(1f)) {
                        LazyColumn(Modifier.width(112.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f))
                            .padding(4.dp).semantics { contentDescription = "Completed segments, vertical strip" },
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(session.segments, key = { it.id }) { item ->
                                ThumbnailCard(item, false, loader, thumbnailsEnabled, review.saving,
                                    { openReview(item) }, { decide(item, it) }, playFrom(item))
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp), contentAlignment = Alignment.Center) {
                            if (segment != null) ReviewPanel(session, segment, model, review,
                                onPlayFrom = playFrom(segment))
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

@Composable
internal fun ThumbnailCard(segment: Segment, landscape: Boolean, loader: ThumbnailLoader,
    enabled: Boolean, saving: Boolean, onOpen: () -> Unit, onDecide: (Boolean) -> Unit,
    onPlayFrom: (() -> Unit)? = null, reorderPosition: String? = null,
    onUnlock: (() -> Unit)? = null, onMoveUp: (() -> Unit)? = null, onMoveDown: (() -> Unit)? = null,
    showMenu: Boolean = true, pointerClickEnabled: Boolean = true,
    selected: Boolean = false, dragging: Boolean = false) {
    val thumbnail by produceState<ThumbnailResult>(ThumbnailResult.Loading, segment.path, enabled) {
        if (enabled) value = loader.load(segment)
    }
    var actions by remember(segment.id) { mutableStateOf(false) }
    val disposition = if (segment.kept) "Kept" else "Discarded"
    val availability = if (thumbnail == ThumbnailResult.Unavailable) ", video unavailable" else ""
    val shape = MaterialTheme.shapes.small
    // Keep a full 48dp review area beside the separate 48dp overflow target.
    // Narrower short cards let overflow intercept centre taps and long presses.
    Box(Modifier.width(if (landscape) 112.dp else 104.dp)
        .then(if (selected || dragging) Modifier.border(if (dragging) 3.dp else 2.dp,
            if (dragging) SkitCamColors.Rose else SkitCamColors.Warm, shape) else Modifier)
        .padding(3.dp)) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .then(if (pointerClickEnabled) Modifier.clickable(enabled = !saving, onClick = onOpen) else Modifier)
                .border(1.5.dp, segmentStatusColor(segment.kept), MaterialTheme.shapes.small)
                .clearAndSetSemantics {
                contentDescription = "Review segment ${segment.order}, $disposition$availability"
                this.selected = selected || dragging
                stateDescription = listOfNotNull(reorderPosition?.let { "Position $it" }, when (thumbnail) {
                    is ThumbnailResult.Ready -> "Thumbnail ready"
                    ThumbnailResult.Loading -> "Loading thumbnail"
                    ThumbnailResult.Unavailable -> "Thumbnail unavailable"
                }).joinToString(". ")
                role = Role.Button
                if (reorderPosition != null) liveRegion = LiveRegionMode.Polite
                if (saving) disabled()
                onClick(label = "Review segment ${segment.order}") { if (!saving) onOpen(); !saving }
                if (onUnlock != null) onLongClick(label = "Unlock reordering") { onUnlock(); true }
                customActions = buildList {
                    if (onMoveUp != null) add(CustomAccessibilityAction("Move up") { onMoveUp(); true })
                    if (onMoveDown != null) add(CustomAccessibilityAction("Move down") { onMoveDown(); true })
                }
            }, colors = CardDefaults.cardColors(
                containerColor = if (selected) SkitCamColors.SurfacePressed else SkitCamColors.SurfaceRaised,
                contentColor = SkitCamColors.TextPrimary)) {
            Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(50.dp),
                    contentAlignment = Alignment.Center) {
                    when (val frame = thumbnail) {
                        is ThumbnailResult.Ready -> Image(frame.bitmap.asImageBitmap(), null,
                            Modifier.fillMaxSize().alpha(if (segment.kept) 1f else .45f), contentScale = ContentScale.Crop)
                        ThumbnailResult.Loading -> Text("…", color = Color.White)
                        ThumbnailResult.Unavailable -> Text("Unavailable", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    SegmentStatusMark(segment.kept, Modifier.align(Alignment.BottomStart).padding(2.dp))
                }
                Text("${segment.order} · ${formatClipDuration(segment.displayDurationMs)}", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showMenu) Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { actions = true }, enabled = !saving,
                modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "More actions for segment ${segment.order}" }) {
                CameraSymbol(CameraIcon.More, Modifier.size(28.dp)
                    .background(SkitCamColors.Ink.copy(alpha=.85f), androidx.compose.foundation.shape.CircleShape)
                    .padding(4.dp))
            }
            DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                if (segment.kept) {
                    DropdownMenuItem(text = { Text("Discard") }, enabled = !saving,
                        leadingIcon = { CameraSymbol(CameraIcon.Delete, color = SkitCamColors.Warning) },
                        modifier = Modifier.semantics { contentDescription = "Discard segment ${segment.order}" },
                        onClick = { actions = false; onDecide(false) })
                } else {
                    DropdownMenuItem(text = { Text("Restore") }, enabled = !saving,
                        leadingIcon = { CameraSymbol(CameraIcon.Restore, color = SkitCamColors.Success) },
                        modifier = Modifier.semantics { contentDescription = "Restore segment ${segment.order}" },
                        onClick = { actions = false; onDecide(true) })
                }
                if (segment.kept && onPlayFrom != null) {
                    DropdownMenuItem(text = { Text("Play from here") }, enabled = !saving,
                        leadingIcon = { CameraSymbol(CameraIcon.Play) },
                        modifier = Modifier.semantics { contentDescription = "Play from segment ${segment.order}" },
                        onClick = { actions = false; onPlayFrom() })
                }
            }
        }
    }
}

@Composable
private fun ImmersiveReview(session: Session, segment: Segment, model: RecordingViewModel,
    review: SegmentReviewController, loader: ThumbnailLoader, trim: TrimViewModel,
    onDecide: (Segment, Boolean) -> Unit,
    playFrom: (Segment) -> (() -> Unit)?, onOpenSegment: (Segment) -> Unit) {
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playback = rememberVideoPlaybackState(review.player)
    val range = trim.draft
    val frameStepMs = remember(segment.id) { frameStepMs(segment.media?.averageFps) }
    var confirmAction by remember(segment.id) { mutableStateOf<(() -> Unit)?>(null) }
    // Absolute source position captured when a trim gesture begins. The marker must
    // not move while the edge under the finger changes the retained range.
    var trimAnchor by remember(segment.id) { mutableStateOf<Long?>(null) }
    val closeReview = {
        trim.close()
        review.close()
    }
    fun navigate(action: () -> Unit) {
        if (trim.dirty) confirmAction = action else action()
    }
    LaunchedEffect(segment.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                if (trim.segment?.id != segment.id || trim.sessionId != session.id) {
                    trim.open(session, segment)
                }
                // Resolve once against the full source, not a clipped duration on resume.
                if (trim.ready) return@repeatOnLifecycle
                val facts = withContext(Dispatchers.IO) {
                    SessionFileStore(File(segment.path).parentFile!!.parentFile!!).sourceFacts(segment.path)
                }
                withTimeout(15_000) {
                    while (true) {
                        review.error?.let { trim.fail(it) }
                        val duration = review.player?.duration
                        if (duration != null && duration != C.TIME_UNSET && duration > 0) break
                        delay(50)
                    }
                }
                val player = review.player ?: error("Player unavailable")
                check(player.isCurrentMediaItemSeekable) { "This source cannot be seeked safely." }
                trim.resolve(facts, player.duration)
            } catch (_: TimeoutCancellationException) {
                trim.fail("Video preparation timed out. Close and reopen the segment.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                trim.fail(e.message ?: "Cannot prepare trim.")
            }
        }
    }
    LaunchedEffect(segment.id, trim.revision, trim.ready) {
        val current = trim.draft
        if (trim.ready && current?.valid() == true) {
            review.previewTrim(current, trimAnchor, frameStepMs)
            trimAnchor = null
        }
    }
    BackHandler { navigate(closeReview) }
    @Composable
    fun trimMessages() {
        trim.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SkitCamColors.Error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        trim.notice?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = SkitCamColors.Warning)
        }
        if (trim.saving) Text("Saving trim…", style = MaterialTheme.typography.labelSmall,
            color = SkitCamColors.TextSecondary)
    }
    fun resetTrim() {
        review.player?.pause()
        trim.reset()
    }
    @Composable
    fun videoMessages(modifier: Modifier = Modifier) {
        if (trim.error != null || trim.notice != null || trim.saving ||
            review.error != null || review.saving || review.feedback != null ||
            (!segment.kept && session.retainedCount >= RecordingEngine.MAX_SEGMENTS)) {
            Column(modifier.fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f)).padding(8.dp)) {
                trimMessages()
                ReviewMessages(session, segment, review)
            }
        }
    }
    @Composable
    fun videoControls(modifier: Modifier = Modifier, progressOnOwnRow: Boolean = false) {
        val activeRange = range?.takeIf { trim.ready && it.valid() }
        val rawSourcePosition = trimAnchor ?: review.sourcePositionMs ?: playback.positionMs
        val sourcePosition = activeRange?.let { rawSourcePosition.coerceIn(it.trimStartMs, it.trimEndMs) }
            ?: rawSourcePosition
        fun seek(sourceMs: Long) {
            trimAnchor = null
            activeRange?.let { review.seekTrim(it, sourceMs, frameStepMs) }
        }
        val trimProgress: (@Composable (Modifier) -> Unit)? = if (activeRange != null) {
            { progressModifier ->
                VideoTrimBar(
                    positionMs = sourcePosition,
                    sourceDurationMs = activeRange.sourceDurationMs,
                    trimStartMs = activeRange.trimStartMs,
                    trimEndMs = activeRange.trimEndMs,
                    enabled = !trim.saving && trim.error == null,
                    onSeek = { seek(it) },
                    onTrimStart = { ms -> trim.draft?.let { trim.change(it.start(ms)) } },
                    onTrimEnd = { ms -> trim.draft?.let { trim.change(it.end(ms)) } },
                    frameStepMs = frameStepMs,
                    onDragStart = {
                        review.player?.pause()
                        // Same source as the drawn marker, so it stays exactly put.
                        if (trimAnchor == null) trimAnchor = review.sourcePositionMs
                    },
                    onDragEnd = { trim.settled() },
                    modifier = progressModifier
                )
            }
        } else null
        VideoTransportControls(
            playing = playback.playing,
            positionMs = sourcePosition,
            durationMs = activeRange?.sourceDurationMs ?: playback.durationMs,
            enabled = activeRange != null && review.player != null && review.error == null,
            onPlayPause = { activeRange?.let(review::toggleTrimPlayback) },
            onJumpToStart = { activeRange?.let { seek(it.trimStartMs) } },
            onSeek = { seek(it) },
            overlay = true,
            seekContentDescription = "Seek segment ${segment.order}",
            progressContent = trimProgress,
            progressOnOwnRow = progressOnOwnRow,
            showTime = false,
            additionalActions = {
                // Fixed slots: editing/saving changes availability, never preview geometry.
                val ready = trim.ready && review.error == null
                val busy = review.saving || trim.saving
                SkitCamIconAction(CameraIcon.Check, stringResource(R.string.save_trim),
                    { trim.saveInPlace(model) },
                    enabled = trim.dirty && trim.canSave && ready && !busy, emphasized = true)
                SkitCamIconAction(CameraIcon.Reset, stringResource(R.string.reset_trim), ::resetTrim,
                    enabled = (trim.dirty || trim.draft?.isTrimmed == true) && ready && !busy)
            },
            modifier = modifier
        )
    }
    fun select(item: Segment) {
        // Shelf callbacks can outlive a recomposition; check the live selection.
        if (item.id != review.selection?.segmentId) navigate { onOpenSegment(item) }
    }
    val safePlayFrom: (Segment) -> (() -> Unit)? = { item -> if (trim.dirty) null else playFrom(item) }
    ImmersiveVideoScaffold {
        val wide = maxWidth > maxHeight
        val aspect = segmentVideoAspect(segment, review.videoAspect, if (wide) 16f / 9f else 9f / 16f)
        val shelfHeight = if (density.fontScale > 1.5f) 116.dp else 100.dp
        if (wide) {
            val railWidth = minOf(if (density.fontScale > 1.5f) 240.dp else 200.dp, maxWidth * 0.38f)
            Row(Modifier.fillMaxSize()) {
                // Controls use the panel width, not a potentially narrow portrait frame.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    VideoSurface(
                        player = review.player,
                        aspect = aspect,
                        contentDescription = "Segment ${segment.order} playback",
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        trimPreview = true
                    )
                    videoMessages(Modifier.align(Alignment.TopStart))
                    videoControls(Modifier.align(Alignment.BottomCenter))
                }
                Column(Modifier.width(railWidth).fillMaxHeight().padding(end = 4.dp)) {
                    VideoScreenHeader(
                        title = reviewTitle(segment, false),
                        subtitle = reviewSubtitle(segment),
                        onClose = { navigate(closeReview) }
                    )
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)) {
                        item { Text("Clips", style = MaterialTheme.typography.labelSmall) }
                        items(session.segments, key = { it.id }) { item ->
                            ReviewThumbnailCard(item, loader, true, review.saving || trim.saving,
                                { select(item) }, { onDecide(item, it) }, safePlayFrom(item),
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
                    onClose = { navigate(closeReview) }
                )
                // Fit the complete video into all available preview space. Transport
                // and trim handles span the safe width, independently of letterboxing.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    VideoSurface(
                        player = review.player,
                        aspect = aspect,
                        contentDescription = "Segment ${segment.order} playback",
                        modifier = Modifier.fillMaxSize(),
                        trimPreview = true
                    )
                    // Status/error text overlays the preview rather than resizing it.
                    videoMessages(Modifier.align(Alignment.TopStart))
                    videoControls(Modifier.align(Alignment.BottomCenter), progressOnOwnRow = true)
                }
                ReviewShelfRow(session, segment.id, review.saving || trim.saving, loader, shelfHeight, onDecide, safePlayFrom,
                    onOpen = ::select)
            }
        }
    }
    confirmAction?.let { action ->
        SkitCamConfirmation("Discard unsaved trim?", "The changed trim range has not been saved.",
            onDismiss = { confirmAction = null }) {
            SkitCamPrimaryButton("Keep editing", { confirmAction = null }, Modifier.fillMaxWidth())
            SkitCamSecondaryButton("Discard", { confirmAction = null; action() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ReviewShelfRow(session: Session, selectedId: String, saving: Boolean,
    loader: ThumbnailLoader, height: Dp, onDecide: (Segment, Boolean) -> Unit,
    playFrom: (Segment) -> (() -> Unit)?, onOpen: (Segment) -> Unit = {}) {
    LazyRow(Modifier.fillMaxWidth().height(height).background(Color.Black.copy(alpha = 0.55f))
        .padding(4.dp).semantics { contentDescription = "Completed segments, horizontal strip" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(session.segments, key = { it.id }) { item ->
            ReviewThumbnailCard(item, loader, true, saving,
                { onOpen(item) }, { onDecide(item, it) }, playFrom(item),
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
    val shape = MaterialTheme.shapes.small
    Box(modifier.then(if (selected) Modifier.background(SkitCamColors.Warm.copy(alpha = 0.12f), shape)
        .border(2.dp, SkitCamColors.Warm, shape).padding(2.dp) else Modifier)) {
        Card(onClick = onOpen, enabled = !saving,
            border = BorderStroke(1.5.dp, segmentStatusColor(segment.kept)),
            modifier = Modifier.fillMaxWidth()
                .then(if (row) Modifier.heightIn(min = 64.dp) else Modifier.fillMaxSize())
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
                ThumbnailStatus(thumbnail, segment.kept, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text("${segment.order} · ${formatClipDuration(segment.displayDurationMs)}",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(48.dp))
            } else {
                Column(Modifier.fillMaxSize().padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ThumbnailStatus(thumbnail, segment.kept, Modifier.fillMaxWidth().height(48.dp))
                    Text("${segment.order} · ${formatClipDuration(segment.displayDurationMs)}",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Keep overflow outside the card's clearAndSetSemantics in both layouts,
        // so its labelled actions remain discoverable to TalkBack.
        Box(Modifier.align(if (row) Alignment.CenterEnd else Alignment.TopEnd)) {
            ThumbnailMenu(segment, saving, actions, { actions = true }, { actions = false }, onDecide, onPlayFrom)
        }
    }
}

@Composable
private fun ThumbnailStatus(thumbnail: ThumbnailResult, kept: Boolean, modifier: Modifier) {
    Box(modifier) {
        ThumbnailFrame(thumbnail, Modifier.fillMaxSize(), dimmed = !kept)
        SegmentStatusMark(kept, Modifier.align(Alignment.BottomStart).padding(2.dp))
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
    val discardLabel = stringResource(R.string.discard_segment)
    val restoreLabel = stringResource(R.string.restore_segment)
    Box {
        IconButton(onClick = onExpand, enabled = !saving,
            modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "More actions for segment ${segment.order}" }) {
            CameraSymbol(CameraIcon.More, Modifier.size(28.dp)
                .background(SkitCamColors.Ink.copy(alpha=.85f), androidx.compose.foundation.shape.CircleShape)
                .padding(4.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (segment.kept) {
                DropdownMenuItem(text = { Text("Discard") }, enabled = !saving,
                    leadingIcon = { CameraSymbol(CameraIcon.Delete, color = SkitCamColors.Warning) },
                    modifier = Modifier.semantics { contentDescription = "$discardLabel ${segment.order}" },
                    onClick = { onDismiss(); onDecide(false) })
            } else {
                DropdownMenuItem(text = { Text("Restore") }, enabled = !saving,
                    leadingIcon = { CameraSymbol(CameraIcon.Restore, color = SkitCamColors.Success) },
                    modifier = Modifier.semantics { contentDescription = "$restoreLabel ${segment.order}" },
                    onClick = { onDismiss(); onDecide(true) })
            }
            if (segment.kept && onPlayFrom != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.play_from_here)) }, enabled = !saving,
                    leadingIcon = { CameraSymbol(CameraIcon.Play) },
                    modifier = Modifier.semantics { contentDescription = "Play from segment ${segment.order}" },
                    onClick = { onDismiss(); onPlayFrom() })
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ReviewPanel(session: Session, segment: Segment, model: RecordingViewModel,
    review: SegmentReviewController, readOnly: Boolean = false,
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
                        SkitCamIconAction(CameraIcon.Close, stringResource(R.string.close), { review.close() })
                    }
                    if (review.player != null) Text(review.playbackLabel, style = MaterialTheme.typography.labelSmall)
                    ReviewActions(session, segment, review,
                        showClose = false)
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
                ReviewActions(session, segment, review)
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
private fun ReviewActions(session: Session, segment: Segment,
    review: SegmentReviewController, showClose: Boolean = true) {
    if (showClose) SkitCamIconAction(CameraIcon.Close, stringResource(R.string.close), { review.close() })
    ReviewMessages(session, segment, review)
}

@Composable
private fun ReviewMessages(session: Session, segment: Segment, review: SegmentReviewController) {
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
