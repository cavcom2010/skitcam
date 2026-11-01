package com.calvinmazhindu.skitcam.export

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
                ExportState.Completed -> "Video finished"
                ExportState.Empty -> "No kept segments"
                ExportState.Failed -> "Could not finish video"
                ExportState.Cancelled -> "Finishing cancelled"
                ExportState.Preparing -> "Preparing video"
                ExportState.Exporting -> "Finishing video"
                ExportState.Verifying -> "Checking video"
                ExportState.Publishing -> "Saving to gallery"
            },style=MaterialTheme.typography.headlineMedium)
            if(status.active) CircularProgressIndicator()
            Text(feedback ?: status.message ?: "Finishing your video. Original recordings are preserved.")
            if(status.active && status.state!=ExportState.Publishing) TextButton(colors=ButtonDefaults.textButtonColors(contentColor=Color(0xFFF2D4BC)),onClick={ExportJournal.cancelRequested=true}) { Text("Cancel") }
            if(status.state==ExportState.Completed && status.uri!=null) {
                Button(colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF854C55),contentColor=Color.White),onClick={context.startActivity(Intent(context,FinishedPlaybackActivity::class.java).setData(Uri.parse(status.uri)))}) { Text("Play") }
                Button(colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF854C55),contentColor=Color.White),onClick={
                    val uri=Uri.parse(status.uri)
                    val intent=Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,uri)
                        .apply { clipData=ClipData.newRawUri("SkitCam video",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    context.startActivity(Intent.createChooser(intent,"Share video"))
                }) { Text("Share") }
            }
            if(status.state in setOf(ExportState.Failed,ExportState.Cancelled)) Button(colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF854C55),contentColor=Color.White),onClick={retry()}) { Text("Retry") }
            if(!status.active) Button(colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF854C55),contentColor=Color.White),onClick=onNewRecording) { Text("New Recording") }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FinishedPlaybackActivity : ComponentActivity() {
    private var player: ExoPlayer?=null
    internal val currentPlayer get()=player
    private lateinit var view: PlayerView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view=PlayerView(this)
        setContentView(view)
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
            it.setMediaItem(MediaItem.fromUri(uri));it.prepare()
        }
    }
    override fun onStop() { view.player=null;player?.release();player=null;super.onStop() }
}
