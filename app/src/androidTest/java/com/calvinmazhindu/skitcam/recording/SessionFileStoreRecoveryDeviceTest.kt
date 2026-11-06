package com.calvinmazhindu.skitcam.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the real AtomicFile-backed repository without touching production sessions. */
@RunWith(AndroidJUnit4::class)
class SessionFileStoreRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "m12a-recovery/${UUID.randomUUID()}")

    @After fun cleanup() { root.deleteRecursively() }

    private fun session(id: String = "session", state: RecordingState = RecordingState.Paused) =
        Session(id, 1L, 0, state,
            listOf(Segment(1, "/fixture/${id}_1.mp4", 1000L, 0, 0, id = "$id-segment-1")))
    private fun store() = SessionFileStore(root)
    private fun metadata(id: String) = File(File(root, id), "session.json")

    @Test fun validNewerBaseWinsOverStaleLegacyBackup() {
        val store = store()
        val old = session()
        store.save(old)
        metadata(old.id).copyTo(File(metadata(old.id).path + ".bak"))
        val newer = old.copy(state = RecordingState.Completed)
        metadata(old.id).writeText(SessionCodec.encode(newer))
        assertEquals(newer, store.loadLatest())
    }

    @Test fun corruptBaseFallsBackToValidLegacyBackup() {
        val store = store()
        val valid = session()
        store.save(valid)
        metadata(valid.id).copyTo(File(metadata(valid.id).path + ".bak"))
        metadata(valid.id).writeText("{not valid json")
        assertEquals(valid, store.loadLatest())
    }

    @Test fun legacyBackupOnlyIsReadable() {
        val store = store()
        val valid = session()
        store.save(valid)
        metadata(valid.id).copyTo(File(metadata(valid.id).path + ".bak"))
        metadata(valid.id).delete()
        assertEquals(valid, store.loadLatest())
    }

    @Test fun uncommittedNewFileIsNeverLoadedAsRecovered() {
        val store = store()
        val orphan = session("orphan")
        File(root, orphan.id).mkdirs()
        File(root, "${orphan.id}/session.json.new").writeText(SessionCodec.encode(orphan))
        assertNull(store.loadLatest())
    }

    @Test fun corruptBaseWithoutBackupFailsSafely() {
        val store = store()
        val valid = session()
        store.save(valid)
        metadata(valid.id).writeText("{not valid json")
        var failed = false
        try { store.loadLatest() } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertTrue(metadata(valid.id).exists())
    }
}
