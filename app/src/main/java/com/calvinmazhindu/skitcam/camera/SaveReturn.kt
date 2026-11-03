package com.calvinmazhindu.skitcam.camera

import com.calvinmazhindu.skitcam.recording.RecordingSnapshot
import com.calvinmazhindu.skitcam.recording.RecordingState
import com.calvinmazhindu.skitcam.export.ExportState
import com.calvinmazhindu.skitcam.export.ExportStatus
import org.json.JSONObject

/** UI receipt only. Never changes a recording session or an export job. */
data class SavedVideo(val sessionId: String, val jobId: String, val uri: String, val createdAtMs: Long) {
    fun valid() = sessionId.isNotBlank() && jobId.isNotBlank() && uri.startsWith("content://") && createdAtMs>=0
}
data class SaveReceipt(val video: SavedVideo, val handled: Boolean = false) {
    fun encode(): String = JSONObject().put("version",1).put("sessionId",video.sessionId).put("jobId",video.jobId)
        .put("uri",video.uri).put("createdAtMs",video.createdAtMs).put("handled",handled).toString()
    companion object {
        fun decode(text: String): SaveReceipt {
            val j=JSONObject(text);require(j.getInt("version")==1)
            return SaveReceipt(SavedVideo(j.getString("sessionId"),j.getString("jobId"),j.getString("uri"),j.getLong("createdAtMs")),j.getBoolean("handled"))
                .also { require(it.video.valid()) }
        }
    }
}
object SaveReturnPolicy {
    fun eligible(snapshot: RecordingSnapshot, status: ExportStatus?, busy: Boolean): Boolean =
        !busy && !snapshot.openSession && !snapshot.unresolved && snapshot.state==RecordingState.Completed &&
            status?.state==ExportState.Completed && status.sessionId==snapshot.session?.id &&
            status.uri?.startsWith("content://")==true
    fun handled(sessionId: String, receipt: SaveReceipt?, legacyAcknowledgement: String?): Boolean =
        (receipt?.video?.sessionId==sessionId && receipt.handled) || legacyAcknowledgement==sessionId
    fun accepts(video: SavedVideo, receipt: SaveReceipt?): Boolean = video.valid() && receipt?.video?.jobId!=video.jobId
}

data class BoundCameraInfo(val width: Int, val height: Int, val photo: Boolean, val fpsTarget: Int?) {
    val concise: String get() {
        if (photo) return "Photo"
        val shortSide = minOf(width, height)
        val quality = when {
            shortSide <= 0 -> "Camera"
            maxOf(width, height) == 3840 && shortSide == 2160 -> "4K"
            else -> "${shortSide}p"
        }
        return "$quality · ${fpsTarget?.let { "$it target" } ?: "Auto"}"
    }
}
