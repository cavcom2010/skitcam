package com.calvinmazhindu.skitcam.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.calvinmazhindu.skitcam.camera.*
import com.calvinmazhindu.skitcam.export.FinishedPlaybackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Browsing never opens the camera or changes recording metadata. */
class SavedMediaActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)
    private var opening by mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if(uri!=null) open(uri, null)
    }
    internal var currentItems: List<SavedMedia> = emptyList(); private set
    private fun permitted() = Build.VERSION.SDK_INT>=29 || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFDAA0A4))) {
                Surface(Modifier.fillMaxSize(),color=Color(0xFF101014),contentColor=Color.White) { Gallery() }
            }
        }
    }
    @Composable private fun Gallery() {
        var entries by remember { mutableStateOf<List<SavedMedia>>(emptyList()) }
        var projectJob by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var filter by rememberSaveable { mutableStateOf(GalleryFilter.All) }
        val owner=LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) revision++ }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(revision) {
            loading=true;error=null
            projectJob=withContext(Dispatchers.IO) { com.calvinmazhindu.skitcam.project.ProjectJobs.latest(this@SavedMediaActivity) }
            if(permitted()) {
                val result=withContext(Dispatchers.IO) { runCatching { SavedMediaStore(this@SavedMediaActivity).read() } }
                entries=result.getOrDefault(emptyList());currentItems=entries
                error=result.exceptionOrNull()?.let { "Saved media could not be listed. Try Refresh or Browse phone." }
            } else { entries=emptyList();currentItems=emptyList() }
            loading=false
        }
        val shown=remember(entries,filter) { SavedMediaPolicy.visible(entries,filter) }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                CameraIconButton(CameraIcon.Back,"Back to camera") { finish() }
                Text("Gallery",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={revision++},enabled=!loading) { Text("Refresh") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                GalleryFilter.entries.forEach { option -> FilterChip(selected=filter==option,onClick={filter=option},label={Text(option.name,maxLines=1)}) }
            }
            if(!loading && error==null && permitted()) Text("${entries.count { it.video }} videos · ${entries.count { !it.video }} photos saved with SkitCam",
                Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription="Loading saved media" })
            if(!permitted()) TextButton(onClick={permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)}) { Text("Allow access to saved media") }
            error?.let { Text(it,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error) }
            if(!loading && shown.isEmpty() && error==null && permitted()) Text("No saved ${if(filter==GalleryFilter.Videos) "videos" else if(filter==GalleryFilter.Photos) "photos" else "media"} found.",Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription="Saved media list" },contentPadding=PaddingValues(8.dp)) {
                items(shown,key={it.uri}) { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min=80.dp).clickable(enabled=!opening) { open(item.uri.toUri(),item.mimeType) }
                        .semantics { contentDescription="Open ${if(item.video) "video" else "photo"} ${item.name}" }
                        .padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        SavedMediaThumbnail(item.uri,item.mimeType,Modifier.size(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name,maxLines=1,overflow=TextOverflow.Ellipsis)
                            val date=if(item.addedAtSeconds in 1..Long.MAX_VALUE/1000) DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(item.addedAtSeconds*1000)) else "Date unavailable"
                            Text("${if(item.video) "Video" else "Photo"} · $date",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            projectJob?.let { id -> TextButton(onClick={startActivity(Intent(this@SavedMediaActivity,com.calvinmazhindu.skitcam.project.ProjectExportActivity::class.java).putExtra("job",id))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Project exports") } }
            TextButton(onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))},enabled=!opening,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Browse phone") }
        }
    }
    private fun open(uri: Uri, knownType: String?) {
        if(opening) return
        opening=true
        lifecycleScope.launch {
            val type=withContext(Dispatchers.IO) { runCatching {
                contentResolver.openInputStream(uri)?.use { check(it.read()>=0) } ?: error("Missing media")
                knownType ?: contentResolver.getType(uri) ?: error("Unknown media type")
            }.getOrNull() }
            try {
                if(type==null) { Toast.makeText(this@SavedMediaActivity,"Media is unavailable. Refresh the gallery.",Toast.LENGTH_SHORT).show();revision++ }
                else if(type.startsWith("video/")) startActivity(Intent(this@SavedMediaActivity,FinishedPlaybackActivity::class.java).setData(uri).putExtra("from_gallery",true))
                else if(type.startsWith("image/")) startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                else Toast.makeText(this@SavedMediaActivity,"Unsupported media type",Toast.LENGTH_SHORT).show()
            } catch(_: android.content.ActivityNotFoundException) { Toast.makeText(this@SavedMediaActivity,"No compatible media viewer is installed",Toast.LENGTH_SHORT).show() }
            finally { opening=false }
        }
    }
}
