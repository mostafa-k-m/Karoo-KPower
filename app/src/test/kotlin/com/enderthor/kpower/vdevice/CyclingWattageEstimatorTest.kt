package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Test

class CyclingWattageEstimatorTest {

    private fun estimator(
        slope: Double = 0.0,
        speed: Double = 10.0,
        cadence: Double = 90.0,
        isforcepower: Boolean = false,
        acceleration: Double = 0.0,
        ftp: Double = 300.0,
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
        val accelerating = estimator(speed = 5.0, acceleration = 1.0).calculateCyclingWattage()
        val expectedExtra = (100.0 + CyclingWattageEstimator.ROTATING_MASS_KG) * 1.0 * 5.0
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
    fun capStillApplies() {
        // ftp 200 → cap is max(1.7*200, 790) = 790
        assertEquals(790.0, estimator(speed = 25.0, ftp = 200.0).calculateCyclingWattage(), 0.0)
    }
}
