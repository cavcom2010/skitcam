package com.calvinmazhindu.skitcam.ui.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTimeFormatTest {
    @Test fun clipDurationsAreFriendly() {
        assertEquals("0s", formatClipDuration(0))
        assertEquals("0.9s", formatClipDuration(900))
        assertEquals("4s", formatClipDuration(4_000))
        assertEquals("4.4s", formatClipDuration(4_411))
        assertEquals("1:01", formatClipDuration(61_000))
        assertEquals("1:01:01", formatClipDuration(3_661_000))
        assertEquals("0s", formatClipDuration(-50))
    }

    @Test fun frameStepsAndSnappingFollowRecordedCadence() {
        assertEquals(33L, frameStepMs(30.0))
        assertEquals(17L, frameStepMs(60.0))
        assertEquals(42L, frameStepMs(24.0))
        assertEquals(33L, frameStepMs(null))
        assertEquals(990L, snapToFrame(1_001, 33))
        assertEquals(33L, snapToFrame(20, 33))
        assertEquals(0L, snapToFrame(10, 33))
    }

    @Test fun transportAndPreciseTimesStayReadable() {
        assertEquals("00:00", formatClockTime(0))
        assertEquals("01:05", formatClockTime(65_000))
        assertEquals("1:01:05", formatClockTime(3_665_000))
        assertEquals("00:02", formatClockDuration(1_950))
        assertEquals("00:00", formatClockDuration(0))
        assertEquals("00:01.4", formatTransportTime(1_450))
        assertEquals("01:02.3", formatTransportTime(62_350))
        assertEquals("00:03.200", formatPreciseTime(3_200))
        assertEquals("01:02.003", formatPreciseTime(62_003))
    }
}
