package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.previewConfigData
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

    private companion object {
        const val SENSOR = "11111-111-1"
    }
}
