package com.calvinmazhindu.skitcam.arrange

import com.calvinmazhindu.skitcam.recording.Session
import com.calvinmazhindu.skitcam.recording.SessionCodec
import org.json.JSONArray
import org.json.JSONObject

/** Uncommitted UI transaction; never consumed by playback, export or session recovery. */
data class InlineReorderDraft(val baseline: Session, val ids: List<String> = baseline.orderedSegmentIds) {
    val dirty get() = ids != baseline.orderedSegmentIds
    fun matches(session: Session) = baseline.id == session.id && baseline.segments == session.segments &&
        baseline.playbackOrder == session.playbackOrder && baseline.shelfOrder == session.shelfOrder
    fun move(id: String, destination: Int): InlineReorderDraft {
        val source = ids.indexOf(id)
        if (source < 0 || destination !in ids.indices || source == destination) return this
        return copy(ids = ids.toMutableList().apply { add(destination, removeAt(source)) })
    }
    fun encode(): String = JSONObject().put("version", 1)
        .put("baseline", JSONObject(SessionCodec.encode(baseline))).put("ids", JSONArray(ids)).toString()
    companion object {
        fun decode(text: String): InlineReorderDraft {
            val json = JSONObject(text)
            check(json.getInt("version") == 1)
            val baseline = SessionCodec.decode(json.getJSONObject("baseline").toString())
            val array = json.getJSONArray("ids")
            val ids = List(array.length()) { array.getString(it) }
            check(baseline.validShelfOrder(ids)) { "Invalid reorder checkpoint." }
            return InlineReorderDraft(baseline, ids)
        }
    }
}
