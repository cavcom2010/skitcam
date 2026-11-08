package com.calvinmazhindu.skitcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamShapes
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing

enum class SkitCamStatusTone { Neutral, Info, Success, Warning, Error }

@Composable
fun SkitCamSectionHeading(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = SkitCamSpacing.sm)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        Text(
            text,
            Modifier.padding(top = SkitCamSpacing.sm).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = SkitCamColors.Warm
        )
    }
}

@Composable
fun SkitCamStatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: SkitCamStatusTone = SkitCamStatusTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val accent = when (tone) {
        SkitCamStatusTone.Neutral -> SkitCamColors.TextSecondary
        SkitCamStatusTone.Info -> SkitCamColors.Warm
        SkitCamStatusTone.Success -> SkitCamColors.Success
        SkitCamStatusTone.Warning -> SkitCamColors.Warning
        SkitCamStatusTone.Error -> SkitCamColors.Error
    }
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = SkitCamColors.Surface.copy(alpha = 0.92f),
        contentColor = SkitCamColors.TextPrimary,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            Modifier.padding(horizontal = SkitCamSpacing.md, vertical = SkitCamSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SkitCamSpacing.sm)
        ) {
            Box(Modifier.size(8.dp).background(accent, SkitCamShapes.pill))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = accent)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) {
                    Text(actionLabel, color = SkitCamColors.Warm, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SkitCamPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = SkitCamSpacing.touchTarget),
        colors = ButtonDefaults.buttonColors(
            containerColor = SkitCamColors.RoseStrong,
            contentColor = Color.White,
            disabledContainerColor = SkitCamColors.SurfacePressed,
            disabledContentColor = SkitCamColors.TextMuted
        ),
        shape = MaterialTheme.shapes.medium
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SkitCamSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = SkitCamSpacing.touchTarget),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = SkitCamColors.Warm,
            disabledContentColor = SkitCamColors.TextMuted
        ),
        shape = MaterialTheme.shapes.medium
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SkitCamMessagePanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    busy: Boolean = false
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.widthIn(max = 420.dp).padding(SkitCamSpacing.xl),
            color = SkitCamColors.Surface.copy(alpha = 0.96f),
            contentColor = SkitCamColors.TextPrimary,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                Modifier.padding(SkitCamSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.md)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(36.dp), color = SkitCamColors.Rose)
                Text(
                    title,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SkitCamColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (primaryLabel != null && onPrimary != null) {
                    SkitCamPrimaryButton(primaryLabel, onPrimary, Modifier.fillMaxWidth())
                }
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(
                        onClick = onSecondary,
                        modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)
                    ) { Text(secondaryLabel, color = SkitCamColors.Warm) }
                }
            }
        }
    }
}

@Composable
fun SkitCamLoadingPanel(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.md)) {
            CircularProgressIndicator(color = SkitCamColors.Rose)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = SkitCamColors.TextSecondary)
        }
    }
}

@Composable
fun SkitCamDialogTitle(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) {
            Text("Close", color = SkitCamColors.Warm)
        }
    }
}
