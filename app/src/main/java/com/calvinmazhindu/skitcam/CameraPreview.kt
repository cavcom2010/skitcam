package com.calvinmazhindu.skitcam

import android.util.Rational
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.calvinmazhindu.skitcam.video.*
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

@OptIn(ExperimentalSessionConfig::class)
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
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
    val videoRevision = if(photo) 0 else cameraModel.video.revision
    val view = remember(context, choice, videoRevision) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    } }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner, view, choice, attempt, videoRevision) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var image: ImageCapture? = null
        var videoCapture: androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>? = null
        var profileAvailable = false
        var info: androidx.lifecycle.LiveData<CameraState>? = null
        var bound = false
        error = null
        model.cameraReady = false
        fun updateReadiness() {
            if(disposed || !bound) return
            val active = error == null && cameraModel.zoomReady && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                info?.value?.type == CameraState.Type.OPEN && view.previewStreamState.value == PreviewView.StreamState.STREAMING
            if(active) {
                cameraModel.streamed()
                model.boundLens = choice.lens
                cameraModel.video.streamed()
                model.cameraReady = !photo && profileAvailable && cameraModel.video.ready
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
                view.scaleType = PreviewView.ScaleType.FIT_CENTER
                fun bind(highResolution: Boolean): Camera {
                    val builder=Preview.Builder()
                    val snapshot=model.snapshots.value
                    val open=snapshot.session?.takeIf { snapshot.openSession }
                    val cameraInfo=cameraProvider.getCameraInfo(selector)
                    val requested=if(photo) null else cameraModel.video.desired(cameraInfo,open,model.legacyMedia.value)
                    if(requested!=null) {
                        val quality=when(requested.quality) {
                            "FHD" -> androidx.camera.video.Quality.FHD
                            "UHD" -> androidx.camera.video.Quality.UHD
                            "HD" -> androidx.camera.video.Quality.HD
                            else -> androidx.camera.video.Quality.SD
                        }
                        val size=checkNotNull(androidx.camera.video.QualitySelector.getResolution(cameraInfo,quality)) { "Video profile dimensions are unavailable." }
                        val aspect=if(ViewfinderGeometry.isFourThree(size.width,size.height)) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
                        // Preview's default 4:3 stream does not represent a 16:9 recording.
                        // This selects preview geometry, without changing the frozen Recorder profile.
                        builder.setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(
                            AspectRatioStrategy(aspect,AspectRatioStrategy.FALLBACK_RULE_AUTO)).build())
                    }
                    Camera2Interop.Extender(builder).setSessionCaptureCallback(cameraModel.framing.callback(if(photo) null else cameraModel.video.callback()))
                    val useCase = builder.build().also { it.surfaceProvider = view.surfaceProvider }
                    preview = useCase
                    if(!photo) {
                        var capture=model.port.prepare(checkNotNull(requested))
                        if(!cameraModel.video.configuration(cameraInfo,useCase,capture)) capture=model.port.prepare(cameraModel.video.settings)
                        videoCapture=capture
                        val bound=cameraProvider.bindToLifecycle(lifecycleOwner,selector,useCase,capture)
                        val size=checkNotNull(capture.resolutionInfo).resolution
                        val profile=cameraModel.video.profile(size.width,size.height)
                        require(open?.captureProfile?.sameConfiguration(profile)!=false) { "Original capture dimensions are unavailable. RESUME is blocked." }
                        if(open!=null && open.captureProfile==null) require(model.legacyMedia.value?.let { media ->
                            maxOf(media.width,media.height)==maxOf(size.width,size.height) && minOf(media.width,media.height)==minOf(size.width,size.height)
                        }==true) { "Legacy capture dimensions cannot be restored. RESUME is blocked." }
                        model.port.bound(profile)
                        profileAvailable=true
                        cameraModel.video.bound(bound)
                        return bound
                    }
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
                cameraModel.framing.bind(camera,preview,if(photo) image else videoCapture)
                val resolution = if(photo) image?.resolutionInfo else videoCapture?.resolutionInfo
                cameraModel.bound(camera, resolution?.resolution?.let { "${it.width} × ${it.height}" }.orEmpty() + if(photo) " · JPEG" else " · ${cameraModel.video.settings.fps?.let { "$it fps target" } ?: "Auto FPS"}", resolution?.resolution?.let { BoundCameraInfo(it.width,it.height,photo,if(photo) null else cameraModel.video.settings.fps) },resolution?.rotationDegrees ?: 0) { failure ->
                    if(!disposed) {
                        if(failure!=null) { error=failure;cameraModel.failed(failure) }
                        updateReadiness()
                    }
                }
                info = camera.cameraInfo.cameraState.also { it.observe(lifecycleOwner,stateObserver) }
                bound = true
                view.previewStreamState.observe(lifecycleOwner,streamObserver)
            } catch(e: Exception) {
                error = e.message ?: "Cannot start camera."
                if(!photo) {
                    cameraModel.video.failed(error!!)
                    // Configuration failure during recovery must not rewrite a paused journal.
                    runCatching {
                        videoCapture?.let { cameraProvider -> provider?.unbind(cameraProvider) }
                        preview?.let { provider?.unbind(it) }
                        val fallback=Preview.Builder().build().also { it.surfaceProvider=view.surfaceProvider }
                        preview=fallback
                        val selector=if(choice.lens==CameraLens.Rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        val camera=provider!!.bindToLifecycle(lifecycleOwner,selector,fallback)
                        cameraModel.bound(camera,"Preview only · RESUME blocked")
                        info=camera.cameraInfo.cameraState.also { it.observe(lifecycleOwner,stateObserver) }
                        bound=true;error=null
                        view.previewStreamState.observe(lifecycleOwner,streamObserver)
                    }
                } else cameraModel.failed(error!!)
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
            videoCapture?.let { provider?.unbind(it) }
            if(photos.capture === image) photos.capture = null
            cameraModel.released()
        }
    }
    LaunchedEffect(cameraModel.video.pending,cameraModel.video.whiteBalanceStatus,cameraModel.streaming,cameraModel.zoomReady) {
        if(!photo && cameraModel.streaming) model.cameraReady=cameraModel.zoomReady && cameraModel.video.ready && model.port.profile!=null &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    BoxWithConstraints(modifier) {
        val ratio = if(photo) { if(landscape) 4f/3f else 3f/4f } else cameraModel.videoAspect
        val frame=ratio?.let { ViewfinderGeometry.fit(maxWidth.value,maxHeight.value,it) }
        val bounds=if(frame!=null) Modifier.size(frame.width.dp,frame.height.dp) else Modifier.fillMaxSize()
        Box(bounds.align(Alignment.Center)
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
