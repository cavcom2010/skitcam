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

enum class CameraIcon {
    Settings, Quick, Microphone, MicOff, Record, Photo, Play, Pause, Stop, Start, PlayFromStart, Back, Share, More,
    Check, Reset, Delete, Close, Loop, Restore, Gallery, SwitchCamera
}

/** Original simple geometry, independent of an external icon library. */
@Composable
fun CameraSymbol(icon: CameraIcon, modifier: Modifier=Modifier, color: Color=Color.White) {
    Canvas(modifier.size(24.dp)) {
        val unit=size.minDimension/24f;val stroke=2f*unit
        fun point(x: Float,y: Float)=Offset(x*unit,y*unit)
        when(icon) {
            CameraIcon.Check -> {
                drawLine(color,point(4f,12f),point(9f,17f),stroke)
                drawLine(color,point(9f,17f),point(21f,5f),stroke)
            }
            CameraIcon.Close -> {
                drawLine(color,point(5f,5f),point(19f,19f),stroke)
                drawLine(color,point(19f,5f),point(5f,19f),stroke)
            }
            CameraIcon.Reset, CameraIcon.Restore -> {
                drawArc(color,-60f,285f,false,point(5f,5f),Size(15*unit,15*unit),style=Stroke(stroke))
                drawLine(color,point(5f,4f),point(5f,11f),stroke)
                drawLine(color,point(5f,11f),point(12f,11f),stroke)
            }
            CameraIcon.Delete -> {
                drawLine(color,point(3f,6f),point(21f,6f),stroke)
                drawLine(color,point(9f,3f),point(15f,3f),stroke)
                drawRoundRect(color,point(6f,6f),Size(12*unit,15*unit),CornerRadius(2*unit),style=Stroke(stroke))
                drawLine(color,point(10f,10f),point(10f,17f),stroke)
                drawLine(color,point(14f,10f),point(14f,17f),stroke)
            }
            CameraIcon.Loop -> {
                drawLine(color,point(4f,7f),point(19f,7f),stroke)
                drawLine(color,point(19f,7f),point(15f,3f),stroke)
                drawLine(color,point(19f,7f),point(15f,11f),stroke)
                drawLine(color,point(20f,17f),point(5f,17f),stroke)
                drawLine(color,point(5f,17f),point(9f,13f),stroke)
                drawLine(color,point(5f,17f),point(9f,21f),stroke)
            }
            CameraIcon.Gallery -> {
                drawRoundRect(color,point(2f,3f),Size(20*unit,18*unit),CornerRadius(2*unit),style=Stroke(stroke))
                drawCircle(color,2*unit,point(16f,8f))
                drawLine(color,point(3f,18f),point(9f,11f),stroke)
                drawLine(color,point(9f,11f),point(18f,21f),stroke)
            }
            CameraIcon.SwitchCamera -> {
                drawRoundRect(color,point(2f,6f),Size(20*unit,16*unit),CornerRadius(2*unit),style=Stroke(stroke))
                drawLine(color,point(8f,3f),point(16f,3f),stroke)
                drawLine(color,point(6f,11f),point(18f,11f),stroke)
                drawLine(color,point(18f,11f),point(15f,8f),stroke)
                drawLine(color,point(18f,17f),point(6f,17f),stroke)
                drawLine(color,point(6f,17f),point(9f,20f),stroke)
            }
            CameraIcon.More -> repeat(3) { drawCircle(color,1.7f*unit,point(12f,5f+it*7f)) }
            CameraIcon.Record -> drawCircle(color,radius=size.minDimension*.30f)
            CameraIcon.Photo -> {
                drawRoundRect(color,point(2f,7f),Size(20*unit,14*unit),CornerRadius(3*unit),style=Stroke(stroke))
                drawRoundRect(color,point(8f,4f),Size(8*unit,3*unit),CornerRadius(1*unit),style=Stroke(stroke))
                drawCircle(color,4.5f*unit,point(12f,14f),style=Stroke(stroke))
            }
            CameraIcon.Play -> {
                val path=androidx.compose.ui.graphics.Path().apply {
                    moveTo(8*unit,4*unit);lineTo(20*unit,12*unit);lineTo(8*unit,20*unit);close()
                }
                drawPath(path,color)
            }
            CameraIcon.Pause -> { drawRect(color,point(6f,5f),Size(4*unit,14*unit));drawRect(color,point(14f,5f),Size(4*unit,14*unit)) }
            CameraIcon.Start -> {
                drawRect(color,point(4f,4f),Size(3*unit,16*unit))
                val path=androidx.compose.ui.graphics.Path().apply {
                    moveTo(20*unit,4*unit);lineTo(9*unit,12*unit);lineTo(20*unit,20*unit);close()
                }
                drawPath(path,color)
            }
            CameraIcon.PlayFromStart -> {
                drawRect(color,point(3f,5f),Size(3*unit,14*unit))
                val path=androidx.compose.ui.graphics.Path().apply {
                    moveTo(9*unit,4*unit);lineTo(21*unit,12*unit);lineTo(9*unit,20*unit);close()
                }
                drawPath(path,color)
            }
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
    com.calvinmazhindu.skitcam.ui.components.SkitCamIconAction(
        icon, label, onClick, enabled = enabled,
        tint = com.calvinmazhindu.skitcam.ui.theme.SkitCamColors.TextPrimary)
}
