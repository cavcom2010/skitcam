package com.calvinmazhindu.skitcam.photo

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/** Activity recreation keeps the draft/countdown; process-level IO completes an already captured save. */
class PhotoModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private var shared: PhotoRuntime? = null
        private fun runtime(app: Application) = synchronized(this) { shared ?: PhotoRuntime(app).also { shared = it } }
    }
    private val runtime = runtime(application)
    val busy get() = runtime.busy || countdown > 0
    val message get() = runtime.message
    val lastUri get() = runtime.lastUri
    val saveCount get() = runtime.saveCount
    val lastSaveSucceeded get() = runtime.lastSaveSucceeded
    val savedAt get() = runtime.savedAt
    val canRetry get() = runtime.retry != null && !busy
    val recovered get() = runtime.recovered
    var capture: ImageCapture? = null
    var countdown by mutableIntStateOf(0); private set
    private var timerJob: Job? = null
    fun take(seconds: Int, rotation: Int) {
        if(busy || !recovered) return
        val useCase = capture ?: return
        runtime.message = null;runtime.lastSaveSucceeded=false
        timerJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) { countdown = remaining; delay(1000) }
            countdown = 0
            if(capture === useCase) runtime.take(useCase,rotation)
        }
    }
    fun cancelTimer() { timerJob?.cancel(); timerJob = null; countdown = 0 }
    fun retry() { if(canRetry) runtime.save(runtime.retry!!) }
    override fun onCleared() { cancelTimer(); capture = null }
}

private class PhotoRuntime(app: Application) {
    val store = PhotoStore(app)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var busy by mutableStateOf(false)
    var recovered by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var retry: File? = null
    var lastUri by mutableStateOf<android.net.Uri?>(null)
    var saveCount by mutableIntStateOf(0)
    var lastSaveSucceeded by mutableStateOf(false)
    var savedAt = 0L
    init { worker.execute {
        val pending = store.recover()
        val latest = store.latestPublished()
        main.post { savedAt = latest?.second ?: 0L; lastUri = latest?.first; retry = pending; recovered = true; if(pending != null) message = "Photo saving was interrupted. Retry Save keeps the original capture." }
    } }
    fun take(capture: ImageCapture, rotation: Int) {
        if(busy) return
        busy = true;lastSaveSucceeded=false
        worker.execute {
            try {
                val dir = store.create()
                main.post {
                    capture.targetRotation = rotation
                    val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = false }
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(File(dir,"source.jpg")).setMetadata(metadata).build(), worker,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                try { store.captured(dir); main.post { save(dir, alreadyBusy = true) } }
                                catch(e: Exception) { main.post { busy = false; message = "Photo metadata save failed: ${e.message}. Capture retained privately." } }
                            }
                            override fun onError(exception: ImageCaptureException) {
                                main.post { busy = false; message = "Photo capture failed: ${exception.message}" }
                            }
                        })
                }
            } catch(e: Exception) { main.post { busy = false; message = e.message ?: "Cannot prepare photo." } }
        }
    }
    fun save(dir: File, alreadyBusy: Boolean = false) {
        if(busy && !alreadyBusy) return
        busy = true;lastSaveSucceeded=false
        worker.execute {
            try {
                val (uri,facts) = store.publish(dir)
                val createdAt = store.read(dir).getLong("createdAt")
                val pending = store.recover()
                main.post { saveCount++;lastSaveSucceeded=true;savedAt = createdAt; lastUri = uri; retry = pending; busy = false; message = "Photo saved · ${facts.width} × ${facts.height}" + if(pending!=null) ". Another retained photo needs Retry Save." else "" }
            } catch(e: Exception) { main.post { retry = dir; busy = false; message = e.message ?: "Photo saving failed. Capture retained privately." } }
        }
    }
}
