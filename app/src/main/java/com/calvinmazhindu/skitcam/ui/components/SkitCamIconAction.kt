package com.calvinmazhindu.skitcam.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.camera.CameraIcon
import com.calvinmazhindu.skitcam.camera.CameraSymbol
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing

/** One semantic button, one decorative glyph, a discoverable long-press label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkitCamIconAction(
    icon: CameraIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    tint: Color = SkitCamColors.Warm
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        if (emphasized) {
            FilledTonalIconButton(
                onClick = onClick, enabled = enabled,
                modifier = modifier.size(SkitCamSpacing.touchTarget).semantics { contentDescription = label },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = SkitCamColors.RoseStrong,
                    contentColor = Color.White,
                    disabledContainerColor = SkitCamColors.SurfacePressed,
                    disabledContentColor = SkitCamColors.TextMuted
                )
            ) { CameraSymbol(icon, Modifier.size(24.dp), if (enabled) Color.White else SkitCamColors.TextMuted) }
        } else {
            IconButton(
                onClick = onClick, enabled = enabled,
                modifier = modifier.size(SkitCamSpacing.touchTarget)
                    .background(SkitCamColors.Ink.copy(alpha = .85f), CircleShape)
                    .semantics { contentDescription = label }
            ) { CameraSymbol(icon, Modifier.size(24.dp), if (enabled) tint else SkitCamColors.TextMuted) }
        }
    }
}
