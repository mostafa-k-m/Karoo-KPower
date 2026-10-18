package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclingWattageEstimatorTest {

    private fun estimator(
        slope: Double = 0.0,
        speed: Double = 10.0,
        cadence: Double = 90.0,
        isforcepower: Boolean = false,
        acceleration: Double = 0.0,
        ftp: Double = 300.0,
        minPower: Double = 0.0,
    ) = CyclingWattageEstimator(
        slope = slope,
        totalMass = 100.0,
        rollingResistanceCoefficient = 0.005,
        dragCoefficient = 1.0,
        frontalArea = 0.5,
        speed = speed,
        windSpeed = 0.0,
        powerLoss = 0.0,
        elevation = 0.0,
        ftp = ftp,
        cadence = cadence,
        surface = 1.0,
        isforcepower = isforcepower,
        acceleration = acceleration,
        minPower = minPower,
    )

    @Test
    fun flatSteadyRideMatchesHandCalculation() {
        // rolling 9.80665*100*0.005 = 4.903, drag 0.5*1*0.5*1.225*10² = 30.625, dynamic 0.1
        val expected = (4.903325 + 30.625 + 0.1) * 10.0
        assertEquals(expected, estimator().calculateCyclingWattage(), 1e-6)
    }

    @Test
    fun accelerationAddsKineticPower() {
        val steady = estimator(speed = 5.0).calculateCyclingWattage()
        // 0.5 m/s² keeps the total below the soft cap's knee
        val accelerating = estimator(speed = 5.0, acceleration = 0.5).calculateCyclingWattage()
        val expectedExtra = (100.0 + CyclingWattageEstimator.ROTATING_MASS_KG) * 0.5 * 5.0
        assertEquals(expectedExtra, accelerating - steady, 1e-6)
    }

    @Test
    fun coastingDecelerationCancelsResistance() {
        // Slowing down by exactly the resistance forces means the rider adds nothing
        val resistance = 4.903325 + 30.625 + 0.1
        val decel = -resistance / (100.0 + CyclingWattageEstimator.ROTATING_MASS_KG)
        val power = estimator(acceleration = decel, isforcepower = true).calculateCyclingWattage()
        assertEquals(0.0, power, 1e-6)
    }

    @Test
    fun descentWhilePedallingIsClampedToZero() {
        assertEquals(0.0, estimator(slope = -0.05).calculateCyclingWattage(), 0.0)
    }

    @Test
    fun hardBrakingIsClampedToZero() {
        assertEquals(0.0, estimator(acceleration = -2.5).calculateCyclingWattage(), 0.0)
    }

    @Test
    fun lowCadenceGivesZeroWhenUsingCadenceSensor() {
        assertEquals(0.0, estimator(cadence = 10.0).calculateCyclingWattage(), 0.0)
    }

    @Test
    fun lowCadenceIgnoredWithoutCadenceSensor() {
        val power = estimator(cadence = 0.0, isforcepower = true).calculateCyclingWattage()
        assertEquals(estimator().calculateCyclingWattage(), power, 1e-6)
    }

    @Test
    fun floorWhileAGearDrivesTheWheel() {
        // A steep descent's very negative estimate reads just above the floor, never below it
        val descent = estimator(slope = -0.05, minPower = 30.0).calculateCyclingWattage()
        assertTrue(descent > 30.0 && descent < 30.5)
        // Above the floor nothing changes, and below 22 rpm it's still 0 W
        assertEquals(estimator().calculateCyclingWattage(), estimator(minPower = 30.0).calculateCyclingWattage(), 1e-9)
        assertEquals(0.0, estimator(cadence = 10.0, minPower = 30.0).calculateCyclingWattage(), 0.0)
    }

    @Test
    fun restartAfterAStopStaysPlausible() {
        // The wheel sensor reads 0 at a standstill, then jumps straight to
        // 11.6 km/h (it can't measure very slow turning). With a 3 s window this read 790 W.
        var clock = 0L
        val tracker = AccelerationTracker { clock }
        val kmh = List(20) { 0.0 } + listOf(11.6, 11.5, 12.1, 12.4, 12.2, 12.6, 13.0, 13.4, 13.8, 14.0)
        val power = kmh.mapIndexed { i, v ->
            clock = i * 1000L
            val a = tracker.update(v / 3.6)
            CyclingWattageEstimator(
                slope = 0.0, totalMass = 120.0, rollingResistanceCoefficient = 0.007, dragCoefficient = 0.9,
                frontalArea = 0.65, speed = v / 3.6, windSpeed = 0.0, powerLoss = 0.06, elevation = 30.0,
                ftp = 200.0, cadence = 70.0, surface = 0.75, isforcepower = false, acceleration = a,
            ).calculateCyclingWattage()
        }
        println("restart: " + power.drop(19).joinToString { "%.0f".format(it) })
        // Getting 122 kg to 11.6 km/h takes ~640 J; over a few seconds that's a few hundred watts
        assertTrue(power.max() < 400.0)
    }

    @Test
    fun softFloorBendsTowardsTheFloor() {
        // Unchanged from the knee (2 × 30 W) up, continuous there, then approaching 30 W from above
        assertEquals(80.0, CyclingWattageEstimator.softFloor(80.0, 30.0), 0.0)
        assertEquals(60.0, CyclingWattageEstimator.softFloor(60.0 - 1e-9, 30.0), 1e-6)
        val curve = listOf(-500.0, -100.0, -20.0, 0.0, 20.0, 40.0, 59.0).map { CyclingWattageEstimator.softFloor(it, 30.0) }
        assertTrue(curve.zipWithNext().all { (a, b) -> b > a })
        assertTrue(curve.all { it > 30.0 })
        assertEquals(30.0, curve.first(), 1e-6)
    }

    @Test
    fun softCapBendsTowardsTheCeiling() {
        // ftp 200 → ceiling max(1.7*200, 790) = 790; a 25 m/s estimate (several kW) stays just below it
        val huge = estimator(speed = 25.0, ftp = 200.0).calculateCyclingWattage()
        assertTrue(huge < 790.0 && huge > 785.0)
        // Unchanged up to the knee (0.6 × 790 = 474 W)
        assertEquals(400.0, CyclingWattageEstimator.softCap(400.0, 790.0), 0.0)
        // Keeps rising, so bigger estimates still read higher
        val curve = listOf(500.0, 600.0, 800.0, 1000.0, 1500.0).map { CyclingWattageEstimator.softCap(it, 790.0) }
        assertEquals(curve.sorted(), curve)
        assertTrue(curve.zipWithNext().all { (a, b) -> b > a })
        // A strong rider's ceiling follows FTP
        assertEquals(400.0 * 1.7, CyclingWattageEstimator.softCap(1e6, 400.0 * 1.7), 1e-6)
    }
}
