package com.calvinmazhindu.skitcam.export

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PublicationRecoveryTest {
    private val identity = PublicationIdentity("job.mp4", "Movies/SkitCam/", "app", "marker")
    private val row = PublicationRow(42, identity.name, identity.path, identity.owner, identity.marker, 1)

    @Test fun absenceRequiresSuccessfulEmptyLookupAndNoInsertionHistory() {
        assertEquals(PublicationLookup.Unknown, identity.resolve(null, null, false))
        assertEquals(PublicationLookup.Absent, identity.resolve(emptyList(), null, false))
        assertEquals(PublicationLookup.Unknown, identity.resolve(emptyList(), 42, false))
        assertEquals(PublicationLookup.Unknown, identity.resolve(emptyList(), null, true))
    }

    @Test fun identityAndVisibilityMustBeUnambiguous() {
        assertEquals(PublicationLookup.Pending(42), identity.resolve(listOf(row), null, true))
        assertEquals(PublicationLookup.Published(42), identity.resolve(listOf(row.copy(pending = 0, marker = null)), 42, true))
        for (invalid in listOf(row.copy(id = 41), row.copy(name = "different"), row.copy(path = null),
            row.copy(owner = "other"), row.copy(pending = null), row.copy(pending = 2), row.copy(trashed = 1))) {
            assertEquals(PublicationLookup.Unknown, identity.resolve(listOf(invalid), 42, true))
        }
        assertEquals(PublicationLookup.Unknown, identity.resolve(listOf(row.copy(marker = null)), null, true))
        assertEquals(PublicationLookup.Unknown, identity.resolve(listOf(row, row.copy(id = 43)), 42, true))
    }

    private class Port(vararg states: PublicationLookup) : PublicationRecovery.Port {
        private val states = ArrayDeque(states.toList())
        val actions = mutableListOf<String>()
        var readFails = false
        var pendingComplete = false
        override fun lookup() = states.removeFirst()
        override fun prepareCopy() { actions += "prepare" }
        override fun insertPending(): Long { actions += "insert"; return 42 }
        override fun saveRow(row: Long) { actions += "save:$row" }
        override fun pendingMatches(row: Long) = pendingComplete
        override fun writePending(row: Long) { actions += "write:$row" }
        override fun verifyReadOnly(row: Long) {
            actions += "read:$row"
            check(!readFails) { "digest mismatch" }
        }
        override fun makeVisible(row: Long) { actions += "visible:$row" }
    }

    @Test fun publishedMatchingOrMismatchingBytesNeverPermitAnyMutation() {
        for (mismatch in listOf(false, true)) {
            val port = Port(PublicationLookup.Published(42), PublicationLookup.Published(42)).apply { readFails = mismatch }
            if (mismatch) assertThrows(IllegalStateException::class.java) { PublicationRecovery(port).finish() }
            else assertEquals(42L, PublicationRecovery(port).finish())
            assertEquals(listOf("read:42"), port.actions)
        }
    }

    @Test fun unknownOrChangedStateIsNotATruncateOrInsertInstruction() {
        val unknown = Port(PublicationLookup.Unknown)
        assertThrows(IllegalStateException::class.java) { PublicationRecovery(unknown).finish() }
        assertTrue(unknown.actions.isEmpty())
        for (next in listOf(PublicationLookup.Unknown, PublicationLookup.Absent,
            PublicationLookup.Pending(43), PublicationLookup.Published(43))) {
            val changed = Port(PublicationLookup.Pending(42), next)
            assertThrows(IllegalStateException::class.java) { PublicationRecovery(changed).finish() }
            assertEquals(listOf("save:42"), changed.actions)
        }
    }

    @Test fun aRowMadeVisibleBeforeTheWritableOpenIsVerifiedReadOnly() {
        val port = Port(PublicationLookup.Pending(42), PublicationLookup.Published(42), PublicationLookup.Published(42))
        assertEquals(42L, PublicationRecovery(port).finish())
        assertEquals(listOf("save:42", "read:42"), port.actions)
    }

    @Test fun copyReadbackPrecedesVisibilityAndAGoodPendingRowIsNotInsertedAgain() {
        val pending = PublicationLookup.Pending(42)
        val visible = PublicationLookup.Published(42)
        val new = Port(PublicationLookup.Absent, pending, pending, pending, visible)
        assertEquals(42L, PublicationRecovery(new).finish())
        assertEquals(listOf("prepare", "insert", "save:42", "write:42", "read:42", "visible:42"), new.actions)
        val existing = Port(pending, pending, pending).apply { readFails = true }
        assertThrows(IllegalStateException::class.java) { PublicationRecovery(existing).finish() }
        assertEquals(listOf("save:42", "prepare", "write:42", "read:42"), existing.actions)
        val complete = Port(pending, pending, pending, visible).apply { pendingComplete = true }
        assertEquals(42L, PublicationRecovery(complete).finish())
        assertEquals(listOf("save:42", "read:42", "visible:42"), complete.actions)
    }

    @Test fun codecCreationIoIsNotClassifiedLikeOtherIo() {
        val io = IOException("no codec available")
        val failure = assertThrows(ExportDecoderException::class.java) {
            openVerifierCodec("video/") { throw io }
        }
        assertSame(io, failure.cause)
        assertEquals(ExportCandidate.FAILURE_DECODER, classifyVerificationFailure(failure))
        assertEquals(ExportCandidate.FAILURE_OTHER, classifyVerificationFailure(io))
        assertEquals(ExportCandidate.FAILURE_OTHER, classifyVerificationFailure(ExportIntegrityException("read", io)))
    }

    @Test fun publicationBudgetIsOneCopyAndAMarginWithCheckedArithmetic() {
        assertEquals(15_000L + 100L * 1024 * 1024, galleryCopyBytes(15_000))
        assertThrows(IllegalArgumentException::class.java) { galleryCopyBytes(0) }
        assertThrows(ArithmeticException::class.java) { galleryCopyBytes(Long.MAX_VALUE) }
    }
}
