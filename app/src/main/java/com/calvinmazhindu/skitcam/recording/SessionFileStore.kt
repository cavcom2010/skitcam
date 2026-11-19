package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.trim.SourceFacts
import com.calvinmazhindu.skitcam.trim.TrimRange
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Called only on the engine worker. An atomic commit protects the last saved metadata. */
class SessionFileStore(private val root: File) : SessionStore {
    internal val cleanup = SessionCleanup(root)
    private val sessionName = Regex("[a-zA-Z0-9-]+")
    override fun abandon(sessionId: String) = cleanup.abandon(sessionId)
    override val rootPath: String get() = root.absolutePath
    override fun freeBytes(): Long = root.let {
        if (!it.exists()) check(it.mkdirs()) { "Could not create session storage." }
        it.usableSpace
    }
    override fun save(session: Session) {
        val directory = File(root, session.id)
        check(sessionName.matches(session.id)) { "Invalid session identity." }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create session directory." }
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "Invalid session directory." }
        syncRecordingDirectory(root)
        commitRecordingMetadata(File(directory, "session.json"), SessionCodec.encode(session))
    }
    override fun saveRecovered(original: Session, updated: Session) {
        if (updated.unavailableCount > original.unavailableCount) {
            val backup = File(root, "${original.id}/recovery-original-${java.util.UUID.randomUUID()}.json")
            commitRecordingMetadata(backup, SessionCodec.encode(original))
        }
        save(updated)
    }

    internal data class Entry(val id: String, val session: Session?, val recordedAt: Long)

    internal fun entries(): List<Entry> = root.listFiles().orEmpty().mapNotNull { directory ->
        if (!sessionName.matches(directory.name) || !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
            return@mapNotNull null
        val metadata = File(directory, "session.json")
        val backup = File(directory, "session.json.bak")
        if (!metadata.exists() && !backup.exists() && !File(directory, "session.json.new").exists() &&
            segmentFiles(directory.name).isEmpty()) return@mapNotNull null
        val session = runCatching { readMetadata(metadata) }.getOrNull()
        Entry(directory.name, session, maxOf(metadata.lastModified(), backup.lastModified(), directory.lastModified()))
    }.sortedWith(compareByDescending<Entry> { it.recordedAt }.thenBy { it.id })

    override fun loadCurrent(): Session? {
        // A deferred skit remains discoverable while the camera starts idle.
        val sessions = entries().mapNotNull { it.session }
        return sessions.firstOrNull()?.takeUnless { it.deferred }
    }

    private fun segmentFiles(sessionId: String): List<File> {
        if (!sessionName.matches(sessionId)) return emptyList()
        val directory = File(root, sessionId)
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return emptyList()
        return directory.listFiles().orEmpty().filter {
            SessionCleanup.segmentName.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
        }
    }

    override fun lastAttempt(sessionId: String): Int {
        if (!sessionName.matches(sessionId)) return 0
        val directory = File(root, sessionId)
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return 0
        return directory.listFiles().orEmpty().maxOfOrNull(::orderOf) ?: 0
    }

    override fun syncMedia(path: String) {
        java.nio.channels.FileChannel.open(File(path).toPath(), java.nio.file.StandardOpenOption.WRITE,
            NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun orderOf(file: File): Int =
        SessionCleanup.segmentName.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    override fun inspectRecovery(session: Session): SessionRecovery {
        val sources = segmentFiles(session.id).associateBy { it.absolutePath }
        val playableIds = session.segments.filter {
            val source = sources[it.path]
            source != null && runCatching { validate(source.path) }.isSuccess
        }.map { it.id }.toSet()
        val known = session.segments.filter { it.id in playableIds }.map { it.path }.toSet()
        val journalled = (session.unconfirmed + listOfNotNull(session.pending)).associateBy { it.path }
        val candidates = sources.values.filter { it.path !in known }.sortedBy(::orderOf).map {
            journalled[it.path] ?: PendingSegment(it.name, it.path, orderOf(it))
        }
        val attempted = (candidates + journalled.values.filter { it.path !in sources }).distinctBy { it.path }
        return SessionRecovery(playableIds, attempted, session.unavailableCount + session.segments.size - playableIds.size)
    }
    override fun loadLatest(): Session? {
        val metadata = root.listFiles().orEmpty()
            .filter { sessionName.matches(it.name) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
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
        fun read(file: File): Session {
            check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Invalid metadata file." }
            return file.bufferedReader().use { SessionCodec.decode(it.readText()) }
                .also { check(it.id == metadata.parentFile?.name) { "Session identity mismatch." } }
        }
        var baseFailure: Exception? = null
        if (metadata.exists()) {
            try {
                return read(metadata)
            } catch (exception: Exception) {
                baseFailure = exception
            }
        }
        val backup = File(metadata.path + ".bak")
        if (backup.exists()) {
            return try {
                read(backup)
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
