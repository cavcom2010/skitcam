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
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.calvinmazhindu.skitcam.camera.*
import com.calvinmazhindu.skitcam.ui.camera.CameraHeader
import com.calvinmazhindu.skitcam.ui.camera.CameraModeToggle
import com.calvinmazhindu.skitcam.ui.camera.PhotoControls
import com.calvinmazhindu.skitcam.ui.camera.blockPreviewInput
import com.calvinmazhindu.skitcam.ui.components.SkitCamMessagePanel
import com.calvinmazhindu.skitcam.ui.theme.SkitCamColors
import com.calvinmazhindu.skitcam.ui.theme.SkitCamTheme
import com.calvinmazhindu.skitcam.photo.PhotoModel
import com.calvinmazhindu.skitcam.photo.PhotoAspectRatio
import com.calvinmazhindu.skitcam.photo.PhotoAspectSelector
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.calvinmazhindu.skitcam.recording.CameraOrientationController
import com.calvinmazhindu.skitcam.review.PausedEnvironment
import com.calvinmazhindu.skitcam.review.SegmentReviewController
import com.calvinmazhindu.skitcam.review.ThumbnailLoader
import com.calvinmazhindu.skitcam.playback.SkitPlaybackController
import com.calvinmazhindu.skitcam.playback.SkitPlaybackScreen
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
    val orientation: CameraOrientationController by lazy { CameraOrientationController(this) }
    val playback: SkitPlaybackController by lazy { SkitPlaybackController(applicationContext) }
    private val beforeCapture: (Int) -> Unit = { degrees ->
        orientation.beforeCapture(degrees)
        check(cameraModel.previewDisplayRotation==orientation.displayRotation) { "Wait for the rotated camera preview before recording." }
        playback.close()
        reviewController.close()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingModel.port.beforeStart=beforeCapture
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        setContent {
            SkitCamTheme {
                Surface(color=SkitCamColors.Ink,contentColor=SkitCamColors.TextPrimary) { CameraScreen() }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        orientation.start()
        recordingModel.audio.foreground(true)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientation.refreshDisplay()
    }
    override fun onPause() {
        orientation.stop()
        photoModel.cancelTimer()
        cameraModel.video.background()
        cameraModel.idle()
        recordingModel.cameraReady = false
        recordingModel.audio.foreground(false)
        arrangementModel.cancelDrag()
        playback.close()
        reviewController.close()
        recordingModel.background()
        super.onPause()
    }
    override fun onDestroy() {
        orientation.stop()
        if(recordingModel.port.beforeStart===beforeCapture) recordingModel.port.beforeStart=null
        playback.close()
        reviewController.close()
        super.onDestroy()
    }
}

@Composable
internal fun MainActivity.CameraScreen() {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    // Insets belong outside the usable dock width; a landscape navigation bar must
    // not squeeze RESUME/STOP down to a few characters per line.
    val dockWidth = (if(density.fontScale>1.5f) 192.dp else 152.dp) +
        if(landscape) WindowInsets.safeDrawing.asPaddingValues().calculateEndPadding(LocalLayoutDirection.current) else 0.dp
    val snapshot by recordingModel.snapshots.collectAsStateWithLifecycle()
    val review = reviewController
    val arrangement = arrangementModel
    val export by ExportJournal.status.collectAsStateWithLifecycle()
    val exportBusy by ExportJournal.busyState.collectAsStateWithLifecycle()
    val cameraReady by recordingModel.cameraAvailability.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val recovered by recordingModel.recovered.collectAsStateWithLifecycle()
    val commandPending by recordingModel.commandPending.collectAsStateWithLifecycle()
    val camera = cameraModel
    val playback = this@CameraScreen.playback
    LaunchedEffect(snapshot.state, playback.active) {
        if (snapshot.state != RecordingState.Paused) playback.close()
    }
    LaunchedEffect(snapshot.session, playback.active) {
        if (playback.active) playback.validate(snapshot.session)
    }
    camera.video.canEdit = { com.calvinmazhindu.skitcam.video.VideoProfilePolicy.canChange(recordingModel.snapshots.value,recordingModel.commandPending.value,ExportJournal.busy) && recordingModel.recovered.value && camera.choice.mode==CameraMode.SkitVideo && camera.streaming && !camera.switching && !photoModel.busy }
    camera.video.beforeChange = { recordingModel.cameraReady = false }
    val photos = photoModel
    val matchingExport = export?.sessionId == snapshot.session?.id && export!=null
    val recoveryResult = matchingExport && export?.state in setOf(ExportState.Failed,ExportState.Cancelled,ExportState.Empty) && camera.acknowledgedExport!=export?.sessionId
    val finishing = matchingExport && (export?.active==true || exportBusy || recoveryResult)
    val reviewing = review.selection != null && arrangement.draft == null && trimModel.segment == null && !finishing
    val immersiveVideoUi = reviewing || playback.active || trimModel.segment != null
    // This owner survives Photo/Video control changes and the finishing overlay.
    SideEffect { orientation.update(snapshot,recovered,finishing,commandPending) }
    val settingsAllowed = recovered && !snapshot.unresolved && !commandPending && !photos.busy && !finishing &&
        snapshot.state in setOf(RecordingState.Ready,RecordingState.Completed,RecordingState.Paused)
    LaunchedEffect(camera.loaded, recovered) {
        if(camera.loaded && recovered) {
            // UI collectors can resume in different orders. Read the completed recovery directly.
            val current = recordingModel.snapshots.value
            val result = ExportJournal.status.value
            camera.initialize(current,result!=null && result.sessionId==current.session?.id && !camera.exportHandled(result.sessionId))
        }
    }
    LaunchedEffect(camera.loaded,photos.lastUri) { if(camera.loaded) photos.lastUri?.let { camera.media(it,"image/jpeg",photos.savedAt) } }
    LaunchedEffect(camera.loaded,recovered,export,exportBusy,snapshot.session?.id,snapshot.state,snapshot.unresolved) {
        val result=export
        if(camera.loaded && recovered && SaveReturnPolicy.eligible(snapshot,result,exportBusy)) {
            val completed=result!!
            val video=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val job=ExportJournal.read(this@CameraScreen,completed.sessionId) ?: error("Missing export record")
                    check(job.getString("state")==ExportState.Completed.name && job.getString("uri")==completed.uri)
                    SavedVideo(completed.sessionId,job.getString("id"),completed.uri!!,job.getLong("createdAt"))
                        .also { check(it.valid()) }
                }.getOrNull()
            }
            // IO may outlive a state change. A stale result cannot select media or navigate.
            if(ExportJournal.status.value==completed && recordingModel.snapshots.value.session?.id==completed.sessionId &&
                SaveReturnPolicy.eligible(recordingModel.snapshots.value,completed,ExportJournal.busy)) {
                if(video!=null) camera.published(video,recordingModel.snapshots.value.session!!.lens)
                else camera.message="Saved video details are unavailable. Original recordings are preserved."
            }
        }
    }
    LaunchedEffect(camera.receipt,cameraReady,camera.streaming,exportBusy) {
        if(cameraReady && camera.streaming && !exportBusy && camera.receipt?.video?.sessionId==snapshot.session?.id && !snapshot.openSession) {
            camera.cameraReturned()
            snapshot.session?.id?.let { id ->
                ExportTrace.mark(id,"cameraReady")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ExportTrace.flush(this@CameraScreen,id) }
            }
        }
    }
    LaunchedEffect(camera.savedNotice) {
        val notice=camera.savedNotice ?: return@LaunchedEffect
        val action=snackbars.showSnackbar("Video saved",actionLabel="View",withDismissAction=true,duration=SnackbarDuration.Short)
        camera.dismissSavedNotice()
        if(action==SnackbarResult.ActionPerformed && !recordingModel.snapshots.value.openSession &&
            !recordingModel.commandPending.value && !ExportJournal.busy && !photoModel.busy)
            startActivity(Intent(this@CameraScreen,FinishedPlaybackActivity::class.java).setData(notice.uri.toUri()))
    }
    var seenPhotoSave by remember { mutableIntStateOf(photos.saveCount) }
    LaunchedEffect(photos.saveCount) {
        if(photos.saveCount>seenPhotoSave) { seenPhotoSave=photos.saveCount;snackbars.showSnackbar("Photo saved",duration=SnackbarDuration.Short) }
    }
    val photo = camera.choice.mode == CameraMode.Photo
    val immersiveVideo = !photo && !landscape
    // Match the stock camera's Full mode: the image fills the whole screen,
    // with the header, dock and mode controls drawn over it.
    val fullPhoto = photo && camera.photoAspectRatio==PhotoAspectRatio.Full
    // Portrait 9:16 uses the full screen width and extends behind the bottom dock.
    val wideNineSixteen = photo && !landscape && camera.photoAspectRatio==PhotoAspectRatio.SixteenNine
    fun switchAllowed() = CameraPolicy.maySwitch(recordingModel.recovered.value,recordingModel.snapshots.value,
        finishing || ExportJournal.busy,photos.busy || recordingModel.commandPending.value || camera.video.pending) && camera.streaming
    val maySwitch = switchAllowed() && !commandPending
    val recentEnabled = camera.ready && recovered && photos.recovered && !snapshot.openSession && !snapshot.unresolved && !finishing && !ExportJournal.busy && !photos.busy && !commandPending && !camera.switching && !camera.video.pending
    fun selectCamera(mode: CameraMode = camera.choice.mode, lens: CameraLens? = null) {
        val old = camera.choice
        camera.select(mode,lens,switchAllowed())
        if(camera.choice != old) recordingModel.cameraReady = false
    }
    var quick by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var photoRatioMenu by remember { mutableStateOf(false) }
    val blocked = snapshot.state in setOf(RecordingState.Starting,RecordingState.Finalising,RecordingState.Stopping)
    val editors = arrangement.draft != null || trimModel.segment != null || review.selection != null || finishing
    val adjustments = !blocked && !editors && !photos.busy && camera.streaming
    LaunchedEffect(editors, blocked) { if(editors || blocked) { quick = false; settings = false } }
    LaunchedEffect(photo,photos.busy,quick,settings,editors,blocked) {
        if(!photo || photos.busy || quick || settings || editors || blocked) photoRatioMenu=false
    }
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun takePhoto() {
        if(!grantedPhotoPermission(this) || !CameraPolicy.maySwitch(recordingModel.recovered.value,
                recordingModel.snapshots.value,ExportJournal.busy || finishing,photos.busy || recordingModel.commandPending.value)) return
        if(Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            legacyStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        photos.take(camera.timer) { orientation.displayRotation }
    }
    fun openMedia() {
        if(!CameraPolicy.maySwitch(recordingModel.recovered.value,recordingModel.snapshots.value,
                ExportJournal.busy,photoModel.busy || recordingModel.commandPending.value || camera.video.pending) || camera.switching) return
        startActivity(Intent(this@CameraScreen,com.calvinmazhindu.skitcam.gallery.SavedMediaActivity::class.java))
    }
    val trimming = trimModel.segment != null
    val arranging = arrangement.draft != null || trimming || finishing
    val thumbnails = remember { ThumbnailLoader() }
    var headerHeight by remember { mutableIntStateOf(0) }
    var controlsHeight by remember { mutableIntStateOf(0) }
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        canRetry = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }
    val requestPermission = {
        requested = true
        preferences.edit { putBoolean("requested", true) }
        launcher.launch(Manifest.permission.CAMERA)
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

    Box(Modifier.fillMaxSize().background(SkitCamColors.Ink)) {
        if(!recovered || !camera.ready || !photos.recovered || (!orientation.ready && !camera.streaming)) {
            CameraMessage("Restoring camera…")
        } else if (granted && !(finishing && snapshot.state == RecordingState.Completed)) {
            // Landscape modes use safe height behind the header/mode overlays; portrait
            // video extends beneath its dock. Always fit the complete capture frame.
            CameraPreview(recordingModel,camera,photos,displayRotation=orientation.displayRotation,gesturesEnabled=adjustments && !quick && !settings && !photoRatioMenu, modifier=Modifier.fillMaxSize().padding(
                top=if(fullPhoto || landscape) 0.dp else with(density) { headerHeight.toDp() },
                bottom=if(fullPhoto || wideNineSixteen || landscape || immersiveVideo) 0.dp else with(density) { controlsHeight.toDp() },
                end=if(fullPhoto) 0.dp else if(landscape) dockWidth else 0.dp)
                .then(if(fullPhoto) Modifier
                else if(immersiveVideo) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                else if(landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Start + WindowInsetsSides.Vertical)) else Modifier))
        } else if (!granted) {
            val needsSettings = requested && !canRetry
            CameraMessage(
                title = stringResource(
                    when {
                        needsSettings -> R.string.camera_settings_title
                        requested -> R.string.camera_denied_title
                        else -> R.string.camera_permission_title
                    }
                ),
                message = stringResource(
                    when {
                        needsSettings -> R.string.camera_settings_rationale
                        requested -> R.string.camera_denied
                        else -> R.string.camera_permission_rationale
                    }
                ),
                action = stringResource(
                    when {
                        needsSettings -> R.string.open_settings
                        requested -> R.string.allow_camera
                        else -> R.string.camera_continue
                    }
                ),
                onAction = {
                    if (needsSettings) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:$packageName".toUri()))
                    } else requestPermission()
                }
            )
        }
        if(granted && camera.ready && recovered && !immersiveVideoUi) {
            val lensLabel=if(camera.choice.lens==CameraLens.Rear) stringResource(R.string.rear_camera) else stringResource(R.string.front_camera)
            CameraHeader(
                photo=photo,
                landscape=landscape,
                fullPhoto=fullPhoto,
                dockWidth=dockWidth,
                arranging=arranging,
                streaming=camera.streaming,
                boundLabel=camera.boundInfo?.concise ?: stringResource(R.string.camera_unavailable),
                lensLabel=lensLabel,
                aspectRatio=camera.photoAspectRatio,
                aspectMenuExpanded=photoRatioMenu,
                aspectEnabled=granted && maySwitch && !camera.switching,
                onAspectMenuChange={ photoRatioMenu=it;if(it) quick=false },
                onAspectSelect={ camera.photoAspect(it,switchAllowed()) },
                settingsEnabled=settingsAllowed,
                quickEnabled=adjustments,
                onSettings={ settings=true;quick=false },
                onQuick={ quick=!quick },
                audio=recordingModel.audio.takeIf { review.selection==null },
                modifier=Modifier.onSizeChanged { headerHeight = it.height }
            )
        }
        if (!photo && !arranging && snapshot.state == RecordingState.Paused && snapshot.session != null && headerHeight > 0 && controlsHeight > 0) {
            val top = with(density) { headerHeight.toDp() }
            val bottom = with(density) { controlsHeight.toDp() }
            val pausedModifier = if (immersiveVideoUi) Modifier.fillMaxSize() else Modifier.fillMaxSize()
                .padding(top = top, bottom = if (landscape) 0.dp else bottom,
                    end = if (landscape) dockWidth else 0.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    if (landscape) WindowInsetsSides.Start + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal))
                .padding(8.dp)
            if (playback.active) {
                SkitPlaybackScreen(playback, pausedModifier) { playback.close() }
            } else if (!arranging) {
                PausedEnvironment(snapshot.session!!, landscape, recordingModel, review, thumbnails,
                    onTrim = { trimModel.open(snapshot.session!!, it) }, modifier = pausedModifier,
                    onPlayFromBeginning = { snapshot.session?.let { review.close(); playback.play(it) } },
                    onPlayFrom = { item -> snapshot.session?.let { review.close(); playback.play(it, item.id) } },
                    immersiveReview = reviewing)
            }
        }
        val modeSelector: @Composable () -> Unit = {
            CameraModeToggle(
                selected=camera.choice.mode,
                enabled=maySwitch && !camera.switching && !snapshot.openSession,
                onSelect={ photos.cancelTimer();selectCamera(mode=it) }
            )
        }
        if (!immersiveVideoUi) Column(
            (if (landscape) Modifier.align(Alignment.CenterEnd).width(dockWidth).fillMaxHeight()
            else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                .onSizeChanged { controlsHeight = it.height }
                .then(if(immersiveVideo || fullPhoto || wideNineSixteen) Modifier.blockPreviewInput() else Modifier)
                .then(if (arranging || settings) Modifier.clearAndSetSemantics {} else Modifier)
                .background(if (landscape) Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
                else Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    if (landscape) WindowInsetsSides.End + WindowInsetsSides.Vertical
                    else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            if(granted && camera.ready && recovered && !finishing) {
                if(photo) {
                    PhotoControls(
                        photos=photos,
                        landscape=landscape,
                        canCapture=granted && maySwitch && !camera.switching,
                        onCapture=::takePhoto,
                        leading={ MediaShortcut(camera.latestUri,camera.latestType,recentEnabled,::openMedia) },
                        modeSelector=modeSelector,
                        trailing={ LensSwitch(camera.choice.lens==CameraLens.Front,maySwitch && camera.hasOtherLens) {
                            selectCamera(lens=if(camera.choice.lens==CameraLens.Rear) CameraLens.Front else CameraLens.Rear)
                        } }
                    )
                } else {
                    RecordingControls(this@CameraScreen, recordingModel, orientation, previewRotation=camera.previewDisplayRotation, cameraGranted = granted && !camera.switching,
                showShortcuts = true, verticalShutter = landscape, modeSelector = modeSelector,
                leading = { MediaShortcut(camera.latestUri,camera.latestType,recentEnabled,::openMedia) },
                trailing = { LensSwitch(camera.choice.lens==CameraLens.Front,maySwitch && camera.hasOtherLens) { selectCamera(lens=if(camera.choice.lens==CameraLens.Rear) CameraLens.Front else CameraLens.Rear) } },
                beforeRecording = { camera.dismissSavedNotice();playback.close();trimModel.close();review.close();arrangement.invalidate() },
                onBackground = { playback.close(); review.close() },
                onArrange = {
                    val current = recordingModel.snapshots.value
                    if (current.state == RecordingState.Paused && current.count >= 2 && !current.unresolved) {
                        review.close()
                        arrangement.open(current.session!!)
                    }
                })
                }
                Box(Modifier.fillMaxWidth().height(20.dp),contentAlignment=Alignment.Center) {
                    val inline=camera.message ?: camera.focusResult.takeIf { camera.focus!=null }
                    inline?.let { Text(it,style=MaterialTheme.typography.labelSmall,color=SkitCamColors.TextSecondary,maxLines=1) }
                }
            }
        }
        if(quick && !editors) Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=56.dp)) {
            CameraTools(camera,false,adjustments,snapshot.session?.segments?.lastOrNull()?.media,snapshot.openSession,onMore=if(settingsAllowed) {{quick=false;settings=true}} else null,audio=recordingModel.audio) { quick=false }
        }
        if(settings && settingsAllowed) CameraTools(camera,true,adjustments,snapshot.session?.segments?.lastOrNull()?.media,snapshot.openSession,audio=recordingModel.audio) { settings=false }
        if (arrangement.draft != null && snapshot.state == RecordingState.Paused && snapshot.session != null) {
            ArrangementScreen(snapshot.session!!, arrangement, recordingModel, review, thumbnails)
        }
        if (trimming && snapshot.state == RecordingState.Paused) TrimScreen(trimModel, recordingModel, review)
        if (finishing) ExportScreen(export!!) { camera.acknowledge(export!!.sessionId) }
        SnackbarHost(snackbars,Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(
            end=if(landscape) dockWidth else 0.dp,
            bottom=if(landscape) 56.dp else with(density) { controlsHeight.toDp()+8.dp })) { data ->
            Snackbar(data,containerColor=SkitCamColors.Surface,contentColor=SkitCamColors.TextPrimary,
                actionColor=SkitCamColors.Warm,dismissActionContentColor=SkitCamColors.TextSecondary)
        }
    }
}

@Composable
internal fun CameraMessage(message: String, action: String? = null, title: String? = null, onAction: () -> Unit = {}) {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    SkitCamMessagePanel(
        title = title ?: stringResource(R.string.app_name),
        message = message,
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(
            start = 32.dp, end = if (landscape) (if(LocalDensity.current.fontScale>1.5f) 192.dp else 152.dp) else 32.dp,
            top = if (landscape) 88.dp else 150.dp, bottom = if (landscape) 32.dp else 150.dp),
        primaryLabel = action,
        onPrimary = if (action != null) onAction else null
    )
}

private fun grantedPhotoPermission(context: Context) = ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
