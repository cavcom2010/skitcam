package com.calvinmazhindu.skitcam.export

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal object ExportProcessor {
    /** Instrumentation can compare against the unchanged renderer, isolated package only. */
    @Volatile var forceRenderForTests = false
    @Volatile var availableBytesForTests: Long? = null
    @SuppressLint("UsableSpace")
    fun freeBytes(context: Context, directory: File): Long =
        availableBytesForTests?.takeIf { context.packageName.endsWith(".m12bvalidation") } ?: directory.usableSpace

    fun process(context: Context, id: String, spec: ExportSpec, candidate: File, requiredBytes: Long): JSONObject {
        var job=ExportJournal.read(context,id)!!
        val forced=context.packageName.endsWith(".m12bvalidation") && forceRenderForTests
        if(!job.has("selectedPath")) {
            val decision=ExportTrace.measure(id,"pathSelection") {
                checkCancelled()
                if(forced || job.optInt("pathPolicy")==0) ExportDecision(ExportPath.RENDER,"Frozen standard-render policy")
                else ExportPathPolicy.renderWithoutProbe(spec) ?: ExportTrace.measure(id,"eligibilityProbe") {
                    try { ExportPathPolicy.choose(spec,spec.clips.map { ExportInputProbe.inspect(File(it.path)) }) }
                    catch(failure: Exception) { checkCancelled();ExportDecision(ExportPath.RENDER,"Probe cannot establish eligibility: ${failure.message}") }
                }
            }
            job.put("selectedPath",decision.path.name).put("pathReason",decision.reason)
            ExportJournal.write(context,id,job)
        }
        var selected=ExportPath.valueOf(job.getString("selectedPath"))
        var verifying = false
        fun attempt(path: ExportPath): JSONObject {
            verifying = false
            job=ExportJournal.read(context,id)!!
            job.put("productionAttempts",job.optInt("productionAttempts",0)+1)
            job.remove("lastVerifyFailure")
            job.remove("lastVerifyFailureDetail")
            ExportJournal.write(context,id,job)
            if(path==ExportPath.DIRECT_COPY) {
                val expected=job.getJSONArray("sources").getJSONObject(0).getString("sha256")
                ExportTrace.measure(id,"directCopy") { copy(File(spec.clips.single().path),candidate,expected) }
                ExportTrace.bytes(id,"directCopyBytes",candidate.length())
            } else {
                ExportTrace.measure(id,"transform") {
                    if (path == ExportPath.RENDER && ExportFaults.has(ExportFaults.Mode.RENDER_PRODUCTION_IO))
                        throw IOException("Injected renderer production I/O")
                    transform(context,spec,candidate,id,path==ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER) { percent ->
                        ExportJournal.status.value?.takeIf { it.sessionId==id && it.state==ExportState.Exporting }?.let { status ->
                            val verb=if(path==ExportPath.RENDER) "Encoding" else "Processing audio · preserving video"
                            ExportJournal.status.value=status.copy(message=percent?.let { "$verb · $it%" } ?: verb)
                        }
                    }
                }
            }
            // Produce a durable identity before any decoder is opened so an interrupted or
            // failed verification can be retried against exactly these bytes.
            ExportFaults.beforeCheckpoint()
            ExportCandidate.commit(context,id,candidate,spec)
            ExportFaults.afterCheckpoint(candidate)
            job=ExportJournal.read(context,id)!!
            job.put("verificationAttempts",job.optInt("verificationAttempts",0)+1)
            ExportJournal.write(context,id,job)
            ExportJournal.update(context,id,ExportState.Verifying)
            verifying = true
            return verifyAttempt(context,id,spec,candidate)
        }
        while (true) {
            try { return attempt(selected) } catch(failure: Exception) {
                val incompatibility = if (!verifying && selected == ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER)
                    generateSequence<Throwable>(failure) { it.cause }
                        .filterIsInstance<ExportPassthroughIncompatibility>().firstOrNull()
                else null
                val badFastCandidate = verifying && classifyVerificationFailure(failure) == ExportCandidate.FAILURE_INTEGRITY
                if (selected == ExportPath.RENDER || job.optBoolean("renderFallbackUsed") ||
                    ExportJournal.cancelRequested || (incompatibility == null && !badFastCandidate)) throw failure

                // Commit the path before more IO or production. Retry then starts directly in RENDER,
                // including if the process dies or there isn't enough space to fall back now.
                ExportJournal.fallback(context,id,incompatibility?.message ?: failure.toString())
                ExportTrace.mark(id,"renderFallback")
                ExportFaults.publicationPoint(ExportFaults.Mode.KILL_AFTER_PATH_TRANSITION)
                checkCancelled()
                check(freeBytes(context,candidate.parentFile!!)>requiredBytes) {
                    "Insufficient space for finishing and gallery copy."
                }
                spec.clips.forEachIndexed { index, clip ->
                    checkCancelled()
                    check(sha(File(clip.path))==job.getJSONArray("sources").getJSONObject(index).getString("sha256")) {
                        "Source changed; fallback was not started"
                    }
                }
                if(candidate.exists()) check(candidate.renameTo(File(candidate.parentFile,"rejected-${System.nanoTime()}.mp4"))) {
                    "Cannot retain rejected candidate"
                }
                selected=ExportPath.RENDER
            }
        }
    }

    /**
     * Re-run every existing integrity check against a completed candidate using fresh
     * extractors and decoders. Never used for normal completion or publication recovery.
     */
    fun reverify(context: Context, id: String, spec: ExportSpec, candidate: File, checkpointSha: String): JSONObject {
        ExportTrace.mark(id,"candidateReverifyStarted")
        return verifyAttempt(context,id,spec,candidate,checkpointSha)
    }

    private fun verifyAttempt(context: Context, id: String, spec: ExportSpec,
        candidate: File, checkpointSha: String? = null): JSONObject {
        return try {
            val report = verifyProduced(context,id,spec,candidate)
            val checkpoint = ExportCandidate.from(checkNotNull(ExportJournal.read(context,id)))
            if (checkpoint == null || report.getString("sha256") != checkpoint.sha256 ||
                (checkpointSha != null && checkpointSha != checkpoint.sha256))
                throw ExportIntegrityException("Candidate bytes changed during verification.")
            report
        } catch (failure: Exception) {
            recordVerificationFailure(context,id,failure,classifyVerificationFailure(failure))
            throw failure
        }
    }

    private fun verifyProduced(context: Context, id: String, spec: ExportSpec, candidate: File): JSONObject {
        ExportTrace.measure(id,"verifyGeometry") {
            try {
                spec.captureProfile?.takeUnless { it.legacy }?.let { profile ->
                    val output=inspect(candidate);val input=inspect(File(spec.clips.first().path))
                    if(maxOf(output.width,output.height)!=maxOf(profile.width,profile.height) ||
                        minOf(output.width,output.height)!=minOf(profile.width,profile.height))
                        throw ExportIntegrityException("Export resolution does not match profile")
                    if(output.width!=input.width || output.height!=input.height)
                        throw ExportIntegrityException("Export display orientation differs from source")
                }
            } catch (failure: ExportIntegrityException) { throw failure
            } catch (failure: Exception) {
                throw ExportIntegrityException(failure.message ?: "Candidate geometry is unreadable.", failure)
            }
        }
        return ExportTrace.measure(id,"verification") {
            val expected = spec.durationMs + if (ExportFaults.has(ExportFaults.Mode.WRONG_VERIFICATION_DURATION)) 500 else 0
            verify(candidate,expected,id) { timing ->
                val checkpoint=ExportJournal.read(context,id)!!
                checkpoint.put("timingDiagnostic",timing);ExportJournal.write(context,id,checkpoint)
            }
        }
    }

    private fun recordVerificationFailure(context: Context, id: String, failure: Exception, kind: String) {
        runCatching {
            val job=ExportJournal.read(context,id) ?: return
            if(job.optString("state")!=ExportState.Verifying.name) return
            val detail=(failure as? ExportDecoderException)?.codecErrorCode?.let { "decoder error $it" }
                ?: failure.message.orEmpty()
            job.put("lastVerifyFailure",kind).put("lastVerifyFailureDetail",detail.take(400))
            ExportJournal.write(context,id,job)
        }
    }

    private fun copy(source: File, output: File, expected: String) {
        val digest=MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input -> output.outputStream().use { target ->
            val buffer=ByteArray(65536)
            while(true) {
                checkCancelled();val size=input.read(buffer);if(size<0) break
                target.write(buffer,0,size);digest.update(buffer,0,size)
            }
            target.fd.sync()
        } }
        if(digest.digest().joinToString("") { "%02x".format(it) }!=expected) throw IOException("Source changed while copying")
    }
}
