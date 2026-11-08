package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

/** Full-window ink surface with safe-area content, shared by all video tools. */
@Composable
fun ImmersiveVideoScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    Box(modifier.fillMaxSize().background(SkitCamColors.Ink)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
    }
}
