package com.calvinmazhindu.skitcam.export

import android.content.Context
import android.util.AtomicFile
import com.calvinmazhindu.skitcam.recording.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One atomic sidecar per session. Recording metadata and source MP4s are never rewritten here. */
object ExportJournal {
    val status = MutableStateFlow<ExportStatus?>(null)
    @Volatile var busy = false
    @Volatile var cancelRequested = false
    fun directory(context: Context, id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(context.filesDir,"sessions/$id/export").also { check(it.isDirectory || it.mkdirs()) }
    }
    @Synchronized fun read(context: Context, id: String): JSONObject? {
        val atomic = AtomicFile(File(directory(context,id),"job.json"))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path+".bak").exists()) return null
        return atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }.also {
            require(it.getInt("version")==1 && it.getString("sessionId")==id) { "Invalid export journal." }
            UUID.fromString(it.getString("id"))
        }
    }
    @Synchronized fun write(context: Context, id: String, job: JSONObject) {
        val atomic = AtomicFile(File(directory(context,id),"job.json"))
        val output = atomic.startWrite()
        try { output.write(job.toString(2).toByteArray());atomic.finishWrite(output) }
        catch(e: Exception) { atomic.failWrite(output);throw e }
        status.value = ExportStatus(id,ExportState.valueOf(job.getString("state")),job.optString("message").ifBlank { null },job.optString("uri").ifBlank { null })
    }
    @Synchronized fun request(context: Context, session: Session) {
        if (read(context,session.id)!=null) return
        cancelRequested=false
        val job = JSONObject().put("version",1).put("id",UUID.randomUUID().toString()).put("sessionId",session.id)
            .put("state",ExportState.Preparing.name).put("ready",false).put("createdAt",System.currentTimeMillis())
        write(context,session.id,job)
    }
    @Synchronized fun finalised(context: Context, session: Session) {
        val job=read(context,session.id) ?: return
        if (job.optBoolean("ready")) return
        job.put("session",SessionCodec.encode(session)).put("ready",true)
        write(context,session.id,job)
    }
    @Synchronized fun update(context: Context, id: String, state: ExportState, message: String? = null, uri: String? = null) {
        val job=read(context,id) ?: error("Export journal unavailable")
        check(exportTransitionAllowed(ExportState.valueOf(job.getString("state")),state)) { "Invalid export transition." }
        job.put("state",state.name).put("message",message ?: "")
        if(uri!=null) job.put("uri",uri)
        write(context,id,job)
    }
    fun restore(context: Context, session: Session) {
        val job=read(context,session.id) ?: return
        val state=ExportState.valueOf(job.getString("state"))
        if (state in setOf(ExportState.Preparing,ExportState.Exporting,ExportState.Verifying,ExportState.Publishing) && !busy) {
            update(context,session.id,ExportState.Failed,"Finishing was interrupted. Retry starts safely from a saved checkpoint.")
        } else status.value=ExportStatus(session.id,state,job.optString("message").ifBlank { null },job.optString("uri").ifBlank { null })
    }
}
