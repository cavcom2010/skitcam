package com.calvinmazhindu.skitcam.recording

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** Moving the checked directory commits the deletion intent before any files are removed. */
internal class SessionCleanup(private val root: File) {
    fun pending(): List<String> = root.listFiles().orEmpty()
        .filter { it.name.startsWith(PREFIX) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
        .map { it.name.removePrefix(PREFIX) }.filter(::validId).sorted()

    fun abandon(id: String) {
        require(validId(id)) { "Invalid session identity." }
        val source = File(root, id)
        val staged = File(root, PREFIX + id)
        if (Files.exists(staged.toPath(), NOFOLLOW_LINKS)) {
            check(!Files.exists(source.toPath(), NOFOLLOW_LINKS)) { "Conflicting session folders. Files are retained." }
        } else {
            if (!Files.exists(source.toPath(), NOFOLLOW_LINKS)) return
            val files = checkedTree(source)
            files.filter { it.name == "job.json" || it.name == "job.json.bak" }.forEach {
                val job = JSONObject(it.readText())
                check(job.getString("sessionId") == id && canRetire(job)) {
                    "This skit may have a saved video. Its files are retained. Retry export to resolve publication."
                }
            }
            Files.move(source.toPath(), staged.toPath(), ATOMIC_MOVE)
        }
        syncRecordingDirectory(root)
        // Enumerate and validate the entire remaining tree before deleting the next file.
        val files = checkedTree(staged)
        files.forEach { Files.delete(it.toPath()) }
        syncRecordingDirectory(root)
    }

    private fun checkedTree(directory: File): List<File> {
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) &&
            directory.canonicalFile == File(root.canonicalFile, directory.name)) {
            "Unexpected session directory. Files are retained."
        }
        val files = checkNotNull(directory.listFiles()) { "Could not read the session folder." }
        return files.flatMap { file ->
            when {
                file.name == "export" || retiredExport.matches(file.name) -> exportFiles(file)
                (segmentName.matches(file.name) || sessionMetadata.matches(file.name)) &&
                    Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) -> listOf(file)
                else -> error("Unexpected file in this session. Files are retained.")
            }
        } + directory
    }

    private fun exportFiles(directory: File): List<File> {
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "Unexpected export folder." }
        val files = checkNotNull(directory.listFiles()) { "Could not read the export folder." }
        check(files.all { exportFile.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }) {
            "Unexpected export file. Files are retained."
        }
        return files.toList() + directory
    }

    companion object {
        private const val PREFIX = ".discard-"
        internal val segmentName = Regex("segment_([0-9]+)\\.mp4")
        private val sessionMetadata = Regex("(session|recovery-original-[a-f0-9-]+)\\.json(\\.new|\\.bak)?")
        private val retiredExport = Regex("previous-export-[a-f0-9-]+")
        private val exportFile = Regex("(job|performance)\\.json(\\.new|\\.bak)?|output\\.mp4|rejected-[0-9]+\\.mp4")
        fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

        fun canRetire(job: JSONObject): Boolean =
            job.optString("state") in setOf("Failed", "Cancelled", "Empty") &&
                !mayHavePublished(job)

        fun mayHavePublished(job: JSONObject): Boolean =
            job.optString("state") in setOf("Completed", "Publishing") ||
                job.optString("uri").isNotBlank() || job.optBoolean("publicationStarted") ||
                (!job.has("publicationStarted") && job.optString("verifiedSha").isNotBlank())
    }
}
