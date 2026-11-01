package com.calvinmazhindu.skitcam

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner

private enum class PreviewStatus { Loading, Streaming, Error }

@Composable
internal fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var status by remember { mutableStateOf(PreviewStatus.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, view, attempt) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
        status = PreviewStatus.Loading
        val stateObserver = Observer<CameraState> { state ->
            if (state.error != null) {
                Log.w("SkitCam", "Camera error: ${state.error?.code}")
                status = PreviewStatus.Error
            }
        }
        val streamObserver = Observer<PreviewView.StreamState> { stream ->
            if (stream == PreviewView.StreamState.STREAMING) {
                Log.d("SkitCam", "Rear-camera preview streaming")
                status = PreviewStatus.Streaming
            }
        }
        view.previewStreamState.observe(lifecycleOwner, streamObserver)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) {
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    check(cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        "No rear camera available"
                    }
                    val useCase = Preview.Builder().build().also {
                        it.surfaceProvider = view.surfaceProvider
                    }
                    preview = useCase
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCase
                    )
                    cameraState = camera.cameraInfo.cameraState.also {
                        it.observe(lifecycleOwner, stateObserver)
                    }
                } catch (exception: Exception) {
                    Log.e("SkitCam", "Unable to start rear-camera preview", exception)
                    status = PreviewStatus.Error
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            view.previewStreamState.removeObserver(streamObserver)
            cameraState?.removeObserver(stateObserver)
            preview?.let { provider?.unbind(it) }
        }
    }

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize())
        when (status) {
            PreviewStatus.Loading -> CameraMessage(stringResource(R.string.starting_camera))
            PreviewStatus.Error -> CameraMessage(stringResource(R.string.camera_error),
                stringResource(R.string.try_again), onAction = { attempt++ })
            PreviewStatus.Streaming -> Unit
        }
    }
}
