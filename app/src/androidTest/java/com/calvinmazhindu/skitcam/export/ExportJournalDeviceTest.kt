package com.calvinmazhindu.skitcam.export

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only new journal fixtures are written. Existing sessions and media are never edited. */
@RunWith(AndroidJUnit4::class)
class ExportJournalDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun session()=Session(UUID.randomUUID().toString(),1,0,RecordingState.Completed,emptyList())
    @Test fun duplicateStopAndFinalisationFreezeOneSnapshot() {
        val session=session()
        ExportJournal.request(context,session)
        val id=ExportJournal.read(context,session.id)!!.getString("id")
        ExportJournal.request(context,session)
        ExportJournal.finalised(context,session)
        val saved=ExportJournal.read(context,session.id)!!.getString("session")
        ExportJournal.finalised(context,session.copy(error="later change"))
        val restored=ExportJournal.read(context,session.id)!!
        assertEquals(id,restored.getString("id"))
        assertEquals(saved,restored.getString("session"))
        assertEquals(session,SessionCodec.decode(saved))
    }
    @Test fun interruptedWriteRetainsPreviousCompleteJournal() {
        val session=session();ExportJournal.request(context,session)
        val before=ExportJournal.read(context,session.id).toString()
        val atomic=AtomicFile(File(ExportJournal.directory(context,session.id),"job.json"))
        atomic.startWrite().use { it.write("{partial".toByteArray());it.fd.sync() }
        assertEquals(before,ExportJournal.read(context,session.id).toString())
    }
    @Test fun processRecoveryRetainsPublicationCheckpointAndRetriesSameJob() {
        val session=session();ExportJournal.request(context,session);ExportJournal.finalised(context,session)
        ExportJournal.update(context,session.id,ExportState.Exporting)
        ExportJournal.update(context,session.id,ExportState.Verifying)
        val job=ExportJournal.read(context,session.id)!!
        job.put("verifiedSha","test-sha");ExportJournal.write(context,session.id,job)
        ExportJournal.update(context,session.id,ExportState.Publishing,uri="content://test/1")
        ExportJournal.busy=false;ExportJournal.status.value=null
        ExportJournal.restore(context,session)
        assertEquals(ExportState.Failed,ExportJournal.status.value!!.state)
        ExportJournal.update(context,session.id,ExportState.Preparing)
        val recovered=ExportJournal.read(context,session.id)!!
        assertEquals(job.getString("id"),recovered.getString("id"))
        assertEquals("test-sha",recovered.getString("verifiedSha"))
        assertEquals("content://test/1",recovered.getString("uri"))
    }
    @Test fun completedJobRestoresWithoutRestartAndInvalidTransitionLeavesBytesIntact() {
        val session=session();ExportJournal.request(context,session)
        ExportJournal.update(context,session.id,ExportState.Publishing)
        ExportJournal.update(context,session.id,ExportState.Completed,uri="content://test/2")
        ExportJournal.status.value=null;ExportJournal.restore(context,session)
        assertEquals(ExportState.Completed,ExportJournal.status.value!!.state)
        val before=ExportJournal.read(context,session.id).toString()
        try { ExportJournal.update(context,session.id,ExportState.Exporting);fail() } catch(_: IllegalStateException) { }
        assertEquals(before,ExportJournal.read(context,session.id).toString())
    }
}
