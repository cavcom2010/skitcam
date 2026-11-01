package com.calvinmazhindu.skitcam.arrange

import com.calvinmazhindu.skitcam.recording.Session

/** Transient UI transaction. Recording chronology and persistent state are never mutated here. */
data class ArrangementDraft(val sessionId: String, val baseline: List<String>, val ids: List<String>) {
    fun move(id: String, destination: Int): ArrangementDraft {
        val source = ids.indexOf(id)
        if (source < 0 || destination !in ids.indices || source == destination) return this
        val updated = ids.toMutableList()
        updated.add(destination, updated.removeAt(source))
        return copy(ids = updated)
    }
    fun matches(session: Session) = session.id == sessionId && session.playbackOrder == baseline
    companion object {
        fun from(session: Session) = ArrangementDraft(session.id, session.playbackOrder.toList(), session.playbackOrder.toList())
    }
}
