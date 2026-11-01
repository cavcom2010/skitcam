package com.calvinmazhindu.skitcam.recording

import org.json.JSONArray
import org.json.JSONObject

/** Shared JSON encoding for the atomic Android repository and ordinary JVM tests. */
object SessionCodec {
    fun encode(session: Session): String = JSONObject().put("version", 2).put("id", session.id)
        .put("createdAtMs", session.createdAtMs).put("orientationDegrees", session.orientationDegrees)
        .put("state", session.state.name).put("error", session.error ?: JSONObject.NULL)
        .put("segments", JSONArray().apply {
            session.segments.forEach { segment -> put(JSONObject()
                .put("order", segment.order).put("path", segment.path)
                .put("durationMs", segment.durationMs).put("orientationDegrees", segment.orientationDegrees)
                .put("videoRotationDegrees", segment.videoRotationDegrees).put("kept", segment.kept)) }
        }).put("pending", session.pending?.let {
            JSONObject().put("id", it.id).put("path", it.path).put("order", it.order)
        } ?: JSONObject.NULL).toString(2)

    fun decode(text: String): Session {
        val json = JSONObject(text)
        val version = json.getInt("version")
        check(version in 1..2) { "Unknown session metadata version." }
        val segments = json.getJSONArray("segments")
        return Session(json.getString("id"), json.getLong("createdAtMs"),
            json.getInt("orientationDegrees"), RecordingState.valueOf(json.getString("state")),
            List(segments.length()) { index -> segments.getJSONObject(index).let {
                Segment(it.getInt("order"), it.getString("path"), it.getLong("durationMs"),
                    it.getInt("orientationDegrees"), it.getInt("videoRotationDegrees"),
                    if (version == 1) true else it.getBoolean("kept"))
            } },
            if (json.isNull("pending")) null else json.getJSONObject("pending").let {
                PendingSegment(it.getString("id"), it.getString("path"), it.getInt("order"))
            }, if (json.isNull("error")) null else json.getString("error"))
    }
}
