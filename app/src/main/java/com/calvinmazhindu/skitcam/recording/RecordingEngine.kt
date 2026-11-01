package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.trim.*
import java.io.File
import java.util.UUID

enum class RecordingState { Ready, Starting, Recording, Finalising, Paused, Stopping, Completed, Error }

data class Segment(val order: Int, val path: String, val durationMs: Long,
    val orientationDegrees: Int, val videoRotationDegrees: Int, val kept: Boolean = true,
    val id: String = UUID.randomUUID().toString(), val trim: TrimRange? = null,
    val invalidTrimJson: String? = null) {
    val effectiveDurationMs: Long? get() = trim?.takeIf { it.valid() }?.effectiveDurationMs
    val displayDurationMs get() = effectiveDurationMs ?: if (invalidTrimJson == null) durationMs.coerceAtLeast(0) else 0L
}
data class PendingSegment(val id: String, val path: String, val order: Int)
data class Session(val id: String, val createdAtMs: Long, val orientationDegrees: Int,
    val state: RecordingState, val segments: List<Segment> = emptyList(),
    val pending: PendingSegment? = null, val error: String? = null,
    val playbackOrder: List<String> = segments.filter { it.kept }.sortedBy { it.order }.map { it.id }) {
    val durationMs: Long get() = safeDurationSum(segments.filter { it.kept }.map { it.displayDurationMs })
    val durationEstimated get() = segments.any { it.kept && it.trim == null }
    val retainedCount: Int get() = segments.count { it.kept }
    val playbackSegments: List<Segment> get() {
        val byId = segments.associateBy { it.id }
        return playbackOrder.map { byId.getValue(it) }
    }
    fun validPlaybackOrder(ids: List<String> = playbackOrder): Boolean {
        val retained = segments.filter { it.kept }.map { it.id }.toSet()
        return segments.all { it.id.isNotBlank() } && segments.map { it.id }.distinct().size == segments.size &&
            ids.size == retained.size && ids.toSet() == retained
    }
}
data class RecordingSnapshot(val state: RecordingState = RecordingState.Ready,
    val session: Session? = null, val activeDurationMs: Long = 0,
    val message: String? = null, val unresolved: Boolean = false) {
    val durationMs get() = safeDurationSum(listOf(session?.durationMs ?: 0, activeDurationMs))
    val count get() = session?.retainedCount ?: 0
    val openSession get() = session != null && state != RecordingState.Completed
}

sealed interface RecorderEvent {
    data object Started : RecorderEvent
    data class Status(val durationMs: Long, val audioError: Boolean = false) : RecorderEvent
    data class Finalized(val durationMs: Long, val error: String? = null) : RecorderEvent
}
fun interface RecordingHandle { fun stop() }
interface RecordingPort {
    fun start(pending: PendingSegment, orientationDegrees: Int,
        listener: (RecorderEvent) -> Unit): RecordingHandle
}
interface SessionStore {
    val rootPath: String
    fun save(session: Session)
    fun loadLatest(): Session?
    fun freeBytes(): Long
    /** Must verify a nonempty MP4 containing video and audio. Returns encoded rotation. */
    fun sourceFacts(path: String): SourceFacts? = null
    fun validate(path: String): Int
}
fun interface Cancellation { fun cancel() }
fun interface Watchdog { fun schedule(callback: () -> Unit): Cancellation }
data class SegmentEditResult(val success: Boolean, val message: String? = null)

