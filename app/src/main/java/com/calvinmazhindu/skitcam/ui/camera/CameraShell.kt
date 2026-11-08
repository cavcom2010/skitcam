package com.calvinmazhindu.skitcam.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.audio.AudioIndicator
import com.calvinmazhindu.skitcam.audio.AudioMonitor
import com.calvinmazhindu.skitcam.audio.AudioWarning
import com.calvinmazhindu.skitcam.camera.CameraIcon
import com.calvinmazhindu.skitcam.camera.CameraIconButton
import com.calvinmazhindu.skitcam.camera.CameraMode
import com.calvinmazhindu.skitcam.camera.CameraSymbol
import com.calvinmazhindu.skitcam.photo.PhotoAspectRatio
import com.calvinmazhindu.skitcam.photo.PhotoAspectSelector
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing

@Composable
fun CameraHeader(
    photo: Boolean,
    landscape: Boolean,
    fullPhoto: Boolean,
    dockWidth: Dp,
    arranging: Boolean,
    streaming: Boolean,
    boundLabel: String,
    lensLabel: String,
    aspectRatio: PhotoAspectRatio,
    aspectMenuExpanded: Boolean,
    aspectEnabled: Boolean,
    onAspectMenuChange: (Boolean) -> Unit,
    onAspectSelect: (PhotoAspectRatio) -> Unit,
    settingsEnabled: Boolean,
    quickEnabled: Boolean,
    onSettings: () -> Unit,
    onQuick: () -> Unit,
    audio: AudioMonitor?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .72f), Color.Transparent)))
            .then(if (landscape || fullPhoto) Modifier.blockPreviewInput() else Modifier)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = SkitCamSpacing.sm, end = if (landscape) dockWidth + SkitCamSpacing.sm else SkitCamSpacing.sm)
                .then(if (arranging) Modifier.clearAndSetSemantics {} else Modifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CameraIconButton(CameraIcon.Settings, stringResource(R.string.settings), settingsEnabled, onSettings)
            val bound = if (streaming) boundLabel else stringResource(R.string.camera_unavailable)
            if (photo) {
                PhotoAspectSelector(
                    aspectRatio,
                    lensLabel,
                    aspectEnabled,
                    aspectMenuExpanded,
                    onAspectMenuChange,
                    onAspectSelect,
                    Modifier.weight(1f)
                )
            } else {
                Text(
                    bound,
                    style = MaterialTheme.typography.labelMedium,
                    color = SkitCamColors.TextPrimary,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "$lensLabel · $bound" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!photo && !arranging) audio?.let { AudioIndicator(it) }
            CameraIconButton(CameraIcon.Quick, stringResource(R.string.quick_settings), quickEnabled, onQuick)
        }
        if (!photo && !arranging) {
            audio?.let {
                AudioWarning(
                    it,
                    Modifier.padding(start = SkitCamSpacing.lg, end = if (landscape) dockWidth + SkitCamSpacing.sm else SkitCamSpacing.lg)
                )
            }
        }
    }
}

@Composable
fun CameraModeToggle(
    selected: CameraMode,
    enabled: Boolean,
    onSelect: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val photo = selected == CameraMode.Photo
    val flip by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (photo) 0f else 180f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 340),
        label = "mode coin flip"
    )
    val showPhotoFace = flip > 90f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val photoLabel = stringResource(R.string.mode_photo)
    val videoLabel = stringResource(R.string.mode_video_short)
    val toggleHint = stringResource(R.string.mode_toggle_hint)
    Surface(
        onClick = { onSelect(if (photo) CameraMode.SkitVideo else CameraMode.Photo) },
        enabled = enabled,
        shape = CircleShape,
        color = SkitCamColors.SurfaceRaised,
        contentColor = SkitCamColors.TextPrimary,
        border = androidx.compose.foundation.BorderStroke(1.dp, SkitCamColors.Rose.copy(alpha = if (enabled) 0.9f else 0.3f)),
        modifier = modifier.size(64.dp)
            .graphicsLayer {
                rotationY = flip
                cameraDistance = 14f * density.density
            }
            .semantics {
                contentDescription = if (photo) videoLabel else photoLabel
                stateDescription = toggleHint
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            ModeCoinFace(
                label = videoLabel,
                icon = CameraIcon.Record,
                modifier = Modifier.graphicsLayer {
                    rotationY = 0f
                    alpha = if (showPhotoFace) 0f else 1f
                }.clearAndSetSemantics {}
            )
            ModeCoinFace(
                label = photoLabel,
                icon = CameraIcon.Photo,
                modifier = Modifier.graphicsLayer {
                    rotationY = 180f
                    alpha = if (showPhotoFace) 1f else 0f
                }.clearAndSetSemantics {}
            )
        }
    }
}

@Composable
private fun ModeCoinFace(label: String, icon: CameraIcon, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CameraSymbol(icon, Modifier.size(24.dp), SkitCamColors.Warm)
        Text(label, style = MaterialTheme.typography.labelSmall, color = SkitCamColors.TextPrimary, maxLines = 1)
    }
}

@Composable
fun CaptureDockRow(
    vertical: Boolean,
    leading: @Composable () -> Unit,
    modeSelector: @Composable () -> Unit,
    shutter: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    if (vertical) {
        Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.sm)
        ) {
            leading()
            modeSelector()
            shutter()
            trailing()
        }
    } else {
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            modeSelector()
            shutter()
            trailing()
        }
    }
}

@Composable
fun PhotoShutter(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = SkitCamColors.TextMuted
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(SkitCamSpacing.shutter).semantics {
            contentDescription = "Take photo"
            role = Role.Button
        }
    ) {
        Canvas(Modifier.size(76.dp)) {
            drawCircle(
                if (enabled) SkitCamColors.Warm else SkitCamColors.TextMuted,
                radius = size.minDimension / 2 - 2.dp.toPx(),
                style = Stroke(3.dp.toPx())
            )
            drawCircle(if (enabled) SkitCamColors.Rose else SkitCamColors.SurfacePressed, radius = size.minDimension / 2 - 8.dp.toPx())
        }
    }
}

/** Overlay gaps intercept camera gestures; child controls handle input first. */
fun Modifier.blockPreviewInput() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
    }
}
