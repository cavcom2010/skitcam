package com.calvinmazhindu.skitcam.camera

import androidx.core.net.toUri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val thumbnailPermits = Semaphore(2)

@Composable
fun MediaShortcut(uri: String?, type: String, enabled: Boolean, onOpen: () -> Unit) {
    IconButton(onClick=onOpen,enabled=enabled,modifier=Modifier.size(48.dp)
        .semantics { contentDescription="Open saved media gallery" }) {
        SavedMediaThumbnail(uri,type,Modifier.size(48.dp).alpha(if (enabled) 1f else .38f))
    }
}

@Composable
fun SavedMediaThumbnail(uri: String?, type: String, modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val bitmap by produceState<Bitmap?>(null,uri,type) {
        value=null
        value = withContext(Dispatchers.IO) { thumbnailPermits.withPermit { runCatching {
            if(uri==null) null
            else if(Build.VERSION.SDK_INT>=29) context.contentResolver.loadThumbnail(uri.toUri(),android.util.Size(96,96),null)
            else if(type.startsWith("image/")) context.contentResolver.openFileDescriptor(uri.toUri(),"r")?.use { descriptor ->
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor,null,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>128) sample*=2
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor,null,BitmapFactory.Options().apply { inSampleSize=sample })
            } else null
        }.getOrNull() } }
    }
    Box(modifier,contentAlignment=androidx.compose.ui.Alignment.Center) {
        if(bitmap!=null) Image(bitmap!!.asImageBitmap(),contentDescription=null,modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).border(1.dp,Color.White.copy(alpha=.6f),RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop)
        else CameraSymbol(CameraIcon.Gallery, Modifier.size(28.dp), Color.LightGray)
    }
}


@Composable
fun LensSwitch(front: Boolean, enabled: Boolean, onSwitch: () -> Unit) {
    CameraIconButton(CameraIcon.SwitchCamera, "Switch to ${if(front) "rear" else "front"} camera", enabled, onSwitch)
}
