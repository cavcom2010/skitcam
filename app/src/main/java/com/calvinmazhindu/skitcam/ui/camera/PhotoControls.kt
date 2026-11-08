package com.calvinmazhindu.skitcam.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.R
import com.calvinmazhindu.skitcam.photo.PhotoModel
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamSpacing

@Composable
fun PhotoControls(
    photos: PhotoModel,
    landscape: Boolean,
    canCapture: Boolean,
    onCapture: () -> Unit,
    leading: @Composable () -> Unit,
    modeSelector: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.xs)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.photo_ready), style = MaterialTheme.typography.labelSmall,
                color = SkitCamColors.TextSecondary)
        }
        CaptureDockRow(
            vertical = landscape,
            leading = leading,
            modeSelector = modeSelector,
            shutter = { PhotoShutter(canCapture, onCapture) },
            trailing = trailing
        )
        Box(Modifier.fillMaxWidth().height(SkitCamSpacing.touchTarget))
        Box(
            Modifier.fillMaxWidth().height(SkitCamSpacing.touchTarget),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SkitCamSpacing.xxs)
            ) {
                if (photos.countdown > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SkitCamSpacing.xs)) {
                        Text(
                            stringResource(R.string.photo_countdown_action, photos.countdown),
                            style = MaterialTheme.typography.bodySmall,
                            color = SkitCamColors.Warm,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = "Photo countdown ${photos.countdown} seconds"
                            }
                        )
                        TextButton(onClick = photos::cancelTimer, modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) {
                            Text(stringResource(R.string.cancel_timer), color = SkitCamColors.Warm, maxLines = 1)
                        }
                    }
                }
                if (photos.canRetry) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.photo_awaiting_save), style = MaterialTheme.typography.labelSmall, color = SkitCamColors.Warning)
                        TextButton(onClick = photos::retry, modifier = Modifier.heightIn(min = SkitCamSpacing.touchTarget)) {
                            Text(stringResource(R.string.retry_save), color = SkitCamColors.Warm)
                        }
                    }
                } else if (!photos.lastSaveSucceeded) {
                    photos.message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = SkitCamColors.Warning,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
                if (photos.busy && photos.countdown == 0) {
                    Text(
                        stringResource(R.string.saving_photo),
                        style = MaterialTheme.typography.labelSmall,
                        color = SkitCamColors.TextSecondary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}
