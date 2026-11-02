package com.calvinmazhindu.skitcam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
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

@Composable
fun MediaShortcut(uri: String?, type: String, enabled: Boolean, onOpen: () -> Unit) {
    val context=LocalContext.current
    val bitmap by produceState<Bitmap?>(null,uri) {
        value = withContext(Dispatchers.IO) { runCatching {
            if(uri==null) null
            else if(Build.VERSION.SDK_INT>=29) context.contentResolver.loadThumbnail(Uri.parse(uri),android.util.Size(96,96),null)
            else if(type.startsWith("image/")) context.contentResolver.openFileDescriptor(Uri.parse(uri),"r")?.use { descriptor ->
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor,null,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>128) sample*=2
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor,null,BitmapFactory.Options().apply { inSampleSize=sample })
            } else null
        }.getOrNull() }
    }
    IconButton(onClick=onOpen,enabled=enabled,modifier=Modifier.size(48.dp)
        .semantics { contentDescription="Open latest saved media" }) {
        if(bitmap!=null) Image(bitmap!!.asImageBitmap(),contentDescription=null,modifier=Modifier.size(48.dp),contentScale=ContentScale.Crop)
        else Canvas(Modifier.size(28.dp)) {
            val color=if(enabled) Color.White else Color.Gray
            drawRoundRect(color,topLeft=Offset(1.dp.toPx(),3.dp.toPx()),size=Size(26.dp.toPx(),22.dp.toPx()),cornerRadius=CornerRadius(3.dp.toPx()),style=Stroke(2.dp.toPx()))
            drawCircle(color,2.dp.toPx(),Offset(19.dp.toPx(),9.dp.toPx()))
            drawLine(color,Offset(4.dp.toPx(),21.dp.toPx()),Offset(11.dp.toPx(),13.dp.toPx()),2.dp.toPx())
            drawLine(color,Offset(11.dp.toPx(),13.dp.toPx()),Offset(21.dp.toPx(),23.dp.toPx()),2.dp.toPx())
        }
    }
}


@Composable
fun LensSwitch(front: Boolean, enabled: Boolean, onSwitch: () -> Unit) {
    IconButton(onClick=onSwitch,enabled=enabled,modifier=Modifier.size(48.dp).semantics {
        contentDescription="Switch to ${if(front) "rear" else "front"} camera"
    }) {
        Canvas(Modifier.size(28.dp)) {
            val color=if(enabled) Color.White else Color.Gray
            val stroke=2.dp.toPx()
            drawRoundRect(color,Offset(1.dp.toPx(),7.dp.toPx()),Size(26.dp.toPx(),18.dp.toPx()),CornerRadius(3.dp.toPx()),style=Stroke(stroke))
            drawRoundRect(color,Offset(9.dp.toPx(),3.dp.toPx()),Size(10.dp.toPx(),4.dp.toPx()),CornerRadius(1.dp.toPx()),style=Stroke(stroke))
            drawLine(color,Offset(8.dp.toPx(),13.dp.toPx()),Offset(20.dp.toPx(),13.dp.toPx()),stroke)
            drawLine(color,Offset(17.dp.toPx(),10.dp.toPx()),Offset(20.dp.toPx(),13.dp.toPx()),stroke)
            drawLine(color,Offset(8.dp.toPx(),19.dp.toPx()),Offset(20.dp.toPx(),19.dp.toPx()),stroke)
            drawLine(color,Offset(8.dp.toPx(),19.dp.toPx()),Offset(11.dp.toPx(),22.dp.toPx()),stroke)
        }
    }
}
