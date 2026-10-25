package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClimbRateTrackerTest {

    private var clock = 0L
    private val tracker = ClimbRateTracker { clock }

    private fun at(s: Int, altitude: Double): Double? {
        clock = s * 1000L
        return tracker.update(altitude)
    }

    @Test
    fun nothingUntilTheWindowSpansThreeSeconds() {
        assertNull(at(0, 10.0))
        assertNull(at(1, 10.5))
        assertNull(at(2, 11.0))
        assertEquals(0.5, at(3, 11.5)!!, 1e-9)
    }

    @Test
    fun steadyClimbGivesItsRate() {
        var r: Double? = null
        for (s in 0..10) r = at(s, 20.0 + 0.4 * s)
        assertEquals(0.4, r!!, 1e-9)
    }

    @Test
    fun altitudeStepsAverageOut() {
        // 0.2 m altitude steps, as a ride file stores them, on a 0.1 m/s climb
        var r: Double? = null
        for (s in 0..12) r = at(s, Math.round((5.0 + 0.1 * s) / 0.2) * 0.2)
        assertEquals(0.1, r!!, 0.03)
    }

    @Test
    fun aGapStartsAgain() {
        for (s in 0..5) at(s, 10.0 + s)
        assertNull(at(12, 30.0))
    }

    @Test
    fun anImpossibleJumpStartsAgain() {
        for (s in 0..5) at(s, 30.0)
        // Placeholder 0 m while the stream was down: not a 30 m drop in a second
        assertNull(at(6, 0.0))
        assertNull(at(7, 30.0))
    }
}
