package com.calvinmazhindu.skitcam.review

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationTextTest {
    @Test fun friendlyDurations() {
        assertEquals("0s", formatClipDuration(0))
        assertEquals("0.9s", formatClipDuration(900))
        assertEquals("4s", formatClipDuration(4_000))
        assertEquals("4.4s", formatClipDuration(4_411))
        assertEquals("1:01", formatClipDuration(61_000))
        assertEquals("1:01:01", formatClipDuration(3_661_000))
        assertEquals("0s", formatClipDuration(-50))
    }
}
