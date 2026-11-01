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

@Composable
fun PausedEnvironment(session: Session, landscape: Boolean, model: RecordingViewModel,
    review: SegmentReviewController, loader: ThumbnailLoader, modifier: Modifier = Modifier) {
    val selected = review.selection?.takeIf { it.sessionId == session.id }
    val segment = session.segments.find { it.order == selected?.order }
    BackHandler(enabled = selected != null) { review.close() }
    val thumbnailsEnabled = selected == null
    if (landscape) {
        Column(modifier) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (segment != null) ReviewPanel(session, segment, model, review)
            }
            LazyRow(Modifier.fillMaxWidth().height(112.dp).background(Color.Black.copy(alpha = 0.55f))
                .padding(4.dp).semantics { contentDescription = "Completed segments, horizontal strip" },
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(session.segments, key = { "${session.id}:${it.order}" }) {
                    ThumbnailCard(it, true, loader, thumbnailsEnabled) { review.open(session.id, it) }
                }
            }
        }
    } else {
        Row(modifier) {
            LazyColumn(Modifier.width(88.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f))
                .padding(4.dp).semantics { contentDescription = "Completed segments, vertical strip" },
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(session.segments, key = { "${session.id}:${it.order}" }) {
                    ThumbnailCard(it, false, loader, thumbnailsEnabled) { review.open(session.id, it) }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp), contentAlignment = Alignment.Center) {
                if (segment != null) ReviewPanel(session, segment, model, review)
            }
        }
    }
}

@Composable
private fun ThumbnailCard(segment: Segment, landscape: Boolean, loader: ThumbnailLoader,
    enabled: Boolean, onOpen: () -> Unit) {
    val thumbnail by produceState<ThumbnailResult>(ThumbnailResult.Loading, segment.path, enabled) {
        if (enabled) value = loader.load(segment)
    }
    val disposition = if (segment.kept) "Kept" else "Discarded"
    val availability = if (thumbnail == ThumbnailResult.Unavailable) ", video unavailable" else ""
    Card(onClick = onOpen, modifier = Modifier.width(if (landscape) 112.dp else 80.dp).heightIn(min = 100.dp)
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
            Text("${segment.order} · ${segment.durationMs / 1000}s", style = MaterialTheme.typography.labelSmall)
            Text(disposition, style = MaterialTheme.typography.labelSmall,
                color = if (segment.kept) Color.White else Color(0xFFF2D4BC))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ReviewPanel(session: Session, segment: Segment, model: RecordingViewModel,
    review: SegmentReviewController) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp)) {
        val videoHeight = (maxHeight * 0.45f).coerceIn(64.dp, 240.dp)
        Surface(color = Color(0xF218181E), contentColor = Color.White, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Review segment ${segment.order} · ${if (segment.kept) "Kept" else "Discarded"}",
                    style = MaterialTheme.typography.titleSmall)
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
                    val canKeep = segment.kept || session.retainedCount < RecordingEngine.MAX_SEGMENTS
                    Button(onClick = { review.decide(model, true) }, enabled = !review.saving && canKeep,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = segment.kept },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF854C55), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Keep") }
                    OutlinedButton(onClick = { review.decide(model, false) }, enabled = !review.saving,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = !segment.kept },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Discard") }
                    TextButton(onClick = { review.close() }, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF2D4BC)),
                        contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Close") }
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
