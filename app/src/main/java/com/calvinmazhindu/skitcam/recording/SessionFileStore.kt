package com.calvinmazhindu.skitcam.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Called only on the engine worker. AtomicFile protects the last committed metadata. */
class SessionFileStore(private val root: File) : SessionStore {
    override val rootPath: String get() = root.absolutePath
    override fun freeBytes(): Long = root.let {
        if (!it.exists()) check(it.mkdirs()) { "Could not create session storage." }
        it.usableSpace
    }
    override fun save(session: Session) {
        val directory = File(root, session.id)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create session directory." }
        val json = JSONObject().put("version", 1).put("id", session.id)
            .put("createdAtMs", session.createdAtMs).put("orientationDegrees", session.orientationDegrees)
            .put("state", session.state.name).put("error", session.error ?: JSONObject.NULL)
            .put("segments", JSONArray().apply {
                session.segments.forEach { segment -> put(JSONObject()
                    .put("order", segment.order).put("path", segment.path)
                    .put("durationMs", segment.durationMs).put("orientationDegrees", segment.orientationDegrees)
                    .put("videoRotationDegrees", segment.videoRotationDegrees)) }
            }).put("pending", session.pending?.let {
                JSONObject().put("id", it.id).put("path", it.path).put("order", it.order)
            } ?: JSONObject.NULL)
        val atomic = AtomicFile(File(directory, "session.json"))
        val output = atomic.startWrite()
        try {
            output.write(json.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (exception: Exception) {
            atomic.failWrite(output)
            throw exception
        }
    }
    override fun loadLatest(): Session? {
        val metadata = root.listFiles().orEmpty().filter { it.isDirectory }
            .map { File(it, "session.json") }.filter { it.exists() || File(it.path + ".bak").exists() }
            .maxByOrNull { maxOf(it.lastModified(), File(it.path + ".bak").lastModified()) } ?: return null
        val json = AtomicFile(metadata).openRead().bufferedReader().use { JSONObject(it.readText()) }
        check(json.getInt("version") == 1) { "Unknown session metadata version." }
        val segments = json.getJSONArray("segments")
        return Session(json.getString("id"), json.getLong("createdAtMs"),
            json.getInt("orientationDegrees"), RecordingState.valueOf(json.getString("state")),
            List(segments.length()) { index -> segments.getJSONObject(index).let {
                Segment(it.getInt("order"), it.getString("path"), it.getLong("durationMs"),
                    it.getInt("orientationDegrees"), it.getInt("videoRotationDegrees"))
            } },
            if (json.isNull("pending")) null else json.getJSONObject("pending").let {
                PendingSegment(it.getString("id"), it.getString("path"), it.getInt("order"))
            }, if (json.isNull("error")) null else json.getString("error"))
    }
    override fun validate(path: String): Int {
        val file = File(path)
        check(file.isFile && file.length() > 0) { "Recorded file is empty or missing." }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val mimes = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            check(mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") }) {
                "Recorded MP4 must contain both video and audio tracks."
            }
            mimes.forEachIndexed { index, mime ->
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    check(extractor.sampleTime >= 0) { "Recorded $mime track has no samples." }
                    extractor.unselectTrack(index)
                }
            }
        } finally { extractor.release() }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(path)
            check((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) {
                "Recorded MP4 has no playable duration."
            }
            return metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally { metadata.release() }
    }
}
