package com.calvinmazhindu.skitcam.photo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The existing Photo header doubles as the ratio control; no additional toolbar row. */
@Composable
fun PhotoAspectSelector(ratio: PhotoAspectRatio,lensLabel: String,enabled: Boolean,
    expanded: Boolean,onExpandedChange: (Boolean)->Unit,onSelect: (PhotoAspectRatio)->Unit,
    modifier: Modifier=Modifier) {
    val window=LocalWindowInfo.current.containerSize
    val landscape=window.width>window.height
    Box(modifier) {
        TextButton(onClick={ onExpandedChange(true) },enabled=enabled,
            contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.heightIn(min=48.dp)
                .semantics { contentDescription="Photo aspect ratio";stateDescription="$lensLabel · ${ratio.label(landscape)}" }) {
            Text("Photo · ${ratio.label(landscape)} ▾",style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={onExpandedChange(false)}) {
            PhotoAspectRatio.entries.forEach { option ->
                DropdownMenuItem(text={ Text(option.label(landscape)) },enabled=enabled,
                    trailingIcon={ if(option==ratio) Text("✓") },
                    modifier=Modifier.semantics { contentDescription="Photo ratio ${option.label(landscape)}";selected=option==ratio },
                    onClick={ onExpandedChange(false);onSelect(option) })
            }
        }
    }
}
