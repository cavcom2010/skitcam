package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.trim.SourceFacts
import com.calvinmazhindu.skitcam.trim.TrimRange
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
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
        val json = SessionCodec.encode(session)
        val atomic = AtomicFile(File(directory, "session.json"))
        val output = atomic.startWrite()
        try {
            output.write(json.toByteArray(Charsets.UTF_8))
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
        return readMetadata(metadata)
    }

    /**
     * Read the committed base before a legacy backup. AtomicFile.openRead() can rename a
     * stale .bak over a newer valid base, so recovery must not use it for selection.
     * A base that exists but cannot decode is still a hard failure unless its backup is valid.
     */
    private fun readMetadata(metadata: File): Session {
        var baseFailure: Exception? = null
        if (metadata.exists()) {
            try {
                return metadata.bufferedReader().use { SessionCodec.decode(it.readText()) }
            } catch (exception: Exception) {
                baseFailure = exception
            }
        }
        val backup = File(metadata.path + ".bak")
        if (backup.exists()) {
            return try {
                backup.bufferedReader().use { SessionCodec.decode(it.readText()) }
            } catch (exception: Exception) {
                throw baseFailure ?: exception
            }
        }
        throw baseFailure ?: IllegalStateException("Session metadata is missing.")
    }
    override fun sourceFacts(path: String): SourceFacts {
        val file = File(path)
        check(file.isFile && file.canRead() && file.length() > 0) { "Source is missing or unreadable." }
        val length = file.length(); val modified = file.lastModified()
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Source duration unavailable.")
        } finally { retriever.release() }
        check(duration in 1..TrimRange.MAX_MS) { "Source duration is invalid." }
        check(length == file.length() && modified == file.lastModified()) { "Source changed." }
        return SourceFacts(duration, length, modified)
    }
    override fun mediaFacts(path: String) = com.calvinmazhindu.skitcam.video.VideoInspector.inspect(path)
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