/** All commands, events and store calls run on one worker. Camera operations are adapted by the port. */
class RecordingEngine(
    private val recorder: RecordingPort,
    private val store: SessionStore,
    private val watchdog: Watchdog,
    private val availability: () -> String? = { null },
    private val changed: (RecordingSnapshot) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val MAX_SEGMENTS = 100
        const val START_RESERVE = 100L * 1024 * 1024
        const val STOP_RESERVE = 50L * 1024 * 1024
        const val COMPLETED_MESSAGE = "Recording session completed. Original MP4 files retained."
    }
    var snapshot = RecordingSnapshot()
        private set
    private var activeId: String? = null
    private var handle: RecordingHandle? = null
    private var timer: Cancellation? = null
    private var terminalReason: String? = null

    private fun publish(state: RecordingState = snapshot.state, session: Session? = snapshot.session,
        duration: Long = snapshot.activeDurationMs, message: String? = snapshot.message) {
        snapshot = RecordingSnapshot(state, session, duration, message, activeId != null)
        changed(snapshot)
    }
    private fun persist(session: Session) {
        store.save(session)
        publish(session.state, session)
    }
    private fun fail(message: String) {
        terminalReason = message
        val session = snapshot.session?.copy(state = RecordingState.Error, error = message)
        publish(RecordingState.Error, session, duration = if (activeId == null) 0 else snapshot.activeDurationMs,
            message = message)
        if (session != null) {
            try { store.save(session) } catch (_: Exception) {
                publish(message = "$message Metadata could not be saved; existing files are retained.")
            }
        }
    }
    fun restore() {
        if (snapshot.state != RecordingState.Ready) return
        try {
            val session = store.loadLatest() ?: return
            publish(session.state, session, 0,
                if (session.state == RecordingState.Completed) COMPLETED_MESSAGE else session.error)
            if (session.state in setOf(RecordingState.Starting, RecordingState.Recording,
                    RecordingState.Finalising, RecordingState.Stopping)) {
                fail("Recording was interrupted. Confirmed segments and the unfinished file are retained. STOP closes this session.")
            }
        } catch (_: Exception) {
            fail("Session metadata could not be read. Files are retained. STOP acknowledges this error.")
        }
    }
    fun start(orientation: Int?) {
        if (snapshot.state !in setOf(RecordingState.Ready, RecordingState.Completed) || activeId != null) return
        if (!checkOrientation(orientation, null)) return
        val unavailable = availability()
        if (unavailable != null) { publish(message = unavailable); return }
        val id = UUID.randomUUID().toString()
        terminalReason = null
        publish(RecordingState.Starting, Session(id, now(), orientation!!, RecordingState.Starting), 0, null)
        beginSegment()
    }
    fun resume(orientation: Int?) {
        if (snapshot.state != RecordingState.Paused || activeId != null) return
        if (snapshot.count >= MAX_SEGMENTS) { publish(message = "100 segment limit reached. Press STOP."); return }
        if (!checkOrientation(orientation, snapshot.session!!.orientationDegrees)) return
        val unavailable = availability()
        if (unavailable != null) { publish(message = unavailable); return }
        publish(RecordingState.Starting, duration = 0, message = null)
        beginSegment()
    }
    private fun checkOrientation(actual: Int?, expected: Int?): Boolean {
        if (actual !in setOf(0, 90, 180, 270) || (expected != null && actual != expected)) {
            publish(message = "Hold the phone upright in ${expected?.let { "the original $it° direction" } ?: "a stable orientation"} before recording.")
            return false
        }
        return true
    }
    private fun beginSegment() {
        try {
            check(store.freeBytes() >= START_RESERVE) { "Low storage: at least 100 MiB free is required." }
            val session = snapshot.session!!
            val order = (session.segments.maxOfOrNull { it.order } ?: 0) + 1
            val pending = PendingSegment(UUID.randomUUID().toString(),
                File(File(store.rootPath, session.id), "segment_%03d.mp4".format(order)).absolutePath, order)
            // Journal the candidate before CameraX can create it. Never reuse an attempted path.
            persist(session.copy(state = RecordingState.Starting, pending = pending))
            activeId = pending.id
            publish(message = null)
            armWatchdog("Camera did not start in time.")
            handle = recorder.start(pending, session.orientationDegrees) { event -> onEvent(pending.id, event) }
        } catch (exception: Exception) {
            timer?.cancel()
            activeId = null
            handle = null
            fail(exception.message ?: "Unable to start recording.")
        }
    }
    fun pause() {
        if (snapshot.state == RecordingState.Recording) requestFinalization(RecordingState.Finalising)
    }
    /** Metadata edits never transition the recording state or remove a source file. */
    fun setSegmentKept(sessionId: String, segmentId: String, kept: Boolean): SegmentEditResult {
        if (snapshot.state != RecordingState.Paused || activeId != null) {
            return SegmentEditResult(false, "Segments can only be changed while paused.")
        }
        val session = snapshot.session ?: return SegmentEditResult(false, "No session is available.")
        if (session.id != sessionId) return SegmentEditResult(false, "This session is no longer selected.")
        val segment = session.segments.find { it.id == segmentId }
            ?: return SegmentEditResult(false, "Segment is no longer available.")
        if (segment.kept == kept) return SegmentEditResult(true)
        if (kept && session.retainedCount >= MAX_SEGMENTS) {
            return SegmentEditResult(false, "100 kept segments already. Discard another segment before restoring this one.")
        }
        val updated = session.copy(segments = session.segments.map {
            if (it.id == segmentId) it.copy(kept = kept) else it
        }, playbackOrder = if (kept) session.playbackOrder + segmentId else session.playbackOrder - segmentId)
        return try {
            store.save(updated)
            publish(session = updated, message = if (updated.retainedCount == MAX_SEGMENTS)
                "100 segment limit reached. Discard a segment or press STOP." else null)
            SegmentEditResult(true)
        } catch (_: Exception) {
            SegmentEditResult(false, "Could not save the decision. The previous status and original file are unchanged.")
        }
    }
    fun saveTrim(sessionId: String, segmentId: String, expected: Segment, source: TrimSource,
        range: TrimRange): SegmentEditResult {
        if (snapshot.state != RecordingState.Paused || activeId != null)
            return SegmentEditResult(false, "Trimming is only available while paused.")
        val session = snapshot.session ?: return SegmentEditResult(false, "No session.")
        val segment = session.segments.find { it.id == segmentId }
        if (session.id != sessionId || segment == null || segment != expected)
            return SegmentEditResult(false, "The segment changed. Cancel and reopen Trim.")
        if (!range.valid() || range.sourceDurationMs != source.presentationDurationMs) return SegmentEditResult(false, "Invalid trim range or unverified duration.")
        return try {
            store.validate(segment.path)
            check(source.facts.durationMs > 0 && store.sourceFacts(segment.path) == source.facts) { "Source changed or unavailable. Reopen Trim." }
            val updated = session.copy(segments = session.segments.map {
                if (it.id == segmentId) it.copy(trim = range, invalidTrimJson = null) else it
            })
            store.save(updated)
            publish(session = updated)
            SegmentEditResult(true)
        } catch (e: Exception) {
            SegmentEditResult(false, e.message ?: "Could not save trim. Previous metadata is retained.")
        }
    }
    fun savePlaybackOrder(sessionId: String, expectedOrder: List<String>, order: List<String>): SegmentEditResult {
        if (snapshot.state != RecordingState.Paused || activeId != null) {
            return SegmentEditResult(false, "Arrangement can only be saved while paused.")
        }
        val session = snapshot.session ?: return SegmentEditResult(false, "No session is available.")
        if (session.id != sessionId || session.playbackOrder != expectedOrder) {
            return SegmentEditResult(false, "The session changed. Cancel and reopen Arrange.")
        }
        if (!session.validPlaybackOrder(order)) {
            return SegmentEditResult(false, "Order must contain each kept segment exactly once.")
        }
        if (session.playbackOrder == order) return SegmentEditResult(true)
        return try {
            val updated = session.copy(playbackOrder = order.toList())
            store.save(updated)
            publish(session = updated)
            SegmentEditResult(true)
        } catch (_: Exception) {
            SegmentEditResult(false, "Could not save the order. The previous order and all MP4s are unchanged.")
        }
    }
    fun stop() {
        when (snapshot.state) {
            RecordingState.Recording -> requestFinalization(RecordingState.Stopping)
            RecordingState.Paused, RecordingState.Error -> if (activeId == null) {
                try {
                    val session = snapshot.session
                    if (session != null) persist(session.copy(state = RecordingState.Completed))
                    publish(RecordingState.Completed, duration = 0,
                        message = COMPLETED_MESSAGE)
                } catch (_: Exception) { fail("Could not complete session metadata. Files are retained.") }
            }
            else -> Unit
        }
    }
    fun background() {
        if (snapshot.state in setOf(RecordingState.Starting, RecordingState.Recording)) {
            requestFinalization(RecordingState.Finalising, "Paused because SkitCam left the foreground.")
        }
    }
    fun interruption(reason: String) {
        if (activeId != null && snapshot.state in setOf(RecordingState.Starting, RecordingState.Recording)) {
            terminalReason = reason
            requestFinalization(RecordingState.Finalising, reason)
        } else if (snapshot.openSession && activeId == null && snapshot.state != RecordingState.Error) {
            fail(reason)
        }
    }
    private fun requestFinalization(state: RecordingState, message: String? = null) {
        publish(state, message = message)
        // Stop immediately; journaling must not delay the stop request.
        try { handle?.stop() } catch (exception: Exception) {
            terminalReason = exception.message ?: "Could not stop recording."
        }
        armWatchdog("Finalisation timed out. No new recording can begin until it resolves. Files are retained.")
        try { persist(snapshot.session!!.copy(state = state)) }
        catch (_: Exception) { terminalReason = "Unable to save session metadata. Files are retained." }
    }
    private fun armWatchdog(message: String) {
        timer?.cancel()
        val id = activeId
        timer = watchdog.schedule {
            if (id != null && id == activeId) {
                try { handle?.stop() } catch (_: Exception) { /* Await the correlated final event. */ }
                fail(message)
            }
        }
    }
    private fun onEvent(id: String, event: RecorderEvent) {
        if (id != activeId) return
        when (event) {
            RecorderEvent.Started -> if (snapshot.state == RecordingState.Starting) {
                timer?.cancel()
                try { persist(snapshot.session!!.copy(state = RecordingState.Recording)) }
                catch (_: Exception) { interruption("Recording metadata could not be saved."); return }
                publish(RecordingState.Recording)
            }
            is RecorderEvent.Status -> {
                publish(duration = event.durationMs)
                if (snapshot.state == RecordingState.Recording) {
                    try {
                        when {
                            event.audioError -> interruption("Microphone recording failed. Earlier segments are retained.")
                            store.freeBytes() < STOP_RESERVE -> interruption("Low storage: recording stopped to preserve files.")
                        }
                    } catch (_: Exception) { interruption("Storage is unavailable. Files are retained.") }
                }
            }
            is RecorderEvent.Finalized -> finalized(event)
        }
    }
    private fun finalized(event: RecorderEvent.Finalized) {
        timer?.cancel()
        val requestedState = snapshot.state
        activeId = null
        handle = null
        if (event.error != null) { fail(event.error); return }
        try {
            val session = snapshot.session!!
            val pending = session.pending!!
            check(event.durationMs > 0) { "Recording contained no valid duration." }
            val rotation = store.validate(pending.path)
            val source = store.sourceFacts(pending.path)
            val segment = Segment(pending.order, pending.path, event.durationMs,
                session.orientationDegrees, rotation, id = pending.id, trim = source?.let { TrimRange.full(it.durationMs) })
            val next = when {
                terminalReason != null -> RecordingState.Error
                requestedState == RecordingState.Stopping -> RecordingState.Completed
                requestedState == RecordingState.Finalising -> RecordingState.Paused
                else -> RecordingState.Error
            }
            val message = terminalReason ?: when (next) {
                RecordingState.Completed -> COMPLETED_MESSAGE
                RecordingState.Error -> "Recording ended unexpectedly. The successful segment is retained."
                else -> if (session.retainedCount + 1 == MAX_SEGMENTS) "100 segment limit reached. Discard a segment or press STOP." else snapshot.message
            }
            // UI count changes only after durable registration, never when stop() is called.
            val updated = session.copy(state = next, segments = session.segments + segment,
                pending = null, error = if (next == RecordingState.Error) message else null,
                playbackOrder = session.playbackOrder + segment.id)
            store.save(updated)
            publish(next, updated, 0, message)
        } catch (exception: Exception) {
            fail("${exception.message ?: "Segment could not be registered."} Files are retained.")
        }
    }
}
