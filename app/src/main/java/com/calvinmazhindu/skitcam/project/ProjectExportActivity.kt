package com.calvinmazhindu.skitcam.project

import android.app.Activity
import android.content.*
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calvinmazhindu.skitcam.camera.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

private data class DocumentGrant(val uri: Uri,val flags: Int)
private class CreateProjectDocument : ActivityResultContract<String,DocumentGrant?>() {
    override fun createIntent(context: Context,input: String)=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE);type="application/zip"
        putExtra(Intent.EXTRA_TITLE,input)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    override fun parseResult(resultCode: Int,intent: Intent?) = if(resultCode==Activity.RESULT_OK && intent?.data!=null) DocumentGrant(intent.data!!,intent.flags) else null
}

/** Optional archive flow. Never changes an old recording/session or the MP4 finishing state. */
class ProjectExportActivity : ComponentActivity() {
    private var jobId by mutableStateOf<String?>(null)
    private var raw by mutableStateOf<String?>(null)
    private var job by mutableStateOf<JSONObject?>(null)
    private var originals by mutableStateOf(true)
    private var discarded by mutableStateOf(false)
    private var opening by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var pickerJob: String?=null
    private val destination=registerForActivityResult(CreateProjectDocument()) { grant ->
        val id=pickerJob;pickerJob=null
        if(id!=null) lifecycleScope.launch {
            opening=true
            try {
                if(grant==null) withContext(Dispatchers.IO) { ProjectJobs.update(this@ProjectExportActivity,id,ProjectState.Cancelled,"Destination selection cancelled. No archive was written.") }
                else {
                    withContext(Dispatchers.IO) {
                        val j=ProjectJobs.read(this@ProjectExportActivity,id)
                        require(ProjectJobs.state(j)==ProjectState.WaitingDestination && j.optString("uri").isBlank()) { "This destination result has already been handled." }
                        j.put("uri",grant.uri.toString()).put("owned",true);ProjectJobs.write(this@ProjectExportActivity,j)
                    }
                    persist(grant.uri,grant.flags,true)
                    ProjectExportService.launch(this@ProjectExportActivity,id)
                }
            } catch(e: Exception) { error=e.message;withContext(Dispatchers.IO) { runCatching { ProjectJobs.update(this@ProjectExportActivity,id,ProjectState.Failed,e.message ?: "Destination unavailable") } } }
            finally { opening=false;reload() }
        }
    }
    private val locate=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri=result.data?.data
        val id=jobId
        if(result.resultCode==Activity.RESULT_OK && uri!=null && id!=null) lifecycleScope.launch {
            opening=true
            try {
                persist(uri,result.data!!.flags,false)
                withContext(Dispatchers.IO) {
                    val j=ProjectJobs.read(this@ProjectExportActivity,id)
                    require(j.has("archiveSha"))
                    require(!ProjectDestination(this@ProjectExportActivity).name(uri).endsWith(".partial.zip")) { "Choose the finished archive, not an incomplete document." }
                    // Relocation must not replace a completed receipt with an unrelated ZIP.
                    // Interrupted jobs also verify before their normal completion transition.
                    val input=contentResolver.openInputStream(uri) ?: error("The selected archive cannot be read.")
                    ArchiveIO.verifyHash(input,ProjectDestination(this@ProjectExportActivity).receipt(j))
                    j.put("uri",uri.toString()).put("owned",false).put("located",true)
                    ProjectJobs.write(this@ProjectExportActivity,j)
                }
                error=null
                ProjectExportService.launch(this@ProjectExportActivity,id)
            } catch(e: Exception) { error=e.message }
            finally { opening=false;reload() }
        }
    }
    private fun persist(uri: Uri,flags: Int,write: Boolean) {
        val required=Intent.FLAG_GRANT_READ_URI_PERMISSION or if(write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        require(flags and required==required && flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0) { "This provider cannot retain the access needed for verification/recovery. Choose local storage." }
        contentResolver.takePersistableUriPermission(uri,required)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobId=savedInstanceState?.getString("job") ?: intent.getStringExtra("job")
        pickerJob=savedInstanceState?.getString("pickerJob")
        originals=savedInstanceState?.getBoolean("originals") ?: true
        discarded=savedInstanceState?.getBoolean("discarded") ?: false
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFDAA0A4))) { Screen() } }
        lifecycleScope.launch {
            try {
                if(jobId==null) raw=withContext(Dispatchers.IO) { intent.data?.let { CompletedProjectCatalog(this@ProjectExportActivity).find(it) } }
                    ?: error("The original SkitCam project is unavailable for this video.")
                else {
                    val j=withContext(Dispatchers.IO) { ProjectJobs.recover(this@ProjectExportActivity,jobId!!) }
                    raw=j.getString("snapshot");job=j
                    if(savedInstanceState==null) { originals=j.getBoolean("originals");discarded=j.getBoolean("discarded") }
                }
            } catch(e: Exception) { error=e.message }
        }
    }
    private suspend fun reload() {
        val id=jobId ?: return
        runCatching { withContext(Dispatchers.IO) { ProjectJobs.recover(this@ProjectExportActivity,id) } }
            .onSuccess { job=it }.onFailure { error="Project job unavailable. Originals are preserved." }
    }
    private fun chooseDestination() {
        if(opening) return
        opening=true;error=null
        lifecycleScope.launch {
            try {
                val id=withContext(Dispatchers.IO) { ProjectJobs.create(this@ProjectExportActivity,checkNotNull(raw),ProjectOptions(originals,discarded)) }
                jobId=id;job=withContext(Dispatchers.IO) { ProjectJobs.read(this@ProjectExportActivity,id) };pickerJob=id
                destination.launch(job!!.getString("filename").removeSuffix(".zip")+".partial.zip")
            } catch(e: Exception) { error=e.message }
            finally { opening=false }
        }
    }
    @Composable private fun Screen() {
        val revision by ProjectJobs.revision.collectAsStateWithLifecycle()
        val progress by ProjectJobs.progress.collectAsStateWithLifecycle()
        LaunchedEffect(revision,jobId) { reload() }
        var estimate by remember { mutableStateOf<Long?>(null) }
        var sourceError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(raw,originals,discarded,jobId) {
            if(raw!=null && jobId==null) {
                estimate=null;sourceError=null
                val result=withContext(Dispatchers.IO) { runCatching {
                    val snapshot=ProjectSnapshot.fromJournal(raw!!)
                    var size=File(filesDir,"sessions/${snapshot.session.id}/export/output.mp4").takeIf { it.isFile }?.length() ?: 0L
                    snapshot.selected(ProjectOptions(originals,discarded)).forEach { segment -> size=Math.addExact(size,CompletedProjectCatalog(this@ProjectExportActivity).source(snapshot,segment.path).length()) }
                    size.takeIf { it>0 }
                } }
                estimate=result.getOrNull();sourceError=result.exceptionOrNull()?.message
            }
        }
        Surface(Modifier.fillMaxSize(),color=Color(0xFF101014),contentColor=Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    CameraIconButton(CameraIcon.Back,"Back from project export") { finish() }
                    Text("Portable project",style=MaterialTheme.typography.titleLarge)
                }
                Text("A ZIP for your computer or archive. No account is required.")
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                val current=job
                if(jobId==null) {
                    Text("Finished video is included.")
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=originals,onCheckedChange={ originals=it;if(!it) discarded=false },enabled=!opening,modifier=Modifier.semantics { contentDescription="Include original segments" })
                        Text("Include original segments")
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=discarded,onCheckedChange={ discarded=it },enabled=originals && !opening,modifier=Modifier.semantics { contentDescription="Include discarded takes" })
                        Text("Include discarded takes")
                    }
                    Text("Originals contain full takes, including trimmed-out footage. Discarded takes are private unless you choose to include them.",style=MaterialTheme.typography.bodySmall)
                    Text("Edit metadata is included. Desktop editors can use the MP4 files; this ZIP is not a native editing timeline.",style=MaterialTheme.typography.bodySmall)
                    estimate?.let { Text("Media size: approximately ${android.text.format.Formatter.formatFileSize(this@ProjectExportActivity,it)} plus metadata") }
                    sourceError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    Button(onClick={chooseDestination()},enabled=raw!=null && !opening && pickerJob==null && sourceError==null) { Text("Choose destination") }
                    Text("Choose a local folder to keep the archive offline. A provider you select may sync files using its own service.",style=MaterialTheme.typography.bodySmall)
                    Text("The incomplete document is named .partial.zip until it has been verified.",style=MaterialTheme.typography.bodySmall)
                } else if(current!=null) {
                    val state=ProjectJobs.state(current)
                    Text(when(state) {
                        ProjectState.WaitingDestination -> "Choose a destination"
                        ProjectState.Preparing -> "Checking project files"
                        ProjectState.Writing -> "Writing project"
                        ProjectState.Verifying -> "Verifying archive"
                        ProjectState.Finalising -> "Finalising archive"
                        ProjectState.Completed -> "Project exported and verified"
                        ProjectState.Cancelled -> "Project export cancelled"
                        ProjectState.Failed -> "Project export failed"
                        ProjectState.Interrupted -> "Project export interrupted"
                    },style=MaterialTheme.typography.titleMedium)
                    Text(current.optString("message"))
                    val p=progress?.takeIf { it.id==jobId }
                    if(state.active) {
                        p?.let { Text(it.phase);Text("${android.text.format.Formatter.formatFileSize(this@ProjectExportActivity,it.bytes)} processed") }
                        if(p?.total!=null && p.total>0) LinearProgressIndicator(progress={ (p.bytes.toDouble()/p.total).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(state!=ProjectState.Finalising) TextButton(onClick={ jobId?.let { ProjectExportService.cancel(this@ProjectExportActivity,it) } }) { Text("Cancel project export") }
                        Text("You can return to the camera. Archiving waits while recording or finishing a video.",style=MaterialTheme.typography.bodySmall)
                    } else if(state==ProjectState.Completed) {
                        Text(current.getString("filename"),style=MaterialTheme.typography.bodySmall)
                        Button(onClick={
                            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(current.getString("uri").toUri(),"application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch(_: ActivityNotFoundException) { Toast.makeText(this@ProjectExportActivity,"No ZIP viewer installed. Open the archive on a computer.",Toast.LENGTH_LONG).show() }
                            catch(_: SecurityException) { error="Archive access changed. Locate it with Android's file picker." }
                        }) { Text("Open archive") }
                        TextButton(onClick={locateArchive()},enabled=!opening) { Text("Locate saved archive") }
                        TextButton(onClick={ jobId=null;job=null;error=null }) { Text("Export another copy") }
                    } else {
                        if(current.optString("uri").isNotBlank() && state!=ProjectState.Cancelled) Button(onClick={
                            if(!opening) try { ProjectExportService.launch(this@ProjectExportActivity,jobId!!) } catch(e: Exception) { error=e.message }
                        },enabled=!opening && ProjectJobs.running.get()==null) { Text("Retry existing destination") }
                        if(current.has("archiveSha")) TextButton(onClick={
                            locateArchive()
                        }) { Text("Locate saved archive") }
                        TextButton(onClick={ jobId=null;job=null;error=null }) { Text("Choose another destination") }
                        Text("An interrupted final rename may already have saved the archive. Check or locate it before making another copy.",style=MaterialTheme.typography.bodySmall)
                    }
                } else CircularProgressIndicator()
                TextButton(onClick={finish()}) { Text("Back") }
            }
        }
    }
    private fun locateArchive() {
        locate.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("job",jobId);outState.putString("pickerJob",pickerJob)
        outState.putBoolean("originals",originals);outState.putBoolean("discarded",discarded)
        super.onSaveInstanceState(outState)
    }
}
