package com.calvinmazhindu.skitcam.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

enum class CameraIcon { Settings, Quick, Microphone, MicOff, Record, Pause, Stop, Back, Share }

/** Original simple geometry, independent of an external icon library. */
@Composable
fun CameraSymbol(icon: CameraIcon, modifier: Modifier=Modifier, color: Color=Color.White) {
    Canvas(modifier.size(24.dp)) {
        val unit=size.minDimension/24f;val stroke=2f*unit
        fun point(x: Float,y: Float)=Offset(x*unit,y*unit)
        when(icon) {
            CameraIcon.Record -> drawCircle(color,radius=size.minDimension*.30f)
            CameraIcon.Pause -> { drawRect(color,point(6f,5f),Size(4*unit,14*unit));drawRect(color,point(14f,5f),Size(4*unit,14*unit)) }
            CameraIcon.Stop -> drawRoundRect(color,point(5f,5f),Size(14*unit,14*unit),CornerRadius(2*unit))
            CameraIcon.Settings -> {
                drawCircle(color,8*unit,point(12f,12f),style=Stroke(stroke));drawCircle(color,3*unit,point(12f,12f),style=Stroke(stroke))
                repeat(8) { rotate(it*45f,point(12f,12f)) { drawLine(color,point(12f,1f),point(12f,4f),stroke) } }
            }
            CameraIcon.Quick -> repeat(3) { n ->
                val y=5f+n*7f;val x=if(n==1) 15f else 8f
                drawLine(color,point(2f,y),point(22f,y),stroke);drawCircle(Color(0xFF202025),3*unit,point(x,y));drawCircle(color,3*unit,point(x,y),style=Stroke(stroke))
            }
            CameraIcon.Microphone,CameraIcon.MicOff -> {
                drawRoundRect(color,point(9f,2f),Size(6*unit,12*unit),CornerRadius(3*unit),style=Stroke(stroke))
                drawArc(color,0f,180f,false,point(5f,6f),Size(14*unit,12*unit),style=Stroke(stroke))
                drawLine(color,point(12f,18f),point(12f,22f),stroke);drawLine(color,point(8f,22f),point(16f,22f),stroke)
                if(icon==CameraIcon.MicOff) drawLine(color,point(2f,2f),point(22f,22f),stroke)
            }
            CameraIcon.Back -> {drawLine(color,point(17f,3f),point(8f,12f),stroke);drawLine(color,point(8f,12f),point(17f,21f),stroke)}
            CameraIcon.Share -> {
                drawLine(color,point(5f,12f),point(19f,5f),stroke);drawLine(color,point(5f,12f),point(19f,19f),stroke)
                for(p in listOf(point(5f,12f),point(19f,5f),point(19f,19f))) drawCircle(color,3*unit,p)
            }
        }
    }
}
@Composable
fun CameraIconButton(icon: CameraIcon, label: String, enabled: Boolean=true, onClick: ()->Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).semantics { contentDescription=label }) {
        CameraSymbol(icon,color=if(enabled) Color.White else Color(0xFF88888E))
    }
}
