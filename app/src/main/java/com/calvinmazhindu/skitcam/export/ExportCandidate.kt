package com.calvinmazhindu.skitcam.export

import android.content.Context
import com.calvinmazhindu.skitcam.recording.SessionCleanup
import com.calvinmazhindu.skitcam.video.CaptureProfile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable identity of a finished, not-yet-verified candidate.
 *
 * The checkpoint is written only after production has returned and the bytes have been
 * synced. It is deliberately separate from `verifiedSha`: it proves which bytes were
 * produced for which job, not that they are safe to publish.
 */
internal object ExportCandidate {
    const val FAILURE_DECODER = "decoder"
    const val FAILURE_INTEGRITY = "integrity"
    const val FAILURE_OTHER = "other"
    const val RETRY_REBUILD_MESSAGE = "Checking the finished video was interrupted. Retry rebuilds it from the original clips."
    const val RETRY_RECHECK_MESSAGE = "Checking the finished video was interrupted. Retry can re-check it without re-encoding if it is unchanged. Original clips are preserved."

    private val digestPattern = Regex("[0-9a-f]{64}")

    data class Checkpoint(val attempt: String, val bytes: Long, val sha256: String,
        val specSha256: String, val sourcesSha256: String, val completedAt: Long)

    sealed interface Reuse {
        data class Ready(val checkpoint: Checkpoint) : Reuse
        data class Rejected(val reason: String) : Reuse
    }

    fun from(job: JSONObject): Checkpoint? = runCatching {
        val value = job.getJSONObject("candidate")
        Checkpoint(value.getString("attempt"), value.getLong("bytes"), value.getString("sha256"),
            value.getString("specSha256"), value.getString("sourcesSha256"), value.getLong("completedAt"))
    }.getOrNull()?.takeIf {
        runCatching { UUID.fromString(it.attempt).toString() == it.attempt }.getOrDefault(false) &&
            it.bytes > 0 && digestPattern.matches(it.sha256) && digestPattern.matches(it.specSha256) &&
            digestPattern.matches(it.sourcesSha256)
    }

    /** Bind the candidate to the frozen ordered clips, trims, orientation and output profile. */
    fun specFingerprint(spec: ExportSpec): String {
        val text = buildString {
            append("skitcam-export-spec-v1\n")
            append(spec.sessionId).append('\n')
            append(spec.orientation).append('\n')
            append(spec.captureProfile?.let(::profileKey) ?: "no-profile").append('\n')
            spec.clips.forEach { clip ->
                append(clip.id).append('\u0001').append(clip.path).append('\u0001')
                append(clip.range.sourceDurationMs).append('\u0001')
                append(clip.range.trimStartMs).append('\u0001')
                append(clip.range.trimEndMs).append('\n')
            }
        }
        return digest(text.toByteArray(Charsets.UTF_8))
    }

    fun sourceFingerprint(job: JSONObject): String {
        val sources = job.getJSONArray("sources")
        val text = buildString {
            for (index in 0 until sources.length()) {
                val source = sources.getJSONObject(index)
                append(source.getString("id")).append(':').append(source.getString("sha256")).append('\n')
            }
        }
        return digest(text.toByteArray(Charsets.UTF_8))
    }

    /** Commit the finished bytes durably; the caller must already have produced a complete file. */
    fun commit(context: Context, id: String, candidate: File, spec: ExportSpec) {
        require(candidate.isFile && candidate.length() > 0) { "Completed candidate is unavailable." }
        FileOutputStream(candidate, true).use { it.fd.sync() }
        val job = ExportJournal.read(context, id)!!
        val sha256 = ExportTrace.measure(id, "candidateCheckpointHash") { sha(candidate) }
        ExportTrace.bytes(id, "candidateCheckpointRead", candidate.length())
        job.put("candidate", JSONObject().put("attempt", UUID.randomUUID().toString())
            .put("bytes", candidate.length()).put("sha256", sha256)
            .put("specSha256", specFingerprint(spec)).put("sourcesSha256", sourceFingerprint(job))
            .put("completedAt", System.currentTimeMillis()))
            .put("candidateReverified", false)
        job.remove("lastVerifyFailure")
        job.remove("lastVerifyFailureDetail")
        job.remove("candidateReuseRejected")
        ExportJournal.write(context, id, job)
    }

    /** Forget a candidate that is about to be deleted or replaced. */
    fun clear(context: Context, id: String, job: JSONObject) {
        if (!job.has("candidate") && !job.has("candidateReverified")) return
        job.remove("candidate")
        job.remove("candidateReverified")
        ExportJournal.write(context, id, job)
    }

    fun assess(context: Context, id: String, spec: ExportSpec, job: JSONObject,
        candidate: File, retry: Boolean): Reuse {
        if (!retry) return Reuse.Rejected("Only an explicit Retry can reuse a finished candidate")
        val checkpoint = from(job) ?: return Reuse.Rejected("No completed candidate is recorded for this job")
        if (job.optBoolean("candidateReverified")) return Reuse.Rejected("The finished candidate was already re-checked once")
        if (job.optString("verifiedSha").isNotBlank()) return Reuse.Rejected("A verified candidate already exists")
        if (job.optBoolean("publicationStarted") || job.optString("uri").isNotBlank() || SessionCleanup.mayHavePublished(job))
            return Reuse.Rejected("Publication may already have started")
        val previous = job.optString("lastVerifyFailure")
        if (previous.isNotEmpty() && previous != FAILURE_DECODER)
            return Reuse.Rejected("The previous failure was not a transient decoder failure")
        val directory = ExportJournal.directory(context, id).canonicalFile
        if (candidate.parentFile?.canonicalFile != directory || !Files.isRegularFile(candidate.toPath(), NOFOLLOW_LINKS))
            return Reuse.Rejected("The finished candidate is not a file owned by this job")
        if (candidate.length() != checkpoint.bytes)
            return Reuse.Rejected("The finished candidate size changed")
        if (specFingerprint(spec) != checkpoint.specSha256)
            return Reuse.Rejected("The export specification changed")
        if (sourceFingerprint(job) != checkpoint.sourcesSha256)
            return Reuse.Rejected("The frozen source list changed")
        return Reuse.Ready(checkpoint)
    }

    private fun profileKey(profile: CaptureProfile): String =
        listOf(profile.cameraId, profile.settings.quality, profile.settings.fps ?: "none",
            profile.settings.stabilization, profile.settings.whiteBalance, profile.width, profile.height,
            profile.legacy).joinToString("|")

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
