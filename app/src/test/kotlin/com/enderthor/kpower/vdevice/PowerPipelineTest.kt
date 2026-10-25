package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.previewConfigData
import com.enderthor.kpower.surface.RoadSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPipelineTest {

    private var clock = 0L
    private val pipeline = PowerPipeline { clock }
    private val setup = PowerSetup(
        config = previewConfigData[0].copy(bikeMass = "20", rollingResistanceCoefficient = "0.007", dragCoefficient = "0.9",
            frontalArea = "0.65", powerLoss = "5", ftp = "200", surface = KarooSurface.ASPHALT),
        riderMass = 80.0,
        speedSensorIds = setOf(SENSOR),
    )

    private fun reading(atS: Int, kmh: Double?, source: String? = SENSOR, cadence: Double? = 80.0): Double {
        clock = atS * 1000L
        return pipeline.update(PowerInputs(kmh?.let { it / 3.6 }, source, cadence, cadenceSourceId = "cad", slope = 0.0, elevation = 30.0, headwind = 0.0), setup)
    }

    @Test
    fun streamDropoutMidRideIsNotAStart() {
        val steady = (0..10).map { reading(it, 25.0) }.last()
        // While the stream is down the monitor sends 0 km/h with no source, then the sensor is back
        (11..13).forEach { reading(it, 0.0, source = null) }
        val after = (14..24).map { reading(it, 25.0) }
        assertTrue("after a dropout: $after vs steady $steady", after.all { it <= steady + 1.0 })
    }

    @Test
    fun movingOffFromAStopCountsAsABriskStart() {
        (0..19).forEach { reading(it, 0.0, cadence = 0.0) }
        val start = (20..29).map { reading(it, 12.0) }
        val steady = (30..45).map { reading(it, 12.0) }.last()
        // 0.7 m/s² for 100 kg at 3.2 m/s is ~240 W on top of the ~40 W to hold the speed
        assertTrue("start: $start", start.max() in 200.0..400.0)
        assertEquals(steady, start.last(), 150.0)
    }

    @Test
    fun movingOffAfterTheKarooSwitchedToGpsWhileStopped() {
        (0..9).forEach { reading(it, 0.0, cadence = 0.0) }
        (10..14).forEach { reading(it, 0.3, source = "DEVICE_LOCATION_ID", cadence = 0.0) }
        val start = (15..20).map { reading(it, 12.0) }
        assertTrue("start: $start", start.max() in 200.0..400.0)
    }

    @Test
    fun mapSurfaceReplacesTheConfigSurface() {
        val auto = setup.copy(config = setup.config.copy(autoSurface = true))
        fun steady(factor: Double?): Double {
            val p = PowerPipeline { clock }
            return (0..10).map {
                clock = it * 1000L
                p.update(PowerInputs(20 / 3.6, SENSOR, 80.0, "cad", 0.0, 30.0, 0.0, surfaceFactor = factor), auto)
            }.last()
        }
        val paved = steady(RoadSurface.PAVED.factor)
        val gravel = steady(RoadSurface.GRAVEL.factor)
        // No road on the map: the config's surface (Asphalt here) is used
        assertEquals(paved, steady(null), 1e-9)
        // Rolling resistance 100 kg × g × 0.007 × (1.45 − 0.75) ≈ 4.8 N more, × 5.6 m/s / 0.95
        assertEquals(28.3, gravel - paved, 0.5)
    }

    @Test
    fun coastingOffPavedRoadsIsNotLearned() {
        val auto = setup.copy(config = setup.config.copy(autoSurface = true, autoCalibrate = true, isforcepower = false))
        fun coast(factor: Double?): Int {
            val p = PowerPipeline { clock }
            // Pedal at 25 km/h, then freewheel and slow down for 15 s
            (0..9).forEach { clock = it * 1000L; p.update(PowerInputs(25 / 3.6, SENSOR, 80.0, "cad", 0.0, 30.0, 0.0, surfaceFactor = factor), auto) }
            (10..25).forEach { clock = it * 1000L; p.update(PowerInputs((25 - (it - 9) * 0.5) / 3.6, SENSOR, 0.0, "cad", 0.0, 30.0, 0.0, surfaceFactor = factor), auto) }
            // Pedalling again closes the segment
            (26..30).forEach { clock = it * 1000L; p.update(PowerInputs(18 / 3.6, SENSOR, 80.0, "cad", 0.0, 30.0, 0.0, surfaceFactor = factor), auto) }
            return p.coastDetector.sampleCount()
        }
        assertTrue(coast(RoadSurface.PAVED.factor) > 0)
        assertEquals(0, coast(RoadSurface.GRAVEL.factor))
        assertEquals(0, coast(null))
    }

    @Test
    fun descentsUseFourFifthsOfTheGrade() {
        // Altitude changing with the slope, as on a real road
        fun steady(slope: Double): Double {
            val p = PowerPipeline { clock }
            return (0..10).map { clock = it * 1000L; p.update(PowerInputs(25 / 3.6, SENSOR, 80.0, "cad", slope, 30.0 + slope * 25 / 3.6 * it, 0.0), setup) }.last()
        }
        fun physics(slope: Double) = CyclingWattageEstimator(slope = slope, totalMass = 100.0, rollingResistanceCoefficient = 0.007,
            dragCoefficient = 0.9, frontalArea = 0.65, speed = 25 / 3.6, windSpeed = 0.0, powerLoss = 0.05, elevation = 30.0,
            ftp = 200.0, cadence = 80.0, surface = KarooSurface.ASPHALT.factor, isforcepower = false).calculateCyclingWattage()
        // (to 0.05 W: air density follows the changing altitude)
        assertEquals(physics(-0.008), steady(-0.01), 0.05)
        // Climbs are unchanged
        assertEquals(physics(0.03), steady(0.03), 0.05)
    }

    @Test
    fun gravityFollowsTheAltitudeNotALaggingGrade() {
        // Climbing 4% at 18 km/h while the Karoo's grade still reads flat
        val v = 18 / 3.6
        fun run(elevationStreaming: Boolean): Double {
            val p = PowerPipeline { clock }
            return (0..8).map {
                clock = it * 1000L
                p.update(PowerInputs(v, SENSOR, 80.0, "cad", 0.0, 10.0 + 0.04 * v * it, 0.0, elevationStreaming = elevationStreaming), setup)
            }.last()
        }
        val flat = PowerPipeline { clock }.let { p -> (0..8).map { clock = it * 1000L; p.update(PowerInputs(v, SENSOR, 80.0, "cad", 0.0, 10.0, 0.0), setup) }.last() }
        // 100 kg × g × 0.2 m/s ≈ 196 W more at the wheel, / 0.95
        assertEquals(206.0, run(true) - flat, 3.0)
        // Without real altitude readings it falls back to the Karoo's grade
        assertEquals(flat, run(false), 0.05)
    }

    @Test
    fun weatherWindIsScaledToRiderHeight() {
        fun steady(windFactor: String?, headwind: Double): Double {
            val config = if (windFactor == null) setup.config else setup.config.copy(windFactor = windFactor)
            val p = PowerPipeline { clock }
            return (0..10).map { clock = it * 1000L; p.update(PowerInputs(20 / 3.6, SENSOR, 80.0, "cad", 0.0, 30.0, headwind), setup.copy(config = config)) }.last()
        }
        val calm = steady("1", 0.0)
        // Default 0.65: a 4 m/s forecast headwind counts as 2.6 m/s
        assertEquals(steady("1", 2.6), steady(null, 4.0), 1e-9)
        assertEquals(steady("1", 2.6), steady("0,65", 4.0), 1e-9)
        assertTrue(steady("1", 4.0) > steady(null, 4.0))
        assertEquals(calm, steady("0", 4.0), 1e-9)
        // Unreadable falls back to the default; out of range is clamped
        assertEquals(steady(null, 4.0), steady("abc", 4.0), 1e-9)
        assertEquals(steady("1.5", 4.0), steady("3", 4.0), 1e-9)
    }

    private companion object {
        const val SENSOR = "11111-111-1"
    }
}
