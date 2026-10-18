package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.GearHub
import com.enderthor.kpower.data.previewConfigData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearDetectorTest {

    // A Nexus 8 hub with 42/20 gearing on a 700C wheel
    private val nexus = previewConfigData[0].copy(gearHub = GearHub.NEXUS_8, chainrings = "42", sprockets = "20")
    private val ratios = nexus.gearRatios()
    private val wheel = 2.17

    private fun speedFor(gear: Int, cadence: Double, wheelM: Double = wheel) = cadence / 60.0 * wheelM * ratios[gear]

    @Test
    fun gearRatiosFromTeethAndHub() {
        assertEquals(8, ratios.size)
        assertEquals(42.0 / 20 * 0.527, ratios.first(), 1e-9)
        assertEquals(42.0 / 20 * 1.615, ratios.last(), 1e-9)
    }

    @Test
    fun derailleurAndCustomGearing() {
        val road = previewConfigData[0].copy(chainrings = "34-50", sprockets = "11-13-15")
        assertEquals(listOf(34.0 / 15, 34.0 / 13, 34.0 / 11, 50.0 / 15, 50.0 / 13, 50.0 / 11), road.gearRatios())
        val threeSpeed = previewConfigData[0].copy(gearHub = GearHub.CUSTOM, chainrings = "40", sprockets = "20", customHubRatios = "0.75, 1 1.333")
        assertEquals(listOf(1.5, 2.0, 2.666), threeSpeed.gearRatios())
        assertTrue(previewConfigData[0].gearRatios().isEmpty())
        assertTrue(previewConfigData[0].copy(gearHub = GearHub.CUSTOM, chainrings = "40", sprockets = "20").gearRatios().isEmpty())
    }

    @Test
    fun drivingGearIsRecognisedAfterTwoUpdates() {
        val detector = GearDetector(ratios, initialCircumference = 2.15)
        assertFalse(detector.update(speedFor(4, 70.0), 70.0, isWheelSpeed = true))
        assertTrue(detector.update(speedFor(4, 70.0), 70.0, isWheelSpeed = true))
        // After a shift the averaged ratio needs a few updates to settle on the new gear
        repeat(3) { detector.update(speedFor(6, 65.0), 65.0, isWheelSpeed = true) }
        assertTrue(detector.update(speedFor(6, 65.0), 65.0, isWheelSpeed = true))
    }

    @Test
    fun wheelRunningAheadOfTheCranksIsNotDriving() {
        val detector = GearDetector(ratios, initialCircumference = wheel)
        // Halfway between gears 5 and 6: legs turning slower than the wheel needs
        val between = 70.0 / 60.0 * wheel * kotlin.math.sqrt(ratios[4] * ratios[5])
        repeat(5) { assertFalse(detector.update(between, 70.0, isWheelSpeed = true)) }
    }

    @Test
    fun gpsSpeedOrLowCadenceIsNotDriving() {
        val detector = GearDetector(ratios, initialCircumference = wheel)
        repeat(3) { assertFalse(detector.update(speedFor(4, 70.0), 70.0, isWheelSpeed = false)) }
        repeat(3) { assertFalse(detector.update(speedFor(4, 20.0), 20.0, isWheelSpeed = true)) }
        assertFalse(GearDetector(emptyList()).update(speedFor(4, 70.0), 70.0, isWheelSpeed = true))
    }

    @Test
    fun learnsTheWheelSizeFromSteadyPedalling() {
        val detector = GearDetector(ratios, initialCircumference = 2.10)
        for (gear in listOf(3, 4, 5, 6)) repeat(40) { detector.update(speedFor(gear, 72.0), 72.0, isWheelSpeed = true) }
        assertEquals(wheel, detector.circumference, wheel * 0.003)
    }

    @Test
    fun checkReportsHowWellRidingMatchesTheGearing() {
        val right = GearDetector(ratios, initialCircumference = wheel)
        repeat(150) { right.update(speedFor(4, 70.0), 70.0, isWheelSpeed = true) }
        assertEquals(1.0, right.takeCheck()!!.matchRate, 1e-9)
        assertNull(right.takeCheck()) // counters start over

        // Entered 42/24 but riding 42/20. (A wrong entry close to one hub step off, like 42/17,
        // can go unnoticed: learning the wheel size absorbs a 6% offset.)
        val wrong = GearDetector(previewConfigData[0].copy(gearHub = GearHub.NEXUS_8, chainrings = "42", sprockets = "24").gearRatios(), wheel)
        val between = 70.0 / 60.0 * wheel * kotlin.math.sqrt(ratios[4] * ratios[5])
        repeat(150) { wrong.update(between, 70.0, isWheelSpeed = true) }
        assertTrue(wrong.takeCheck()!!.matchRate < GearDetector.GOOD_MATCH_RATE)
    }
}
