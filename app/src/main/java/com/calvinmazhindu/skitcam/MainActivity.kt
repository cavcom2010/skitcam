package com.calvinmazhindu.skitcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.calvinmazhindu.skitcam.recording.RecordingControls
import com.calvinmazhindu.skitcam.recording.RecordingViewModel
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.review.PausedEnvironment
import com.calvinmazhindu.skitcam.review.SegmentReviewController
import com.calvinmazhindu.skitcam.review.ThumbnailLoader
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calvinmazhindu.skitcam.arrange.ArrangementViewModel
import com.calvinmazhindu.skitcam.arrange.ArrangementScreen
import androidx.compose.ui.semantics.clearAndSetSemantics

class MainActivity : ComponentActivity() {
    val recordingModel: RecordingViewModel by lazy { ViewModelProvider(this)[RecordingViewModel::class.java] }
    val reviewController: SegmentReviewController by lazy { SegmentReviewController(applicationContext) }
    val arrangementModel: ArrangementViewModel by lazy { ViewModelProvider(this)[ArrangementViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB96969))) {
                CameraScreen()
            }
        }
    }
    override fun onPause() {
        arrangementModel.cancelDrag()
        reviewController.close()
        recordingModel.background()
        super.onPause()
    }
    override fun onDestroy() {
        reviewController.close()
        super.onDestroy()
    }
}

@Composable
private fun MainActivity.CameraScreen() {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    val snapshot by recordingModel.snapshots.collectAsStateWithLifecycle()
    val review = reviewController
    val arrangement = arrangementModel
    val arranging = arrangement.draft != null
    val thumbnails = remember { ThumbnailLoader() }
    var headerHeight by remember { mutableIntStateOf(0) }
    var controlsHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(snapshot.state, snapshot.session?.id) {
        if (snapshot.state != RecordingState.Paused ||
            review.selection?.sessionId?.let { it != snapshot.session?.id } == true) review.close()
        if (snapshot.state != RecordingState.Paused ||
            arrangement.draft?.sessionId?.let { it != snapshot.session?.id } == true) arrangement.invalidate()
    }
    val preferences = remember { getSharedPreferences("camera_permission", Context.MODE_PRIVATE) }
    fun hasPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }
    var requested by rememberSaveable {
        mutableStateOf(preferences.getBoolean("requested", false))
    }
    var canRetry by remember { mutableStateOf(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) }
    var initialRequestHandled by rememberSaveable { mutableStateOf(requested || granted) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        canRetry = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }
    val requestPermission = {
        requested = true
        preferences.edit().putBoolean("requested", true).apply()
        launcher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!initialRequestHandled) {
            initialRequestHandled = true
            requestPermission()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasPermission()
                canRetry = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        if (granted) {
            CameraPreview(recordingModel, Modifier.fillMaxSize())
        } else {
            val needsSettings = requested && !canRetry
            CameraMessage(
                message = stringResource(
                    if (needsSettings) R.string.camera_settings
                    else if (requested) R.string.camera_denied
                    else R.string.camera_permission
                ),
                action = stringResource(if (needsSettings) R.string.open_settings else R.string.allow_camera),
                onAction = {
                    if (needsSettings) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    } else requestPermission()
                }
            )
        }
        Box(Modifier.fillMaxWidth().height(140.dp).background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))
        ))
        Row(
            Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                .then(if (arranging) Modifier.clearAndSetSemantics {} else Modifier)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                .padding(start = 24.dp, top = 24.dp, bottom = 24.dp,
                    end = if (landscape) 264.dp else 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rear_camera), color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall)
        }
        if (!arranging && snapshot.state == RecordingState.Paused && snapshot.session != null && headerHeight > 0 && controlsHeight > 0) {
            val top = with(density) { headerHeight.toDp() }
            val bottom = with(density) { controlsHeight.toDp() }
            PausedEnvironment(snapshot.session!!, landscape, recordingModel, review, thumbnails,
                Modifier.fillMaxSize()
                    .padding(top = top, bottom = if (landscape) 0.dp else bottom,
                        end = if (landscape) 240.dp else 0.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(
                        if (landscape) WindowInsetsSides.Start + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal))
                    .padding(8.dp))
        }
        Column(
            (if (landscape) Modifier.align(Alignment.CenterEnd).width(240.dp).fillMaxHeight()
            else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                .onSizeChanged { controlsHeight = it.height }
                .then(if (arranging) Modifier.clearAndSetSemantics {} else Modifier)
                .background(if (landscape) Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
                else Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    if (landscape) WindowInsetsSides.End + WindowInsetsSides.Vertical
                    else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )).padding(horizontal = if (landscape) 16.dp else 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            RecordingControls(this@CameraScreen, recordingModel, cameraGranted = granted,
                beforeRecording = { review.close(); arrangement.invalidate() },
                onBackground = { review.close() },
                onArrange = {
                    val current = recordingModel.snapshots.value
                    if (current.state == RecordingState.Paused && current.count >= 2 && !current.unresolved) {
                        review.close()
                        arrangement.open(current.session!!)
                    }
                })
        }
        if (arranging && snapshot.state == RecordingState.Paused && snapshot.session != null) {
            ArrangementScreen(snapshot.session!!, arrangement, recordingModel, review, thumbnails)
        }
    }
}

@Composable
internal fun CameraMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(
        start = 32.dp, end = if (landscape) 240.dp else 32.dp,
        top = if (landscape) 88.dp else 150.dp, bottom = if (landscape) 32.dp else 150.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }
}
