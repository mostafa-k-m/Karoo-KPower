package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Test

class AccelerationTrackerTest {

    private var clock = 0L
    private val tracker = AccelerationTracker { clock }

    private fun sample(atMs: Long, speed: Double, t: AccelerationTracker = tracker): Double {
        clock = atMs
        return t.update(speed)
    }

    @Test
    fun singleSampleIsZero() {
        assertEquals(0.0, sample(0, 5.0), 1e-9)
    }

    @Test
    fun constantSpeedIsZero() {
        var a = 0.0
        for (i in 0..5) a = sample(i * 1000L, 8.0)
        assertEquals(0.0, a, 1e-9)
    }

    @Test
    fun linearRampGivesItsSlope() {
        // Once the window holds only the ramp (the median filter lags the first samples)
        var a = 0.0
        for (i in 0..15) a = sample(i * 1000L, i * 1.4)
        assertEquals(1.4, a, 1e-9)
    }

    @Test
    fun decelerationIsNegative() {
        var a = 0.0
        for (i in 0..10) a = sample(i * 1000L, 10.0 - i * 0.5)
        assertEquals(-0.5, a, 1e-9)
    }

    @Test
    fun spikeIsClamped() {
        sample(0, 0.0)
        assertEquals(AccelerationTracker.MAX_ACCELERATION, sample(1000, 10.0), 1e-9)
    }

    @Test
    fun singleSampleDropoutIsIgnored() {
        // a one-second drop in the reading used to read as hard braking and then a sprint
        for (i in 0..5) sample(i * 1000L, 4.0)
        assertEquals(0.0, sample(6000, 2.2), 1e-9)
        assertEquals(0.0, sample(7000, 4.0), 1e-9)
        assertEquals(0.0, sample(8000, 4.0), 1e-9)
    }

    @Test
    fun longGapResetsWindow() {
        sample(0, 0.0)
        sample(1000, 2.0)
        // After a pause the old samples must not produce a slope
        assertEquals(0.0, sample(10_000, 8.0), 1e-9)
    }

    @Test
    fun oldSamplesLeaveTheWindow() {
        // Hard acceleration, then steady: once the ramp is older than the window the slope is 0
        sample(0, 0.0)
        sample(1000, 2.0)
        var a = 0.0
        for (i in 2..12) a = sample(i * 1000L, 4.0)
        assertEquals(0.0, a, 1e-9)
    }

    @Test
    fun coastWindowForgetsSooner() {
        val coast = AccelerationTracker(AccelerationTracker.COAST_WINDOW_MS) { clock }
        sample(0, 0.0, coast)
        sample(1000, 2.0, coast)
        var a = 0.0
        for (i in 2..6) a = sample(i * 1000L, 4.0, coast)
        assertEquals(0.0, a, 1e-9)
    }

    @Test
    fun resetClearsSamples() {
        sample(0, 0.0)
        sample(1000, 2.0)
        tracker.reset()
        assertEquals(0.0, sample(2000, 4.0), 1e-9)
    }
}
