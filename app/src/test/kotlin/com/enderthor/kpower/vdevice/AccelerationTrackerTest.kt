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
        sample(0, 5.0)
        assertEquals(AccelerationTracker.MAX_ACCELERATION, sample(1000, 15.0), 1e-9)
    }

    @Test
    fun movingOffAssumesABriskStart() {
        // Dense readings while stopped, then the sensor jumps to 3.2 m/s (11.6 km/h)
        for (i in 0..19) sample(i * 1000L, 0.0)
        sample(20_000, 3.22)
        assertEquals(AccelerationTracker.START_ACCELERATION, sample(21_000, 3.19), 0.05)
    }

    @Test
    fun movingOffAfterSparseReadingsAssumesABriskStart() {
        // Standing still with no new readings for 8 s: the window has forgotten the stop
        sample(0, 0.0)
        sample(1000, 0.0)
        assertEquals(AccelerationTracker.START_ACCELERATION, sample(9000, 3.22), 0.05)
    }

    @Test
    fun movingOffAfterTheSourceSwitchedStillCountsAsAStart() {
        sample(0, 0.0)
        tracker.reset() // the Karoo switched to GPS speed and back
        assertEquals(AccelerationTracker.START_ACCELERATION, sample(1000, 3.22), 0.05)
    }

    @Test
    fun aDropoutIsNotAStandstill() {
        // The stream monitor's placeholder 0 while the stream is down, then the sensor is back
        // at riding speed: no start ramp, so no phantom 0 → 25 km/h acceleration
        for (i in 0..5) sample(i * 1000L, 6.9)
        tracker.interrupt()
        assertEquals(0.0, sample(7000, 6.9), 1e-9)
        assertEquals(0.0, sample(8000, 6.9), 1e-9)
    }

    @Test
    fun aFastReadingAfterAStopIsAGapNotAStart() {
        // Real stop, then 25 km/h: no sensor jump looks like that, so start a new window instead
        sample(0, 0.0)
        assertEquals(0.0, sample(9000, 6.9), 1e-9)
    }

    @Test
    fun singleSampleDropoutIsIgnored() {
        // 14 → 8 → 14 km/h for one second used to read as hard braking and then a sprint
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
