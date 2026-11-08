package com.calvinmazhindu.skitcam.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors

@Composable
fun VideoScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    closeLabel: String = "Close",
    closeContentDescription: String? = null,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = SkitCamColors.TextSecondary)
            }
        }
        actions()
        TextButton(
            onClick = onClose,
            modifier = Modifier.heightIn(min = 48.dp).then(
                if (closeContentDescription != null)
                    Modifier.semantics { contentDescription = closeContentDescription }
                else Modifier
            ),
            colors = ButtonDefaults.textButtonColors(contentColor = SkitCamColors.Warm),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
        ) { Text(closeLabel) }
    }
}
