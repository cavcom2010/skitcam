package com.calvinmazhindu.skitcam.review

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

internal data class SegmentSummary(val kept: Int, val discarded: Int) {
    val total = kept + discarded
    val keptFraction = if (total > 0) kept.toFloat() / total else 0f
    val discardedFraction = if (total > 0) discarded.toFloat() / total else 0f

    companion object {
        fun from(session: Session): SegmentSummary {
            val kept = session.retainedCount
            return SegmentSummary(kept, session.segments.size - kept)
        }
    }
}

@Composable
internal fun SegmentSummaryBar(summary: SegmentSummary, modifier: Modifier = Modifier) {
    val empty = stringResource(R.string.segment_summary_empty)
    val description = if (summary.total == 0) empty else
        stringResource(R.string.segment_summary_description, summary.kept, summary.discarded)
    val kept = stringResource(R.string.segment_summary_kept, summary.kept)
    val keptShort = stringResource(R.string.segment_summary_kept_short, summary.kept)
    val discarded = stringResource(R.string.segment_summary_discarded, summary.discarded)
    val discardedShort = stringResource(R.string.segment_summary_discarded_short, summary.discarded)
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    // Preserve the old strip's one line of labelMedium plus 6 dp above and below.
    val lineHeight = with(LocalDensity.current) {
        measurer.measure(description, style, maxLines = 1, softWrap = false).size.height.toDp()
    }

    Spacer(modifier
        .clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        }
        .clip(MaterialTheme.shapes.small)
        .drawWithCache {
            val split = size.width * summary.keptFraction
            val inset = 6.dp.toPx()
            fun fit(width: Float, vararg labels: String): TextLayoutResult? =
                if (width <= 2 * inset) null else labels.firstNotNullOfOrNull { label ->
                    measurer.measure(label, style, maxLines = 1, softWrap = false)
                        .takeIf { it.size.width <= width - 2 * inset && it.size.height <= size.height }
                }
            val keptLabel = fit(split, kept, keptShort)
            val discardedLabel = fit(size.width - split, discarded, discardedShort)
            val emptyLabel = if (summary.total == 0) fit(size.width, empty) else null
            onDrawBehind {
                if (summary.total == 0) {
                    drawRect(SkitCamColors.Ink.copy(alpha = .9f))
                } else {
                    // Use one shared boundary and no minimum widths, even for a 1% region.
                    if (summary.discardedFraction > 0f) drawRect(SkitCamColors.Warning)
                    if (summary.keptFraction > 0f) drawRect(SkitCamColors.Success, size = Size(split, size.height))
                }
                keptLabel?.let { drawText(it, SkitCamColors.Ink, centeredAt(it, split / 2, size.height)) }
                discardedLabel?.let {
                    drawText(it, SkitCamColors.Ink, centeredAt(it, (split + size.width) / 2, size.height))
                }
                emptyLabel?.let {
                    drawText(it, SkitCamColors.TextPrimary, centeredAt(it, size.width / 2, size.height))
                }
            }
        }
        .padding(vertical = 6.dp)
        .height(lineHeight))
}

private fun centeredAt(label: TextLayoutResult, x: Float, height: Float) =
    Offset(x - label.size.width / 2f, (height - label.size.height) / 2f)
