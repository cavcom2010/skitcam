package com.calvinmazhindu.skitcam.recording

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared JSON encoding for the atomic Android repository and ordinary JVM tests. */
object SessionCodec {
    fun encode(session: Session): String {
        check(session.validPlaybackOrder()) { "Invalid segment identity or playback order." }
        return JSONObject().put("version", 3).put("id", session.id)
        .put("createdAtMs", session.createdAtMs).put("orientationDegrees", session.orientationDegrees)
        .put("state", session.state.name).put("error", session.error ?: JSONObject.NULL)
        .put("segments", JSONArray().apply {
            session.segments.forEach { segment -> put(JSONObject()
                .put("order", segment.order).put("path", segment.path)
                .put("durationMs", segment.durationMs).put("orientationDegrees", segment.orientationDegrees)
                .put("videoRotationDegrees", segment.videoRotationDegrees).put("kept", segment.kept).put("id", segment.id)) }
        }).put("playbackOrder", JSONArray(session.playbackOrder)).put("pending", session.pending?.let {
            JSONObject().put("id", it.id).put("path", it.path).put("order", it.order)
        } ?: JSONObject.NULL).toString(2)
    }

    fun decode(text: String): Session {
        val json = JSONObject(text)
        val version = json.getInt("version")
        check(version in 1..3) { "Unknown session metadata version." }
        val sessionId = json.getString("id")
        val segments = json.getJSONArray("segments")
        val history =
            List(segments.length()) { index -> segments.getJSONObject(index).let {
                Segment(it.getInt("order"), it.getString("path"), it.getLong("durationMs"),
                    it.getInt("orientationDegrees"), it.getInt("videoRotationDegrees"),
                    if (version == 1) true else it.getBoolean("kept"),
                    if (version == 3) it.getString("id") else UUID.nameUUIDFromBytes(
                        "skitcam:$sessionId:segment:${it.getInt("order")}".toByteArray(Charsets.UTF_8)).toString())
            } }
        check(history.all { it.order > 0 } && history.map { it.order }.distinct().size == history.size) {
            "Invalid recording chronology."
        }
        val playback = if (version < 3) history.filter { it.kept }.sortedBy { it.order }.map { it.id }
            else json.getJSONArray("playbackOrder").let { ids -> List(ids.length()) { ids.getString(it) } }
        val session = Session(sessionId, json.getLong("createdAtMs"),
            json.getInt("orientationDegrees"), RecordingState.valueOf(json.getString("state")), history,
            if (json.isNull("pending")) null else json.getJSONObject("pending").let {
                PendingSegment(it.getString("id"), it.getString("path"), it.getInt("order"))
            }, if (json.isNull("error")) null else json.getString("error"), playback)
        check(session.validPlaybackOrder()) { "Invalid segment identity or playback order." }
        return session
    }
}
