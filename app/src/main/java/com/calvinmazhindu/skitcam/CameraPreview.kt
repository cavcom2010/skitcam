package com.calvinmazhindu.skitcam

import android.util.Rational
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.calvinmazhindu.skitcam.camera.*
import com.calvinmazhindu.skitcam.photo.PhotoModel
import com.calvinmazhindu.skitcam.recording.*

@Composable
internal fun CameraPreview(model: RecordingViewModel, cameraModel: CameraModel, photos: PhotoModel,
    gesturesEnabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val choice = cameraModel.choice
    val photo = choice.mode == CameraMode.Photo
    val window = LocalWindowInfo.current.containerSize
    val landscape = window.width > window.height
    // A new view per idle binding choice prevents accepting the previous stream's cached STREAMING event.
    val view = remember(context, choice) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    } }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner, view, choice, attempt) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var image: ImageCapture? = null
        var info: androidx.lifecycle.LiveData<CameraState>? = null
        var bound = false
        error = null
        model.cameraReady = false
        fun updateReadiness() {
            if(disposed || !bound) return
            val active = error == null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                info?.value?.type == CameraState.Type.OPEN && view.previewStreamState.value == PreviewView.StreamState.STREAMING
            if(active) {
                cameraModel.streamed()
                model.boundLens = choice.lens
                model.cameraReady = !photo
            } else {
                model.cameraReady = false
                cameraModel.idle()
            }
        }
        val stateObserver = Observer<CameraState> { state ->
            if (state.error != null && !disposed) {
                error = "Camera unavailable (${state.error?.code})."
                cameraModel.failed(error!!)
                if (!photo) model.cameraError()
            }
            updateReadiness()
        }
        val streamObserver = Observer<PreviewView.StreamState> { updateReadiness() }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_RESUME) view.post { updateReadiness() }
            else if(event == Lifecycle.Event.ON_PAUSE) updateReadiness()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                val cameraProvider = future.get(); provider = cameraProvider
                val selector = if(choice.lens == CameraLens.Rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                check(cameraProvider.hasCamera(selector)) { "Selected camera is unavailable. The saved session is unchanged." }
                cameraModel.hasOtherLens = cameraProvider.hasCamera(if(choice.lens == CameraLens.Rear) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA)
                view.scaleType = if(photo) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
                fun bind(highResolution: Boolean): Camera {
                    val useCase = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    preview = useCase
                    if(!photo) return cameraProvider.bindToLifecycle(lifecycleOwner,selector,useCase,model.port.capture)
                    val resolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .setAllowedResolutionMode(if(highResolution) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                        .build()
                    val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100).setResolutionSelector(resolution)
                        .setTargetRotation(view.display?.rotation ?: android.view.Surface.ROTATION_0).build()
                    image = capture
                    val group = UseCaseGroup.Builder().addUseCase(useCase).addUseCase(capture)
                        .setViewPort(ViewPort.Builder(Rational(if(landscape) 4 else 3,if(landscape) 3 else 4),view.display?.rotation ?: android.view.Surface.ROTATION_0).setScaleType(ViewPort.FIT).build()).build()
                    return cameraProvider.bindToLifecycle(lifecycleOwner,selector,group)
                }
                val camera = try { bind(true) } catch(first: Exception) {
                    if(!photo) throw first
                    preview?.let { cameraProvider.unbind(it) }; image?.let { cameraProvider.unbind(it) }
                    bind(false)
                }
                photos.capture = image
                val resolution = if(photo) image?.resolutionInfo else model.port.capture.resolutionInfo
                cameraModel.bound(camera, resolution?.resolution?.let { "${it.width} × ${it.height}" }.orEmpty() + if(photo) " · JPEG" else " · Auto FPS")
                info = camera.cameraInfo.cameraState.also { it.observe(lifecycleOwner,stateObserver) }
                bound = true
                view.previewStreamState.observe(lifecycleOwner,streamObserver)
            } catch(e: Exception) {
                error = e.message ?: "Cannot start camera."
                cameraModel.failed(error!!)
                if(!photo) model.cameraError()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true; bound = false
            model.cameraReady = false
            if(!photo) model.background()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            view.previewStreamState.removeObserver(streamObserver)
            info?.removeObserver(stateObserver)
            preview?.let { provider?.unbind(it) }
            image?.let { provider?.unbind(it) }
            if(!photo) provider?.unbind(model.port.capture)
            if(photos.capture === image) photos.capture = null
            cameraModel.released()
        }
    }
    BoxWithConstraints(modifier) {
        val ratio = if(landscape) 4f/3f else 3f/4f
        val photoBounds = if(maxWidth > maxHeight * ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
            else Modifier.fillMaxWidth().aspectRatio(ratio)
        Box((if(photo) photoBounds.align(Alignment.Center) else Modifier.fillMaxSize())
            .pointerInput(gesturesEnabled,choice) { if(gesturesEnabled) detectTapGestures { cameraModel.focus(view,it) } }
            .pointerInput(gesturesEnabled,choice) { if(gesturesEnabled) detectTransformGestures { _, _, scale, _ -> cameraModel.adjustZoom(cameraModel.zoom * scale) } }
            .semantics { contentDescription = "Camera preview. Tap to focus; pinch to zoom."
                if(gesturesEnabled) onClick(label="Focus at center") { cameraModel.focus(view,Offset(view.width/2f,view.height/2f));true } }) {
            key(view) { AndroidView(factory = { view }, modifier = Modifier.matchParentSize()) }
            Canvas(Modifier.matchParentSize()) {
                if(cameraModel.grid) {
                    for(i in 1..2) {
                        drawLine(Color.White.copy(alpha=.4f),Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height),1.dp.toPx())
                        drawLine(Color.White.copy(alpha=.4f),Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3),1.dp.toPx())
                    }
                }
                cameraModel.focus?.let { point -> drawCircle(Color.White,26.dp.toPx(),point,style=Stroke(2.dp.toPx())) }
            }
        }
        if(error != null) CameraMessage(stringResource(R.string.camera_error) + " " + error!!,stringResource(R.string.try_again)) { attempt++ }
        else if(!cameraModel.streaming) CameraMessage(stringResource(R.string.starting_camera))
    }
}
