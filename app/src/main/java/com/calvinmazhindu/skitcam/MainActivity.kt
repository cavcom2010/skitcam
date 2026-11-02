package com.calvinmazhindu.skitcam

import androidx.core.content.edit
import androidx.core.net.toUri
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.calvinmazhindu.skitcam.camera.*
import com.calvinmazhindu.skitcam.photo.PhotoModel
import com.calvinmazhindu.skitcam.recording.CameraLens
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

import com.calvinmazhindu.skitcam.trim.TrimViewModel
import com.calvinmazhindu.skitcam.trim.TrimScreen

import com.calvinmazhindu.skitcam.export.*

class MainActivity : ComponentActivity() {
    val cameraModel: CameraModel by lazy { ViewModelProvider(this)[CameraModel::class.java] }
    val photoModel: PhotoModel by lazy { ViewModelProvider(this)[PhotoModel::class.java] }
    val recordingModel: RecordingViewModel by lazy { ViewModelProvider(this)[RecordingViewModel::class.java] }
    val reviewController: SegmentReviewController by lazy { SegmentReviewController(applicationContext) }
    val trimModel: TrimViewModel by lazy { ViewModelProvider(this)[TrimViewModel::class.java] }
    val arrangementModel: ArrangementViewModel by lazy { ViewModelProvider(this)[ArrangementViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB96969))) {
                Surface(color=Color(0xFF101014),contentColor=Color.White) { CameraScreen() }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        recordingModel.audio.foreground(true)
    }
    override fun onPause() {
        photoModel.cancelTimer()
        cameraModel.video.background()
        cameraModel.idle()
        recordingModel.cameraReady = false
        recordingModel.audio.foreground(false)
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
    val export by ExportJournal.status.collectAsStateWithLifecycle()
    val recovered by recordingModel.recovered.collectAsStateWithLifecycle()
    val commandPending by recordingModel.commandPending.collectAsStateWithLifecycle()
    val camera = cameraModel
    camera.video.canEdit = { com.calvinmazhindu.skitcam.video.VideoProfilePolicy.canChange(recordingModel.snapshots.value,recordingModel.commandPending.value,ExportJournal.busy) && recordingModel.recovered.value && camera.choice.mode==CameraMode.SkitVideo && camera.streaming && !camera.switching && !photoModel.busy }
    camera.video.beforeChange = { recordingModel.cameraReady = false }
    val photos = photoModel
    val finishing = camera.loaded && export?.sessionId == snapshot.session?.id && export != null && camera.acknowledgedExport != export?.sessionId
    LaunchedEffect(camera.loaded, recovered) {
        if(camera.loaded && recovered) {
            // UI collectors can resume in different orders. Read the completed recovery directly.
            val current = recordingModel.snapshots.value
            val result = ExportJournal.status.value
            camera.initialize(current,result!=null && result.sessionId==current.session?.id && camera.acknowledgedExport!=result.sessionId)
        }
    }
    LaunchedEffect(camera.loaded,photos.lastUri) { if(camera.loaded) photos.lastUri?.let { camera.media(it,"image/jpeg",photos.savedAt) } }
    LaunchedEffect(camera.loaded,export?.uri, export?.state) {
        val result = export
        if(camera.loaded && result?.state == ExportState.Completed && result.uri != null) {
            val time = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ExportJournal.read(this@CameraScreen,result.sessionId)?.getLong("createdAt") }.getOrNull()
            }
            if(time != null) camera.media(result.uri.toUri(),"video/mp4",time)
        }
    }
    val photo = camera.choice.mode == CameraMode.Photo
    fun switchAllowed() = CameraPolicy.maySwitch(recordingModel.recovered.value,recordingModel.snapshots.value,
        finishing || ExportJournal.busy,photos.busy || recordingModel.commandPending.value || camera.video.pending) && camera.streaming
    val maySwitch = switchAllowed() && !commandPending
    fun selectCamera(mode: CameraMode = camera.choice.mode, lens: CameraLens? = null) {
        val old = camera.choice
        camera.select(mode,lens,switchAllowed())
        if(camera.choice != old) recordingModel.cameraReady = false
    }
    var quick by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val blocked = snapshot.state in setOf(RecordingState.Starting,RecordingState.Finalising,RecordingState.Stopping)
    val editors = arrangement.draft != null || trimModel.segment != null || review.selection != null || finishing
    val adjustments = !blocked && !editors && !photos.busy && camera.streaming
    LaunchedEffect(editors, blocked) { if(editors || blocked) { quick = false; settings = false } }
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun takePhoto() {
        if(!grantedPhotoPermission(this) || !CameraPolicy.maySwitch(recordingModel.recovered.value,
                recordingModel.snapshots.value,ExportJournal.busy || finishing,photos.busy || recordingModel.commandPending.value)) return
        if(Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            legacyStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        @Suppress("DEPRECATION") val rotation = windowManager.defaultDisplay.rotation
        photos.take(camera.timer,rotation)
    }
    fun openMedia() {
        val uri = camera.latestUri ?: return
        try {
            contentResolver.openInputStream(uri.toUri())!!.use { check(it.read() >= 0) }
            if(camera.latestType == "video/mp4") startActivity(Intent(this,FinishedPlaybackActivity::class.java).setData(uri.toUri()))
            else startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri.toUri(),camera.latestType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(_: Exception) { camera.message = "Saved media is unavailable, or no compatible viewer is installed." }
    }
    val trimming = trimModel.segment != null
    val arranging = arrangement.draft != null || trimming || finishing
    val thumbnails = remember { ThumbnailLoader() }
    var headerHeight by remember { mutableIntStateOf(0) }
    var controlsHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(snapshot.state, snapshot.session?.id) {
        if (snapshot.state != RecordingState.Paused || trimModel.sessionId?.let { it != snapshot.session?.id } == true) trimModel.close()
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
        preferences.edit { putBoolean("requested", true) }
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
        if(!recovered || !camera.ready || !photos.recovered) {
            CameraMessage("Restoring camera…")
        } else if (granted && !(finishing && snapshot.state == RecordingState.Completed)) {
            CameraPreview(recordingModel,camera,photos,gesturesEnabled=adjustments && !quick && !settings, modifier=Modifier.fillMaxSize().then(if(photo && landscape) Modifier.padding(end=280.dp) else Modifier))
        } else if (!granted) {
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
                            "package:$packageName".toUri()))
                    } else requestPermission()
                }
            )
        }
        if(camera.ready && recovered) Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.65f),Color.Transparent)))
            .onSizeChanged { headerHeight = it.height }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(start=8.dp,end=if(landscape) 288.dp else 8.dp).then(if(arranging) Modifier.clearAndSetSemantics {} else Modifier),
                horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={settings=true;quick=false},enabled=maySwitch) { Text("Settings") }
                Text("${if(camera.choice.lens==CameraLens.Rear) stringResource(R.string.rear_camera) else "Front camera"} · ${camera.resolution}",style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f))
                TextButton(onClick={quick=!quick},enabled=adjustments) { Text("Quick") }
            }
            if(!photo && !arranging && review.selection==null) com.calvinmazhindu.skitcam.audio.AudioIndicator(recordingModel.audio,
                Modifier.padding(start=8.dp,end=if(landscape) 280.dp else 8.dp))
        }
        if (!photo && !arranging && snapshot.state == RecordingState.Paused && snapshot.session != null && headerHeight > 0 && controlsHeight > 0) {
            val top = with(density) { headerHeight.toDp() }
            val bottom = with(density) { controlsHeight.toDp() }
            PausedEnvironment(snapshot.session!!, landscape, recordingModel, review, thumbnails,
                onTrim = { trimModel.open(snapshot.session!!, it) }, modifier = Modifier.fillMaxSize()
                    .padding(top = top, bottom = if (landscape) 0.dp else bottom,
                        end = if (landscape) 280.dp else 0.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(
                        if (landscape) WindowInsetsSides.Start + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal))
                    .padding(8.dp))
        }
        Column(
            (if (landscape) Modifier.align(Alignment.CenterEnd).width(280.dp).fillMaxHeight()
            else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                .onSizeChanged { controlsHeight = it.height }
                .then(if (arranging) Modifier.clearAndSetSemantics {} else Modifier)
                .background(if (landscape) Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
                else Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    if (landscape) WindowInsetsSides.End + WindowInsetsSides.Vertical
                    else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            if(camera.ready && recovered && !finishing) {
                if(!snapshot.openSession) {
                    @Composable fun ModeChip(mode: CameraMode, label: String, modifier: Modifier = Modifier) {
                        FilterChip(selected=camera.choice.mode==mode,onClick={photos.cancelTimer();selectCamera(mode=mode)},
                            enabled=maySwitch && !camera.switching,label={Text(label)},modifier=modifier)
                    }
                    if(landscape) Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                        ModeChip(CameraMode.Photo,"Photo",Modifier.fillMaxWidth())
                        ModeChip(CameraMode.SkitVideo,"Skit Video",Modifier.fillMaxWidth())
                    } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ModeChip(CameraMode.Photo,"Photo")
                        ModeChip(CameraMode.SkitVideo,"Skit Video")
                    }
                }
                if(photo) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                        MediaShortcut(camera.latestUri,camera.latestType,camera.latestUri!=null && !photos.busy,::openMedia)
                        Button(onClick=::takePhoto,enabled=granted && maySwitch && !camera.switching,shape=CircleShape,
                            colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,contentColor=Color.White),contentPadding=PaddingValues(0.dp),modifier=Modifier.size(80.dp)
                                .semantics { contentDescription="Take photo" }) { androidx.compose.foundation.Canvas(Modifier.size(76.dp)) { drawCircle(Color.White,radius=size.minDimension/2-2.dp.toPx(),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()));drawCircle(Color.White,radius=size.minDimension/2-8.dp.toPx()) } }
                        LensSwitch(camera.choice.lens==CameraLens.Front,maySwitch && camera.hasOtherLens) {
                            selectCamera(lens=if(camera.choice.lens==CameraLens.Rear) CameraLens.Front else CameraLens.Rear)
                        }
                    }
                    if(photos.countdown>0) Text("${photos.countdown} · Cancel",modifier=Modifier.semantics { contentDescription="Photo countdown ${photos.countdown}" })
                    if(photos.countdown>0) TextButton(onClick=photos::cancelTimer) { Text("Cancel timer") }
                    photos.message?.let { Text(it,style=MaterialTheme.typography.labelMedium) }
                    if(photos.canRetry) TextButton(onClick=photos::retry) { Text("Retry Save") }
                } else {
                    if(!snapshot.openSession) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        MediaShortcut(camera.latestUri,camera.latestType,maySwitch && camera.latestUri!=null,::openMedia)
                        LensSwitch(camera.choice.lens==CameraLens.Front,maySwitch && camera.hasOtherLens) {
                            selectCamera(lens=if(camera.choice.lens==CameraLens.Rear) CameraLens.Front else CameraLens.Rear)
                        }
                    }
                    RecordingControls(this@CameraScreen, recordingModel, cameraGranted = granted && !camera.switching,
                beforeRecording = { trimModel.close(); review.close(); arrangement.invalidate() },
                onBackground = { review.close() },
                onArrange = {
                    val current = recordingModel.snapshots.value
                    if (current.state == RecordingState.Paused && current.count >= 2 && !current.unresolved) {
                        review.close()
                        arrangement.open(current.session!!)
                    }
                })
                }
                if(camera.focus!=null) Text(camera.focusResult,style=MaterialTheme.typography.labelSmall)
                camera.message?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
            }
        }
        if(quick && !editors) Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=56.dp)) {
            CameraTools(camera,false,adjustments,snapshot.session?.segments?.lastOrNull()?.media,snapshot.openSession) { quick=false }
        }
        if(settings && maySwitch) CameraTools(camera,true,adjustments,snapshot.session?.segments?.lastOrNull()?.media,snapshot.openSession) { settings=false }
        if (arrangement.draft != null && snapshot.state == RecordingState.Paused && snapshot.session != null) {
            ArrangementScreen(snapshot.session!!, arrangement, recordingModel, review, thumbnails)
        }
        if (trimming && snapshot.state == RecordingState.Paused) TrimScreen(trimModel, recordingModel, review)
        if (finishing) ExportScreen(export!!) { camera.acknowledge(export!!.sessionId) }
    }
}

@Composable
internal fun CameraMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(
        start = 32.dp, end = if (landscape) 280.dp else 32.dp,
        top = if (landscape) 88.dp else 150.dp, bottom = if (landscape) 32.dp else 150.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun grantedPhotoPermission(context: Context) = ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
