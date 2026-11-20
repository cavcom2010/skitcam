package com.calvinmazhindu.skitcam.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSamplingTest {
    @Test fun fullRangeLooksPastTheOpeningFrame() {
        // End is exclusive, so the last source time is 3999 ms.
        assertEquals(listOf(500L, 1999L, 2999L, 0L, 3999L), thumbnailSampleTimes(0, 4000))
    }

    @Test fun candidatesStayInsideTheRetainedRange() {
        val times = thumbnailSampleTimes(1000, 2000)
        assertEquals(listOf(1499L, 1749L, 1000L, 1999L), times)
        assertTrue(times.all { it in 1000L..1999L })
    }

    @Test fun shortClipsAreClampedAndDeduplicated() {
        val times = thumbnailSampleTimes(0, 100)
        assertEquals(listOf(49L, 74L, 0L, 99L), times)
        assertEquals(times.distinct(), times)
        assertEquals(listOf(0L), thumbnailSampleTimes(0, 1))
    }

    @Test fun darkFramesAreRecognised() {
        assertTrue(isDarkImage(intArrayOf(0, 5, 12, 15)))
        assertTrue(isDarkImage(intArrayOf(8, 9, 10, 11)))
        assertFalse(isDarkImage(intArrayOf(0, 5, 12, 60)))
        assertFalse(isDarkImage(intArrayOf(120, 130, 140, 150)))
    }
}
