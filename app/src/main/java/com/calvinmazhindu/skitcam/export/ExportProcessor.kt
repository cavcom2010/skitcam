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
            val decision=if(forced || job.optInt("pathPolicy")==0) ExportDecision(ExportPath.RENDER,"Frozen standard-render policy")
            else ExportTrace.measure(id,"eligibilityProbe") {
                try { ExportPathPolicy.choose(spec,spec.clips.map { ExportInputProbe.inspect(File(it.path)) }) }
                catch(failure: Exception) { checkCancelled();ExportDecision(ExportPath.RENDER,"Probe cannot establish eligibility: ${failure.message}") }
            }
            job.put("selectedPath",decision.path.name).put("pathReason",decision.reason)
            ExportJournal.write(context,id,job)
        }
        val selected=ExportPath.valueOf(job.getString("selectedPath"))
        fun attempt(path: ExportPath): JSONObject {
            if(path==ExportPath.DIRECT_COPY) {
                val expected=job.getJSONArray("sources").getJSONObject(0).getString("sha256")
                ExportTrace.measure(id,"directCopy") { copy(File(spec.clips.single().path),candidate,expected) }
                ExportTrace.bytes(id,"directCopyBytes",candidate.length())
            } else {
                ExportTrace.measure(id,"transform") {
                    transform(context,spec,candidate,id,path==ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER) { percent ->
                        ExportJournal.status.value?.takeIf { it.sessionId==id && it.state==ExportState.Exporting }?.let { status ->
                            val verb=if(path==ExportPath.RENDER) "Encoding" else "Processing audio · preserving video"
                            ExportJournal.status.value=status.copy(message=percent?.let { "$verb · $it%" } ?: verb)
                        }
                    }
                }
            }
            ExportJournal.update(context,id,ExportState.Verifying)
            spec.captureProfile?.takeUnless { it.legacy }?.let { profile ->
                val output=inspect(candidate);val input=inspect(File(spec.clips.first().path))
                require(maxOf(output.width,output.height)==maxOf(profile.width,profile.height) && minOf(output.width,output.height)==minOf(profile.width,profile.height)) { "Export resolution does not match profile" }
                require(output.width==input.width && output.height==input.height) { "Export display orientation differs from source" }
            }
            return ExportTrace.measure(id,"verification") {
                verify(candidate,spec.durationMs,id) { timing ->
                    val checkpoint=ExportJournal.read(context,id)!!
                    checkpoint.put("timingDiagnostic",timing);ExportJournal.write(context,id,checkpoint)
                }
            }
        }
        return try { attempt(selected) } catch(failure: Exception) {
            val ioFailure=generateSequence<Throwable>(failure) { it.cause }.any { it is IOException }
            if(selected==ExportPath.RENDER || job.optBoolean("renderFallbackUsed") || ExportJournal.cancelRequested || ioFailure || freeBytes(context,candidate.parentFile!!)<=requiredBytes) throw failure
            checkCancelled()
            spec.clips.forEachIndexed { index, clip ->
                check(sha(File(clip.path))==job.getJSONArray("sources").getJSONObject(index).getString("sha256")) { "Source changed; fallback was not started" }
            }
            if(candidate.exists()) check(candidate.renameTo(File(candidate.parentFile,"rejected-${System.nanoTime()}.mp4"))) { "Cannot retain rejected candidate" }
            ExportJournal.fallback(context,id,failure.toString())
            ExportTrace.mark(id,"renderFallback")
            job=ExportJournal.read(context,id)!!
            attempt(ExportPath.RENDER)
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
