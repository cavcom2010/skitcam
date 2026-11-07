package com.calvinmazhindu.skitcam.export

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Diagnostics only: never participates in success, retry, or recording decisions. */
internal object ExportTrace {
    private data class Run(val origin: String, val start: Long,
        val events: LinkedHashMap<String, Long> = linkedMapOf(),
        val spans: MutableList<JSONObject> = mutableListOf(),
        val bytes: MutableMap<String, Long> = linkedMapOf())
    private val process = UUID.randomUUID().toString()
    private val runs = object : LinkedHashMap<String, Run>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Run>?) = size > 32
    }
    fun now(): Long = SystemClock.elapsedRealtimeNanos()
    @Synchronized fun begin(id: String, origin: String, start: Long = now()) {
        if (!runs.containsKey(id)) runs[id] = Run(origin, start)
    }
    @Synchronized fun mark(id: String?, event: String) {
        runs[id]?.events?.putIfAbsent(event, now())
    }
    @Synchronized fun duration(id: String?, stage: String, start: Long) {
        runs[id]?.let { it.spans += JSONObject().put("stage", stage)
            .put("startMs", (start - it.start) / 1_000_000.0)
            .put("durationMs", (now() - start) / 1_000_000.0) }
    }
    fun <T> measure(id: String?, stage: String, block: () -> T): T {
        val start = now()
        return try { block() } finally { duration(id, stage, start) }
    }
    @Synchronized fun bytes(id: String?, kind: String, count: Long) {
        runs[id]?.let { it.bytes[kind] = (it.bytes[kind] ?: 0) + count }
    }
    @Synchronized fun flush(context: Context, id: String) {
        val run = runs[id] ?: return
        // A trace failure must never turn a verified save into a failure.
        runCatching {
            val json = JSONObject().put("schema", 1).put("process", process)
                .put("origin", run.origin).put("clock", "elapsedRealtimeNanos")
                .put("eventsMs", JSONObject().apply {
                    run.events.forEach { (name, time) -> put(name, (time - run.start) / 1_000_000.0) }
                }).put("spans", JSONArray(run.spans)).put("bytes", JSONObject(run.bytes))
            val atomic = AtomicFile(File(ExportJournal.directory(context, id), "performance.json"))
            val output = atomic.startWrite()
            try { output.write(json.toString(2).toByteArray()); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
    }
}
