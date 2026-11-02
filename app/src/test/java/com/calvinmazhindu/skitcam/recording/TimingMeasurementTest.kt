package com.calvinmazhindu.skitcam.recording

import com.calvinmazhindu.skitcam.export.TimingMeasurement
import org.junit.Assert.*
import org.junit.Test

class TimingMeasurementTest {
    @Test fun pcmEndIncludesFinalSamples() {
        assertEquals(1024L,TimingMeasurement.pcmFrames(4096,2,2))
        assertEquals(3550666L,TimingMeasurement.endUs(3529333,1024,48000))
        // The 120411 us last-PTS gap is not the approximately 108422 us presentation-end gap.
        assertEquals(108422L,TimingMeasurement.endUs(3529333,1024,48000)-3442244L)
    }
    @Test fun primingMayStartBeforeZero() { assertEquals(9333L,TimingMeasurement.endUs(-12000,1024,48000)) }
    @Test fun channelsFloatAndFractionalFrames() {
        assertEquals(448L,TimingMeasurement.pcmFrames(3584,2,4))
        assertEquals(101333L,TimingMeasurement.endUs(100000,64,48000))
        try { TimingMeasurement.pcmFrames(3,2,2);fail() } catch(_:IllegalArgumentException) { }
    }
    @Test fun invalidAndOverflowRejected() {
        for(block in listOf<()->Any>({ TimingMeasurement.pcmFrames(-1,2,2) }, { TimingMeasurement.endUs(0,-1,48000) }, { TimingMeasurement.endUs(0,1024,0) })) {
            try {block();fail()}catch(_:IllegalArgumentException) { }
        }
        try {TimingMeasurement.endUs(Long.MAX_VALUE,1024,48000);fail()}catch(_:ArithmeticException) { }
        try {TimingMeasurement.endUs(0,Long.MAX_VALUE,48000);fail()}catch(_:ArithmeticException) { }
    }
    @Test fun timestampGapRoundsToNearestFrameAndIsBounded() {
        assertEquals(3173L,TimingMeasurement.leadingGapFrames(66104,48000,1805000))
        assertEquals(6144L,TimingMeasurement.leadingGapFrames(128000,48000,3000000))
        assertEquals(0L,TimingMeasurement.leadingGapFrames(0,48000,1))
        for(p in listOf(-1L,2000001L,Long.MAX_VALUE)) {
            try {TimingMeasurement.leadingGapFrames(p,48000,3000000);fail()}catch(_:IllegalArgumentException) { }
        }
        try {TimingMeasurement.leadingGapFrames(200,48000,200);fail()}catch(_:IllegalArgumentException) { }
    }
    @Test fun primingAndTrimPrerollAreNotSourceGaps() {
        assertEquals(0L,TimingMeasurement.sourceGapUs(-21333,0))
        assertEquals(0L,TimingMeasurement.sourceGapUs(0,0))
        assertEquals(66104L,TimingMeasurement.sourceGapUs(66104,0))
        assertEquals(0L,TimingMeasurement.sourceGapUs(66104,300000))
        assertEquals(36104L,TimingMeasurement.sourceGapUs(66104,30000))
    }
}
