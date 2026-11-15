package com.calvinmazhindu.skitcam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

/**
 * Shared status presentation for segments. Green remains "kept" and orange
 * remains "discarded"; the glyph is the non-colour cue and is announced by the
 * parent card's description rather than repeated by the decorative mark.
 */
fun segmentStatusColor(kept: Boolean): Color =
    if (kept) SkitCamColors.Success else SkitCamColors.Warning

@Composable
fun SegmentStatusMark(kept: Boolean, modifier: Modifier = Modifier) {
    val colour = segmentStatusColor(kept)
    Canvas(modifier.size(18.dp)) {
        drawCircle(colour)
        drawCircle(Color.Black.copy(alpha = 0.28f), radius = size.minDimension / 2f,
            style = Stroke(1.dp.toPx()))
        val stroke = 1.8.dp.toPx()
        if (kept) {
            drawLine(Color.Black.copy(alpha = 0.82f), Offset(size.width * 0.27f, size.height * 0.52f),
                Offset(size.width * 0.43f, size.height * 0.68f), stroke, cap = StrokeCap.Round)
            drawLine(Color.Black.copy(alpha = 0.82f), Offset(size.width * 0.43f, size.height * 0.68f),
                Offset(size.width * 0.74f, size.height * 0.34f), stroke, cap = StrokeCap.Round)
        } else {
            drawLine(Color.Black.copy(alpha = 0.82f), Offset(size.width * 0.30f, size.height * 0.50f),
                Offset(size.width * 0.70f, size.height * 0.50f), stroke, cap = StrokeCap.Round)
        }
    }
}
