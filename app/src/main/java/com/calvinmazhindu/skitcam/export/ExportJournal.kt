package com.calvinmazhindu.skitcam.export

import android.content.Context
import com.calvinmazhindu.skitcam.recording.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One atomic sidecar per session. Recording metadata and source MP4s are never rewritten here. */
object ExportJournal {
    val status = MutableStateFlow<ExportStatus?>(null)
    private val executing = MutableStateFlow(false)
    val busyState = executing.asStateFlow()
    var busy: Boolean
        get() = executing.value
        set(value) { executing.value=value }
    @Volatile var cancelRequested = false
    @Synchronized fun cancel(id: String) {
        if (status.value?.let { it.sessionId == id && it.active && it.state != ExportState.Publishing } == true)
            cancelRequested = true
    }
    @Synchronized fun clearStatus(id: String) {
        if (status.value?.sessionId == id) status.value = null
    }

    @Synchronized fun retire(context: Context, id: String) {
        val job = read(context, id) ?: return
        check(!busy && SessionCleanup.canRetire(job)) {
            "Retry export to resolve this skit's saved-video status before editing."
        }
        val directory = File(context.filesDir, "sessions/$id/export")
        java.nio.file.Files.move(directory.toPath(),
            File(directory.parentFile, "previous-export-${UUID.randomUUID()}").toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        syncRecordingDirectory(checkNotNull(directory.parentFile))
        clearStatus(id)
    }
    fun directory(context: Context, id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val path = File(context.filesDir, "sessions/$id/export")
        if (!path.isDirectory) {
            check(path.mkdirs() || path.isDirectory)
            syncRecordingDirectory(checkNotNull(path.parentFile))
        }
        return path
    }
    @Synchronized fun read(context: Context, id: String): JSONObject? {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val base = File(context.filesDir, "sessions/$id/export/job.json")
        val backup = File(base.path + ".bak")
        fun decode(path: File): JSONObject = JSONObject(path.readText()).also {
            require(it.getInt("version") in 1..2 && it.getString("sessionId")==id) { "Invalid export journal." }
            UUID.fromString(it.getString("id"))
        }
        if (base.exists()) {
            try { return decode(base) } catch (e: Exception) { if (!backup.exists()) throw e }
        }
        return if (backup.exists()) decode(backup) else null
    }
    @Synchronized fun write(context: Context, id: String, job: JSONObject) {
        commitRecordingMetadata(File(directory(context, id), "job.json"), job.toString(2))
        status.value = statusFrom(id, job)
    }
    @Synchronized fun request(context: Context, session: Session) {
        if (read(context,session.id)!=null) return
        cancelRequested=false
        val job = JSONObject().put("version",if(session.captureProfile==null) 1 else 2).put("id",UUID.randomUUID().toString()).put("sessionId",session.id)
            .put("publicationStarted", false)
            .put("state",ExportState.Preparing.name).put("ready",false).put("createdAt",System.currentTimeMillis()).put("pathPolicy",1)
        write(context,session.id,job)
        ExportTrace.mark(session.id,"requestCommitted")
    }
    @Synchronized fun finalised(context: Context, session: Session) {
        val job=read(context,session.id) ?: return
        if (job.optBoolean("ready")) return
        job.put("session",SessionCodec.encode(session)).put("exportProfile",session.captureProfile?.let(com.calvinmazhindu.skitcam.video.VideoJson::profile) ?: JSONObject.NULL).put("ready",true)
        write(context,session.id,job)
        ExportTrace.mark(session.id,"snapshotReady")
    }
    @Synchronized fun recordingFailed(context: Context, id: String) {
        val job = read(context, id) ?: return
        if (job.optBoolean("ready")) return
        job.put("recordingFailed", true)
        write(context, id, job)
    }
    @Synchronized fun update(context: Context, id: String, state: ExportState, message: String? = null, uri: String? = null) {
        val job=read(context,id) ?: error("Export journal unavailable")
        check(exportTransitionAllowed(ExportState.valueOf(job.getString("state")),state)) { "Invalid export transition." }
        if (state == ExportState.Publishing && !job.optBoolean("publicationStarted") &&
            job.has("publicationStarted") && job.optString("uri").isBlank()) job.put("publicationProtocolV2", true)
        job.put("state",state.name).put("message",message ?: "")
        if (state == ExportState.Publishing) job.put("publicationStarted", true)
        if(uri!=null) job.put("uri",uri)
        write(context,id,job)
    }
    @Synchronized fun beginPublication(context: Context, id: String) {
        checkCancelled()
        update(context, id, ExportState.Publishing)
    }
    @Synchronized fun markInsertAttempted(context: Context, id: String) {
        val job = checkNotNull(read(context, id))
        check(job.getString("state") == ExportState.Publishing.name && job.optBoolean("publicationProtocolV2") &&
            !job.optBoolean("publicationInsertAttempted") && job.optString("uri").isBlank())
        job.put("publicationInsertAttempted", true)
        write(context, id, job)
    }
    /** Internal, bounded fallback only; user retry/publication transitions remain unchanged. */
    @Synchronized fun fallback(context: Context, id: String, reason: String) {
        val job=read(context,id) ?: error("Missing export")
        check(job.getString("state") in listOf(ExportState.Exporting.name,ExportState.Verifying.name))
        check(!job.optBoolean("renderFallbackUsed") && job.optString("selectedPath")!=ExportPath.RENDER.name)
        check(job.optString("verifiedSha").isBlank()) { "A verified checkpoint cannot be replaced" }
        job.put("renderFallbackUsed",true).put("fallbackReason",reason).put("selectedPath",ExportPath.RENDER.name)
            .put("state",ExportState.Exporting.name).put("message","Retrying with the standard renderer")
        job.remove("candidate")
        job.remove("candidateReverified")
        write(context,id,job)
    }
    @Synchronized fun restore(context: Context, session: Session) {
        val job=read(context,session.id) ?: return
        val state=ExportState.valueOf(job.getString("state"))
        if (state in setOf(ExportState.Preparing,ExportState.Exporting,ExportState.Verifying,ExportState.Publishing) && !busy) {
            val message=when {
                state==ExportState.Verifying && job.optBoolean("candidateReverified") ->
                    ExportCandidate.RETRY_REBUILD_MESSAGE
                state!=ExportState.Publishing && ExportCandidate.from(job)!=null && !job.optBoolean("candidateReverified") &&
                    job.optString("lastVerifyFailure") in setOf("",ExportCandidate.FAILURE_DECODER) ->
                    ExportCandidate.RETRY_RECHECK_MESSAGE
                else -> "Finishing was interrupted. Retry to continue. Original clips are preserved."
            }
            update(context,session.id,ExportState.Failed,message)
        } else status.value = statusFrom(session.id, job)
    }

    private fun statusFrom(id: String, job: JSONObject) = ExportStatus(
        id, ExportState.valueOf(job.getString("state")), job.optString("message").ifBlank { null },
        job.optString("uri").ifBlank { null }, SessionCleanup.mayHavePublished(job))
}
