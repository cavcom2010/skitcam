package com.calvinmazhindu.skitcam.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing

/** Stacked, wrapping actions cannot compete for AlertDialog's narrow dismiss slot. */
@Composable
fun SkitCamConfirmation(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight)
                .semantics { paneTitle = title },
                color = SkitCamColors.Surface, contentColor = SkitCamColors.TextPrimary,
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(SkitCamSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.sm)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = SkitCamColors.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    actions()
                }
            }
        }
    }
}
