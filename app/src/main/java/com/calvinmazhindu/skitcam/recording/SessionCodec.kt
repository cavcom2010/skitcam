package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared JSON encoding for the atomic Android repository and ordinary JVM tests. */
object SessionCodec {
    fun encode(session: Session): String {
        check(session.validPlaybackOrder()) { "Invalid segment identity or playback order." }
        check(session.shelfOrder == null || (session.validShelfOrder(session.shelfOrder) &&
            session.shelfOrder.filter { id -> session.segments.any { it.id == id && it.kept } } == session.playbackOrder)) {
            "Invalid shelf order."
        }
        return JSONObject().put("version", 8).put("deferred", session.deferred)
        .put("lastAttempt", session.lastAttempt).put("unavailableCount", session.unavailableCount)
        .put("unconfirmed", JSONArray(session.unconfirmed.map(::encodePending))).put("shelfOrder", session.shelfOrder?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("lens", session.lens.name).put("id", session.id)
        .put("createdAtMs", session.createdAtMs).put("orientationDegrees", session.orientationDegrees)
        .put("captureProfile", session.invalidProfileJson?.let { JSONObject(it).get("value") } ?: session.captureProfile?.let(VideoJson::profile) ?: JSONObject.NULL)
        .put("state", session.state.name).put("error", session.error ?: JSONObject.NULL)
        .put("segments", JSONArray().apply {
            session.segments.forEach { segment -> put(JSONObject()
                .put("media", segment.media?.let(VideoJson::media) ?: JSONObject.NULL)
                .put("order", segment.order).put("path", segment.path)
                .put("durationMs", segment.durationMs).put("orientationDegrees", segment.orientationDegrees)
                .put("trim", segment.invalidTrimJson?.let { JSONObject(it).get("value") } ?: segment.trim?.let {
                    check(it.valid())
                    JSONObject().put("sourceDurationMs", it.sourceDurationMs).put("trimStartMs", it.trimStartMs).put("trimEndMs", it.trimEndMs)
                } ?: JSONObject.NULL)
                .put("videoRotationDegrees", segment.videoRotationDegrees).put("kept", segment.kept).put("id", segment.id)) }
        }).put("playbackOrder", JSONArray(session.playbackOrder)).put("pending", session.pending?.let(::encodePending) ?: JSONObject.NULL).toString(2)
    }

    private fun encodePending(segment: PendingSegment) =
        JSONObject().put("id", segment.id).put("path", segment.path).put("order", segment.order)

    private fun decodePending(json: JSONObject) =
        PendingSegment(json.getString("id"), json.getString("path"), json.getInt("order"))

    fun decode(text: String): Session {
        val json = JSONObject(text)
        val version = json.getInt("version")
        check(version in 1..8) { "Unknown session metadata version." }
        val sessionId = json.getString("id")
        val segments = json.getJSONArray("segments")
        val history =
            List(segments.length()) { index -> segments.getJSONObject(index).let {
                val raw = if (version >= 4 && !it.isNull("trim")) it.get("trim") else null
                val trim = try {
                    val obj = raw as? JSONObject
                    fun exact(key: String): Long {
                        val n = obj!!.get(key)
                        check(n is Long || n is Int) { "Trim must contain integer milliseconds." }
                        return (n as Number).toLong()
                    }
                    if (obj == null) null else TrimRange(exact("sourceDurationMs"), exact("trimStartMs"), exact("trimEndMs")).also { check(it.valid()) }
                } catch (_: Exception) { null }
                val invalid = if (raw != null && trim == null) JSONObject().put("value", raw).toString() else null
                Segment(it.getInt("order"), it.getString("path"), it.getLong("durationMs"),
                    it.getInt("orientationDegrees"), it.getInt("videoRotationDegrees"),
                    if (version == 1) true else it.getBoolean("kept"),
                    if (version >= 3) it.getString("id") else UUID.nameUUIDFromBytes(
                        "skitcam:$sessionId:segment:${it.getInt("order")}".toByteArray(Charsets.UTF_8)).toString(), trim, invalid, if(version>=6 && !it.isNull("media")) VideoJson.media(it.getJSONObject("media")) else null)
            } }
        check(history.all { it.order > 0 } && history.map { it.order }.distinct().size == history.size) {
            "Invalid recording chronology."
        }
        val playback = if (version < 3) history.filter { it.kept }.sortedBy { it.order }.map { it.id }
            else json.getJSONArray("playbackOrder").let { ids -> List(ids.length()) { ids.getString(it) } }
        val rawProfile = if(version>=6 && !json.isNull("captureProfile")) json.get("captureProfile") else null
        val profile = runCatching { rawProfile?.let { VideoJson.profile(it as JSONObject) } }.getOrNull()
        val invalidProfile = if(rawProfile!=null && profile==null) JSONObject().put("value",rawProfile).toString() else null
        val shelf = if (version >= 7 && !json.isNull("shelfOrder")) json.getJSONArray("shelfOrder").let { ids ->
            List(ids.length()) { ids.getString(it) }
        } else null
        val session = Session(sessionId, json.getLong("createdAtMs"),
            json.getInt("orientationDegrees"), RecordingState.valueOf(json.getString("state")), history,
            if (json.isNull("pending")) null else json.getJSONObject("pending").let {
                PendingSegment(it.getString("id"), it.getString("path"), it.getInt("order"))
            }, if (json.isNull("error")) null else json.getString("error"), playback, if (version >= 5) CameraLens.valueOf(json.getString("lens")) else CameraLens.Rear, profile, invalidProfile, shelf,
            json.optBoolean("deferred"),
            json.optJSONArray("unconfirmed")?.let { list ->
                List(list.length()) { decodePending(list.getJSONObject(it)) }
            }.orEmpty(), json.optInt("unavailableCount"), json.optInt("lastAttempt"))
        check(session.validPlaybackOrder()) { "Invalid segment identity or playback order." }
        check(shelf == null || (session.validShelfOrder(shelf) &&
            shelf.filter { id -> history.any { it.id == id && it.kept } } == playback)) { "Invalid shelf order." }
        return session
    }
}
