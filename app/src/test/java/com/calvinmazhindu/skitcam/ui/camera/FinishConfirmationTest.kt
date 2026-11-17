package com.calvinmazhindu.skitcam.ui.camera

import com.calvinmazhindu.skitcam.recording.*
import org.junit.Assert.*
import org.junit.Test

class FinishConfirmationTest {
    private fun snapshot(state: RecordingState = RecordingState.Recording, id: String = "skit",
        unresolved: Boolean = false) = RecordingSnapshot(state, Session(id, 1, 0, state), unresolved = unresolved)

    @Test fun cancelDoesNotSubmitAndCanReopen() {
        val gate = FinishConfirmationViewModel()
        val before = snapshot()
        gate.request(before, false)
        assertEquals("skit", gate.sessionId)
        gate.cancel()
        assertFalse(gate.consume(before, false))
        gate.request(before, false)
        assertTrue(gate.consume(before, false))
        assertEquals(RecordingState.Recording, before.state)
    }

    @Test fun duplicateConfirmationAndRequestAreIgnored() {
        val gate = FinishConfirmationViewModel()
        gate.request(snapshot(), false)
        gate.request(snapshot(), false)
        assertTrue(gate.consume(snapshot(), false))
        assertFalse(gate.consume(snapshot(), false))
        gate.request(snapshot(), false)
        assertNull(gate.sessionId)
        assertFalse(gate.eligible(snapshot(), false))
    }

    @Test fun staleSessionCannotBeConfirmed() {
        val gate = FinishConfirmationViewModel()
        gate.request(snapshot(), false)
        assertFalse(gate.consume(snapshot(id = "other"), false))
        gate.validate(snapshot(id = "other"))
        assertNull(gate.sessionId)
    }

    @Test fun blockedAndTransitionalStatesCannotStartOrConsume() {
        for (state in listOf(RecordingState.Ready, RecordingState.Starting,
            RecordingState.Finalising, RecordingState.Stopping, RecordingState.Completed)) {
            val gate = FinishConfirmationViewModel()
            gate.request(snapshot(state), false)
            assertNull("$state", gate.sessionId)
        }
        val gate = FinishConfirmationViewModel()
        gate.request(snapshot(), true)
        assertNull(gate.sessionId)
        gate.request(snapshot(), false)
        assertFalse(gate.consume(snapshot(), true))
        assertEquals("skit", gate.sessionId)
    }

    @Test fun unresolvedPausedOrErrorSessionCannotFinish() {
        val gate = FinishConfirmationViewModel()
        for (state in listOf(RecordingState.Paused, RecordingState.Error)) {
            assertFalse(gate.eligible(snapshot(state, unresolved = true), false))
            assertTrue(gate.eligible(snapshot(state), false))
        }
    }

    @Test fun backgroundPauseKeepsConfirmationWithoutSubmitting() {
        val gate = FinishConfirmationViewModel()
        gate.request(snapshot(), false)
        gate.validate(snapshot(RecordingState.Finalising))
        assertEquals("skit", gate.sessionId)
        assertFalse(gate.consume(snapshot(RecordingState.Finalising), false))
        gate.validate(snapshot(RecordingState.Paused))
        assertTrue(gate.consume(snapshot(RecordingState.Paused), false))
    }

    @Test fun completionClearsPromptAndNewSessionCanFinish() {
        val gate = FinishConfirmationViewModel()
        gate.request(snapshot(), false)
        gate.validate(snapshot(RecordingState.Completed))
        assertNull(gate.sessionId)
        gate.request(snapshot(id = "next"), false)
        assertTrue(gate.consume(snapshot(id = "next"), false))
    }

    @Test fun corruptMetadataWithoutSessionCanStillBeAcknowledged() {
        val gate = FinishConfirmationViewModel()
        val error = RecordingSnapshot(RecordingState.Error, message = "Metadata cannot be read")
        gate.request(error, false)
        assertNotNull(gate.sessionId)
        gate.validate(error)
        assertTrue(gate.consume(error, false))
        assertFalse(gate.consume(error, false))
    }
}
