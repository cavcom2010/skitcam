package com.calvinmazhindu.skitcam.export

import androidx.core.net.toUri
import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun ExportScreen(status: ExportStatus,onNewRecording: () -> Unit) {
    val context=LocalContext.current
    var feedback by remember { mutableStateOf<String?>(null) }
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(status.sessionId) {
        if(Build.VERSION.SDK_INT>=33 && !notificationAsked && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationAsked=true;notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        try { ExportService.launch(context,status.sessionId,retry=true) } catch(e: Exception) { feedback=e.message }
    }
    fun retry() {
        if(Build.VERSION.SDK_INT<=28) permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE))
        else try { ExportService.launch(context,status.sessionId,retry=true) } catch(e: Exception) { feedback=e.message }
    }
    Surface(Modifier.fillMaxSize(),color=Color(0xFF101014),contentColor=Color.White) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(when(status.state) {
                ExportState.Completed -> "Opening camera"
                ExportState.Empty -> "No kept segments"
                ExportState.Failed -> "Could not finish video"
                ExportState.Cancelled -> "Finishing cancelled"
                ExportState.Preparing -> "Preparing video"
                ExportState.Exporting -> "Finishing video"
                ExportState.Verifying -> "Checking video"
                ExportState.Publishing -> "Saving to gallery"
            },style=MaterialTheme.typography.titleLarge)
            if(status.active) CircularProgressIndicator()
            Text(feedback ?: status.message ?: "Your original recordings are preserved.",style=MaterialTheme.typography.bodyMedium)
            if(status.active && status.state!=ExportState.Publishing) TextButton(colors=ButtonDefaults.textButtonColors(contentColor=Color(0xFFF2D4BC)),onClick={ExportJournal.cancelRequested=true}) { Text("Cancel") }
            if(status.state in setOf(ExportState.Failed,ExportState.Cancelled)) Button(colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF854C55),contentColor=Color.White),onClick={retry()}) { Text("Retry") }
            if(!status.active && status.state!=ExportState.Completed) Button(colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF854C55),contentColor=Color.White),onClick=onNewRecording) { Text("New Recording") }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FinishedPlaybackActivity : ComponentActivity() {
    private var player: ExoPlayer?=null
    internal val currentPlayer get()=player
    private lateinit var view: PlayerView
    private var resumeAt=0L
    private var resumePlaying=false
    private var projectAvailable by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view=PlayerView(this)
        resumeAt=savedInstanceState?.getLong("position") ?: 0L
        resumePlaying=savedInstanceState?.getBoolean("playing") ?: false
        lifecycleScope.launch {
            projectAvailable=withContext(Dispatchers.IO) { intent.data?.let { com.calvinmazhindu.skitcam.project.CompletedProjectCatalog(this@FinishedPlaybackActivity).find(it) }!=null }
        }
        setContent {
            MaterialTheme(colorScheme=darkColorScheme()) {
                Surface(color=Color.Black,contentColor=Color.White) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            com.calvinmazhindu.skitcam.camera.CameraIconButton(com.calvinmazhindu.skitcam.camera.CameraIcon.Back,if(intent.getBooleanExtra("from_gallery",false)) "Back to gallery" else "Back to camera") { finish() }
                            Text("Saved video",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            if(projectAvailable) {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    com.calvinmazhindu.skitcam.camera.CameraIconButton(com.calvinmazhindu.skitcam.camera.CameraIcon.More,"Video options") { menu=true }
                                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                        DropdownMenuItem(text={Text("Export Project")},onClick={
                                            menu=false;startActivity(Intent(this@FinishedPlaybackActivity,com.calvinmazhindu.skitcam.project.ProjectExportActivity::class.java).setData(intent.data))
                                        })
                                    }
                                }
                            }
                            com.calvinmazhindu.skitcam.camera.CameraIconButton(com.calvinmazhindu.skitcam.camera.CameraIcon.Share,"Share video") { share() }
                        }
                        AndroidView(factory={view},modifier=Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        val uri=intent.data ?: return finish()
        player=ExoPlayer.Builder(this).build().also {
            view.player=it
            it.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            it.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("SkitCamPlayback", "Finished video playback failed", error)
                    android.widget.Toast.makeText(this@FinishedPlaybackActivity,"Finished video is unavailable. Original recordings are preserved.",android.widget.Toast.LENGTH_LONG).show()
                    finish()
                }
            })
            it.setMediaItem(MediaItem.fromUri(uri));it.seekTo(resumeAt);it.playWhenReady=resumePlaying;it.prepare()
        }
    }
    private fun share() {
        val uri=intent.data ?: return
        try {
            val send=Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,uri).apply {
                clipData=ClipData.newRawUri("SkitCam video",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send,"Share video"))
        } catch(_: ActivityNotFoundException) { android.widget.Toast.makeText(this,"No sharing app available",android.widget.Toast.LENGTH_SHORT).show() }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position",player?.currentPosition ?: resumeAt);outState.putBoolean("playing",player?.playWhenReady ?: resumePlaying);super.onSaveInstanceState(outState) }
    override fun onStop() { resumeAt=player?.currentPosition ?: resumeAt;resumePlaying=player?.playWhenReady ?: resumePlaying;view.player=null;player?.release();player=null;super.onStop() }
}
