package com.calvinmazhindu.skitcam.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingBatteryPolicyTest {
    private val policy = RecordingBatteryPolicy()

    @Test fun thresholdsAndChargingUseTheSamePercentage() {
        for (charging in listOf(false, true)) {
            assertTrue(policy.allowsCapture(BatteryReading(11.0, charging)))
            assertTrue(policy.allowsCapture(BatteryReading(10.0, charging)))
            assertFalse(policy.allowsCapture(BatteryReading(9.99, charging)))
            assertFalse(policy.shouldPause(BatteryReading(7.01, charging)))
            assertTrue(policy.shouldPause(BatteryReading(7.0, charging)))
            assertFalse(policy.allowsCapture(BatteryReading(5.0, charging)))
            assertTrue(policy.shouldPause(BatteryReading(0.0, charging)))
        }
        assertNull(policy.warning(BatteryReading(11.0), RecordingState.Recording))
        assertNotNull(policy.warning(BatteryReading(10.0), RecordingState.Recording))
        assertTrue(policy.warning(BatteryReading(5.0), RecordingState.Ready)!!.contains("critically low"))
    }

    @Test fun invalidReadingsBlockNewCaptureWithoutForcingAPause() {
        for (reading in listOf(BatteryReading(), BatteryReading(-1.0), BatteryReading(Double.NaN),
            BatteryReading.from(-1, 100, true), BatteryReading.from(1, 0, false),
            BatteryReading.from(101, 100, false))) {
            assertFalse(policy.allowsCapture(reading))
            assertFalse(policy.shouldPause(reading))
        }
        assertEquals(7.0, BatteryReading.from(14, 200, false).knownPercentage)
        val injected = RecordingBatteryPolicy(warningAt = 80, pauseAt = 75, criticalAt = 70)
        assertTrue(injected.shouldPause(BatteryReading(75.0)))
        assertTrue(injected.allowsCapture(BatteryReading(80.0)))
    }
}
