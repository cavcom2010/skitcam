package com.calvinmazhindu.skitcam.recording

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class SessionCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cleanup get() = SessionCleanup(temporary.root)
    private fun session() = temporary.newFolder(UUID.randomUUID().toString())
    private fun clip(directory: File, index: Int = 1) =
        File(directory, "segment_%03d.mp4".format(index)).apply { writeText("clip $index") }

    @Test fun deletesOnlyTheSelectedSessionAndRepeatedCleanupIsSafe() {
        val selected = session()
        clip(selected)
        clip(selected, 2)
        val unrelated = clip(session())
        val photo = temporary.newFile("photo.jpg").apply { writeText("photo") }
        cleanup.abandon(selected.name)
        cleanup.abandon(selected.name)
        assertFalse(selected.exists())
        assertEquals("clip 1", unrelated.readText())
        assertEquals("photo", photo.readText())
        assertTrue(cleanup.pending().isEmpty())
    }

    @Test fun unsafeEntriesCausePreflightFailureAndAreNotFollowed() {
        val selected = session()
        val original = clip(selected)
        val unrelated = clip(session())
        val link = File(selected, "segment_002.mp4")
        Files.createSymbolicLink(link.toPath(), unrelated.toPath())
        assertThrows(IllegalStateException::class.java) { cleanup.abandon(selected.name) }
        assertEquals("clip 1", original.readText())
        assertEquals("clip 1", unrelated.readText())
        assertTrue(cleanup.pending().isEmpty())
    }

    @Test fun interruptedCleanupRemainsVisibleAndCanBeRetried() {
        val selected = session()
        clip(selected)
        val id = selected.name
        val staged = File(temporary.root, ".discard-$id")
        Files.move(selected.toPath(), staged.toPath())
        val unknown = File(staged, "unexpected").apply { writeText("keep") }
        assertEquals(listOf(id), cleanup.pending())
        assertThrows(IllegalStateException::class.java) { cleanup.abandon(id) }
        assertEquals("clip 1", File(staged, "segment_001.mp4").readText())
        Files.delete(unknown.toPath())
        cleanup.abandon(id)
        assertTrue(cleanup.pending().isEmpty())
    }

    @Test fun completedAndAmbiguousPublicationsAreProtected() {
        for (state in listOf("Completed", "Publishing", "Failed")) {
            val selected = session()
            val video = clip(selected)
            val export = File(selected, "export").apply { mkdir() }
            File(export, "job.json").writeText(org.json.JSONObject()
                .put("sessionId", selected.name).put("state", state).put("verifiedSha", "old-checkpoint").toString())
            assertThrows(IllegalStateException::class.java) { cleanup.abandon(selected.name) }
            assertTrue(video.isFile)
        }
    }
}
