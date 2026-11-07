package com.calvinmazhindu.skitcam.project

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** New archive journals only; existing recording/session bytes are read-only. */
@RunWith(AndroidJUnit4::class)
class ProjectPersistenceDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun create(): String {
        check(context.packageName.endsWith(".m11validation") || context.packageName.endsWith(".m12bvalidation"))
        val id=File(context.filesDir,"validation-m11/fixture-session-id").readText()
        return ProjectJobs.create(context,CompletedProjectCatalog(context).load(id),ProjectOptions())
    }
    @Test fun interruptedAtomicWriteRecoversPreviousJobAndFrozenSnapshot() {
        val id=create();val before=ProjectJobs.read(context,id).toString()
        val atomic=AtomicFile(File(ProjectJobs.directory(context,id),"job.json"))
        atomic.startWrite().use { it.write("{partial".toByteArray());it.fd.sync() }
        assertEquals(before,ProjectJobs.read(context,id).toString())
    }
    @Test fun recoveryKeepsIdentityReceiptOptionsAndInvalidTransitionChangesNothing() {
        val id=create();val before=ProjectJobs.read(context,id)
        ProjectJobs.update(context,id,ProjectState.Preparing)
        ProjectJobs.update(context,id,ProjectState.Writing)
        ProjectJobs.update(context,id,ProjectState.Verifying) { it.put("archiveSha","a".repeat(64)).put("archiveBytes",100) }
        val recovered=ProjectJobs.recover(context,id)
        assertEquals(ProjectState.Interrupted,ProjectJobs.state(recovered))
        assertEquals(before.getString("snapshot"),recovered.getString("snapshot"))
        assertEquals(before.getString("sessionId"),recovered.getString("sessionId"))
        assertEquals(100,recovered.getInt("archiveBytes"))
        val bytes=File(ProjectJobs.directory(context,id),"job.json").readBytes()
        try { ProjectJobs.update(context,id,ProjectState.Completed);fail() } catch(_: IllegalArgumentException) {}
        assertArrayEquals(bytes,File(ProjectJobs.directory(context,id),"job.json").readBytes())
        ProjectJobs.update(context,id,ProjectState.Preparing)
        assertEquals(id,ProjectJobs.read(context,id).getString("id"))
    }
    @Test fun oversizedJobCannotReplaceAnExistingAtomicReceipt() {
        val id=create();val before=ProjectJobs.read(context,id).toString()
        val changed=ProjectJobs.read(context,id).put("oversizedFixture","x".repeat(8*1024*1024))
        try { ProjectJobs.write(context,changed);fail() } catch(_: IllegalArgumentException) {}
        assertEquals(before,ProjectJobs.read(context,id).toString())
    }
}
