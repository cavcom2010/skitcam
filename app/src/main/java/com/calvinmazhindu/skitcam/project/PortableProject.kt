package com.calvinmazhindu.skitcam.project

import com.calvinmazhindu.skitcam.recording.*
import com.calvinmazhindu.skitcam.export.ExportClip
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.VideoJson
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Portable outer name: bounded UTF-8, no Windows-reserved punctuation, stable job suffix. */
fun projectFilename(chosen: String,id: String): String {
    UUID.fromString(id)
    val stem=StringBuilder();var bytes=0
    val source=chosen.removeSuffix(".zip").removeSuffix(".partial")
    val points=source.codePoints().iterator()
    while(points.hasNext()) {
        val point=points.nextInt()
        if(Character.isISOControl(point) || point in 0xd800..0xdfff || (point<=127 && point.toChar() in "<>:\"/\\|?*")) continue
        val length=when { point<=127 -> 1;point<=2047 -> 2;point<=65535 -> 3;else -> 4 }
        if(bytes+length>140) break
        stem.appendCodePoint(point);bytes+=length
    }
    val safe=stem.toString().trim().trimStart('.').ifBlank { "SkitCam_Project" }
    return if(safe.contains(id)) "$safe.zip" else "${safe}_$id.zip"
}

/** Independent of mutable UI selections and of the current recording session. */
data class ProjectOptions(val originals: Boolean = true, val discarded: Boolean = false) {
    init { require(originals || !discarded) { "Discarded takes require original segments." } }
}
data class ProjectSnapshot(val session: Session, val exportId: String, val finishedUri: String,
    val finishedSha: String, val clips: List<ExportClip>, val sourceHashes: Map<String,String>) {
    fun selected(options: ProjectOptions) = if(options.originals) session.segments.filter { it.kept || options.discarded }.sortedBy { it.order } else emptyList()
    fun name(segment: Segment) = "Original_Segments/segment_%03d.mp4".format(java.util.Locale.ROOT,segment.order)
    companion object {
        fun fromJournal(text: String): ProjectSnapshot {
            val j=JSONObject(text)
            require(j.getInt("version") in 1..2 && j.getString("state")=="Completed" && j.getBoolean("ready")) { "Only a successfully published session can be archived." }
            val session=SessionCodec.decode(j.getString("session"))
            UUID.fromString(session.id);UUID.fromString(j.getString("id"))
            require(session.id==j.getString("sessionId") && session.state==RecordingState.Completed && session.pending==null && session.error==null)
            require(session.retainedCount in 1..RecordingEngine.MAX_SEGMENTS && session.validPlaybackOrder())
            require(session.invalidProfileJson==null && session.segments.all { it.invalidTrimJson==null && it.trim?.valid()!=false }) { "Session contains invalid edit metadata." }
            val a=j.getJSONArray("spec")
            fun integer(o: JSONObject,k: String): Long {
                val n=o.get(k);require(n is Int || n is Long) { "Export times must be integer milliseconds." };return (n as Number).toLong()
            }
            val clips=List(a.length()) { i -> a.getJSONObject(i).let { o ->
                ExportClip(o.getString("id"),o.getString("path"),TrimRange(integer(o,"duration"),integer(o,"start"),integer(o,"end")))
            } }
            require(clips.map { it.id }==session.playbackOrder && clips.all { clip ->
                clip.range.valid() && session.segments.any { s -> s.id==clip.id && s.path==clip.path && s.kept && (s.trim?.isTrimmed!=true || s.trim==clip.range) }
            }) { "Saved export order or trims are inconsistent." }
            val sources=j.getJSONArray("sources")
            require(sources.length()==clips.size)
            val hashes=List(sources.length()) { i -> sources.getJSONObject(i).let { it.getString("id") to it.getString("sha256") } }
            require(hashes.map { it.first }==clips.map { it.id } && hashes.all { ArchiveIO.validSha(it.second) })
            val sha=j.getString("verifiedSha");require(ArchiveIO.validSha(sha) && j.getJSONObject("verification").getString("sha256")==sha)
            val uri=j.getString("uri");require(uri.startsWith("content://"))
            return ProjectSnapshot(session,j.getString("id"),uri,sha,clips,hashes.toMap())
        }
    }
}

/** Whitelist conversion. Internal session JSON is NEVER placed in a portable ZIP. */
object PortableManifest {
    fun encode(snapshot: ProjectSnapshot, options: ProjectOptions, payloads: List<ArchivePayload>, observedDurations: Map<String,Long> = emptyMap()): ByteArray {
        val s=snapshot.session;val selected=snapshot.selected(options).associateBy { it.id }
        val byName=payloads.associateBy { it.name }
        require(byName.size==payloads.size && byName.containsKey("Finished_Video.mp4"))
        require(byName.keys==setOf("Finished_Video.mp4")+selected.values.map(snapshot::name))
        fun range(r: TrimRange?) = r?.let { JSONObject().put("sourceDurationMs",it.sourceDurationMs).put("trimStartMs",it.trimStartMs).put("trimEndMs",it.trimEndMs) } ?: JSONObject.NULL
        val profile=s.captureProfile?.let { JSONObject().put("width",it.width).put("height",it.height)
            .put("settings",VideoJson.settings(it.settings)).put("legacy",it.legacy) } ?: JSONObject.NULL
        return JSONObject().put("format","skitcam-portable-project").put("schemaVersion",1)
            .put("sessionId",s.id).put("exportId",snapshot.exportId).put("createdAtMs",s.createdAtMs)
            .put("timeUnit","milliseconds").put("trimConvention","start inclusive, end exclusive")
            .put("sessionOrientationDegrees",s.orientationDegrees).put("lens",s.lens.name).put("captureProfile",profile)
            .put("finishedVideo",JSONObject().put("path","Finished_Video.mp4").put("sha256",snapshot.finishedSha))
            .put("includeOriginals",options.originals).put("includeDiscardedOriginals",options.discarded)
            .put("playbackOrder",JSONArray(s.playbackOrder))
            .put("segments",JSONArray().apply { s.segments.sortedBy { it.order }.forEach { segment ->
                val name=selected[segment.id]?.let(snapshot::name)
                put(JSONObject().put("id",segment.id).put("recordingOrder",segment.order).put("kept",segment.kept)
                    .put("originalIncluded",name!=null).put("originalPath",name ?: JSONObject.NULL)
                    .put("originalSha256",name?.let { byName.getValue(it).sha256 } ?: JSONObject.NULL)
                    .put("recordedDurationMs",segment.durationMs).put("savedTrim",range(segment.trim))
                    .put("sourceDurationMs",segment.trim?.sourceDurationMs ?: snapshot.clips.find { it.id==segment.id }?.range?.sourceDurationMs ?: JSONObject.NULL)
                    .put("observedSourceDurationMs",observedDurations[segment.id] ?: JSONObject.NULL)
                    .put("orientationDegrees",segment.orientationDegrees).put("videoRotationDegrees",segment.videoRotationDegrees)
                    .put("media",segment.media?.let(VideoJson::media) ?: JSONObject.NULL))
            } })
            .put("finishedTimeline",JSONArray().apply { snapshot.clips.forEach { put(JSONObject().put("segmentId",it.id).put("resolvedRange",range(it.range))) } })
            .put("notes","Originals contain full takes, including trimmed-out footage. This is not a desktop-editor timeline import format.")
            .toString(2).toByteArray(Charsets.UTF_8)
    }
}
