package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.CoastCalibration
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.previewConfigData
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.SavedDevices
import com.enderthor.kpower.vdevice.CyclingWattageEstimator.Companion.DYNAMIC_ROLLING_FORCE_N
import com.enderthor.kpower.vdevice.CyclingWattageEstimator.Companion.GRAVITY
import com.enderthor.kpower.vdevice.CyclingWattageEstimator.Companion.ROTATING_MASS_KG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class CoastCalibrationTest {

    // Summer commuter: Cd 0.88 × A 0.62, Crr 0.0070 on asphalt (0.75)
    private val trueCdA = 0.88 * 0.62
    private val trueCrrEff = 0.0070 * 0.75
    private val mass = 118.0 + 26.5
    private val elevation = 30.0
    private val rho = CyclingWattageEstimator.airDensity(elevation)

    /** Acceleration while freewheeling, from the same model the estimator uses */
    private fun coastAcceleration(v: Double, slope: Double, wind: Double, cdA: Double = trueCdA, crrEff: Double = trueCrrEff): Double {
        val angle = atan(slope)
        val force = GRAVITY * sin(angle) * mass + GRAVITY * cos(angle) * mass * crrEff +
            DYNAMIC_ROLLING_FORCE_N * cos(angle) + 0.5 * rho * cdA * (v + wind).pow(2)
        return -force / (mass + ROTATING_MASS_KG)
    }

    private fun syntheticSamples(n: Int, random: Random, accelNoise: Double, slopeNoise: Double): List<CoastSample> =
        List(n) { i ->
            val v = random.nextDouble(3.0, 9.5)
            val slope = random.nextDouble(-0.03, 0.03)
            val wind = random.nextDouble(-3.0, 3.0)
            CoastSample(
                timeMs = i * 1000L,
                speed = v,
                acceleration = coastAcceleration(v, slope, wind) + random.nextGaussian() * accelNoise,
                slope = slope + random.nextGaussian() * slopeNoise,
                headwind = wind,
                airDensity = rho,
                totalMass = mass,
            )
        }

    private fun Random.nextGaussian(): Double {
        // Box–Muller
        val u1 = nextDouble(1e-12, 1.0)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    private fun assertWithin(expected: Double, actual: Double, fraction: Double) {
        assertTrue("expected $expected ± ${fraction * 100}%, got $actual", abs(actual - expected) <= fraction * expected)
    }

    @Test
    fun exactSamplesAreRecovered() {
        val fit = CoastFitter.fit(syntheticSamples(100, Random(1), 0.0, 0.0))
        assertNotNull(fit)
        assertEquals(trueCdA, fit!!.cdA, 1e-6)
        assertEquals(trueCrrEff, fit.crrEff, 1e-8)
    }

    @Test
    fun noisySamplesAreRecoveredWithinFivePercent() {
        val fit = CoastFitter.fit(syntheticSamples(600, Random(2), accelNoise = 0.015, slopeNoise = 0.001))!!
        assertWithin(trueCdA, fit.cdA, 0.05)
        assertWithin(trueCrrEff, fit.crrEff, 0.05)
    }

    @Test
    fun brakingOutliersAreRejected() {
        val random = Random(3)
        val clean = syntheticSamples(1500, random, accelNoise = 0.015, slopeNoise = 0.001)
        // 15% of samples with the brakes on: far more deceleration than the resistances explain
        val braking = clean.take(225).map { it.copy(acceleration = it.acceleration - random.nextDouble(0.3, 1.5)) }
        val fit = CoastFitter.fit(braking + clean.drop(225))!!
        assertWithin(trueCdA, fit.cdA, 0.05)
        assertWithin(trueCrrEff, fit.crrEff, 0.05)
        assertTrue(fit.samples <= 1500 - 225)
    }

    @Test
    fun tooFewSamplesGiveNoFit() {
        assertNull(CoastFitter.fit(syntheticSamples(CoastFitter.MIN_FIT_SAMPLES - 1, Random(4), 0.0, 0.0)))
    }

    @Test
    fun narrowSpeedRangeGivesNoFit() {
        // All at about the same speed: drag and rolling resistance can't be told apart
        val samples = syntheticSamples(100, Random(5), 0.0, 0.0).map { s ->
            val v = 6.0 + (s.speed - 6.0) * 0.1
            s.copy(speed = v, acceleration = coastAcceleration(v, s.slope, s.headwind))
        }
        assertNull(CoastFitter.fit(samples))
    }

    @Test
    fun blendStartsFromConfigAndMovesPartWay() {
        val fit = CoastFitter.Fit(cdA = 0.60, crrEff = 0.006, samples = 400)
        val result = CoastFitter.blend(null, fit, configCdA = 0.50, configCrr = 0.008, surface = 0.75)
        assertEquals(0.50 + 0.25 * (0.60 - 0.50), result.cdA, 1e-9)
        assertEquals((0.006 + 0.25 * (0.006 - 0.006)) / 0.75, result.crr, 1e-9)
        assertEquals(400, result.samples)
        assertEquals(1, result.rides)
    }

    @Test
    fun blendWeighsSmallRidesLess() {
        val fit = CoastFitter.Fit(cdA = 0.60, crrEff = 0.006, samples = 50)
        val current = CoastCalibration(cdA = 0.50, crr = 0.008, samples = 1000, rides = 4)
        val result = CoastFitter.blend(current, fit, configCdA = 0.9, configCrr = 0.01, surface = 0.75)
        assertEquals(0.50 + 0.25 * 0.25 * (0.60 - 0.50), result.cdA, 1e-9)
        assertEquals(1050, result.samples)
        assertEquals(5, result.rides)
    }

    @Test
    fun blendClampsToSaneRanges() {
        val fit = CoastFitter.Fit(cdA = 3.0, crrEff = 0.1, samples = 400)
        // The fit is clamped before blending, so one wild ride moves a quarter of the way to the limit
        val once = CoastFitter.blend(null, fit, configCdA = 0.9, configCrr = 0.019, surface = 1.0)
        assertEquals(0.9 + 0.25 * (1.0 - 0.9), once.cdA, 1e-9)
        assertEquals(0.019 + 0.25 * (0.02 - 0.019), once.crr, 1e-9)

        var cal: CoastCalibration? = null
        repeat(100) { cal = CoastFitter.blend(cal, fit, configCdA = 0.9, configCrr = 0.019, surface = 1.0) }
        assertTrue(cal!!.cdA <= 1.0 && cal!!.cdA > 0.9999)
        assertTrue(cal!!.crr <= 0.02 && cal!!.crr > 0.01999)
    }

    // --- Detector, fed like EstimatedPowerSource does at ~1 Hz ---

    private var clock = 0L
    private val tracker = AccelerationTracker { clock }
    private val detector = CoastDetector { clock }

    private var configKey = "0"

    private data class Conditions(
        val cadence: Double? = 0.0,
        val wheelSpeed: Boolean = true,
        val useCadenceSensor: Boolean = true,
        val slope: Double = 0.0,
        val wind: Double = 0.0,
    )

    private fun feed(v: Double, c: Conditions) {
        val a = tracker.update(v)
        detector.update(
            CoastDetector.Input(
                configKey = configKey,
                useCadenceSensor = c.useCadenceSensor,
                cadence = c.cadence,
                isWheelSpeed = c.wheelSpeed,
                speed = v,
                meanSpeed = tracker.windowMeanSpeed,
                acceleration = a,
                slope = c.slope,
                headwind = c.wind,
                elevation = elevation,
                totalMass = mass,
                cdA = 0.6,
                crrEff = 0.006,
            )
        )
    }

    /** Pedals at [startSpeed] for a while, then coasts for [seconds] (optionally braking at the end) */
    private fun ride(
        startSpeed: Double,
        seconds: Int,
        c: Conditions = Conditions(),
        brakeLastSeconds: Int = 0,
        windAt: (Int) -> Double = { c.wind },
    ) {
        var v = startSpeed
        repeat(5) {
            clock += 1000
            feed(v, c.copy(cadence = 80.0))
        }
        for (s in 1..seconds) {
            val braking = s > seconds - brakeLastSeconds
            val wind = windAt(s)
            repeat(100) {
                v = maxOf(0.0, v + (coastAcceleration(v, c.slope, wind) - if (braking) 1.5 else 0.0) * 0.01)
            }
            clock += 1000
            feed(v, c.copy(wind = wind))
        }
        clock += 1000
        feed(v, c.copy(cadence = 80.0))
    }

    @Test
    fun simulatedCoastsAreRecoveredWithinFivePercent() {
        val random = Random(7)
        repeat(40) {
            ride(
                startSpeed = random.nextDouble(7.0, 9.5),
                seconds = 25,
                c = Conditions(slope = random.nextDouble(-0.02, 0.02), wind = random.nextDouble(-2.0, 2.0)),
                // Some coasts end at a junction with the brakes on
                brakeLastSeconds = if (it % 3 == 0) 4 else 0,
            )
        }
        val batch = detector.drain()!!
        val fit = CoastFitter.fit(batch.samples)!!
        assertWithin(trueCdA, fit.cdA, 0.05)
        assertWithin(trueCrrEff, fit.crrEff, 0.05)
    }

    @Test
    fun warmupAndEndAreTrimmed() {
        ride(startSpeed = 9.0, seconds = 12)
        // 12 s coasting: first 3 s skipped, last 2 s dropped
        assertEquals(12 - 3 + 1 - 2, detector.sampleCount())
    }

    @Test
    fun shortCoastIsIgnored() {
        ride(startSpeed = 9.0, seconds = 5)
        assertEquals(0, detector.sampleCount())
    }

    @Test
    fun brakingEndsTheSegment() {
        ride(startSpeed = 9.0, seconds = 20, brakeLastSeconds = 6)
        val samples = detector.drain()!!.samples
        // The window before the braking is dropped, nothing after it is kept
        assertTrue(samples.size in 3..(20 - 6 - 3))
        samples.forEach { assertTrue(it.acceleration > coastAcceleration(it.speed, 0.0, 0.0) - 0.1) }
    }

    @Test
    fun pedallingIsNotCoasting() {
        ride(startSpeed = 9.0, seconds = 15, c = Conditions(cadence = 60.0))
        assertEquals(0, detector.sampleCount())
    }

    @Test
    fun needsCadenceStreamAndSetting() {
        ride(startSpeed = 9.0, seconds = 15, c = Conditions(cadence = null))
        ride(startSpeed = 9.0, seconds = 15, c = Conditions(useCadenceSensor = false))
        assertEquals(0, detector.sampleCount())
    }

    @Test
    fun gpsSpeedIsIgnored() {
        ride(startSpeed = 9.0, seconds = 15, c = Conditions(wheelSpeed = false))
        assertEquals(0, detector.sampleCount())
    }

    @Test
    fun steepGradeIsIgnored() {
        ride(startSpeed = 6.0, seconds = 15, c = Conditions(slope = 0.04))
        assertEquals(0, detector.sampleCount())
    }

    @Test
    fun gustyWindDiscardsSegment() {
        ride(startSpeed = 9.0, seconds = 15, windAt = { s -> if (s < 8) 0.0 else 3.0 })
        assertEquals(0, detector.sampleCount())
    }

    @Test
    fun drainHandsOverOnce() {
        ride(startSpeed = 9.0, seconds = 15)
        assertNotNull(detector.drain())
        assertNull(detector.drain())
    }

    @Test
    fun changedSetupDropsCollectedSamples() {
        ride(startSpeed = 9.0, seconds = 15)
        configKey = "1"
        ride(startSpeed = 9.0, seconds = 15)
        val batch = detector.drain()!!
        assertEquals("1", batch.configKey)
        assertEquals(15 - 3 + 1 - 2, batch.samples.size)
    }

    @Test
    fun calibrationKeyFollowsTheHandEnteredSetup() {
        val summer = previewConfigData[0].copy(rollingResistanceCoefficient = "0.0070", surface = KarooSurface.ASPHALT)
        assertEquals(summer.calibrationKey, summer.copy(rollingResistanceCoefficient = "0,007", ftp = "250", autoCalibrate = true).calibrationKey)
        assertTrue(summer.calibrationKey != summer.copy(rollingResistanceCoefficient = "0.017").calibrationKey)
        assertTrue(summer.calibrationKey != summer.copy(bikeMass = "31").calibrationKey)
        assertTrue(summer.calibrationKey != summer.copy(surface = KarooSurface.STANDARD).calibrationKey)
    }

    // Ids as logged on a Karoo 3 with ANT+ speed and cadence sensors
    private fun device(id: String, vararg types: String, enabled: Boolean = true) =
        SavedDevices.SavedDevice(id, "ANT_PLUS", id, enabled, SavedDevices.SavedDevice.DeviceDetail(null, null, null, null), emptyMap(), types.toList(), null)

    @Test
    fun wheelSpeedSource() {
        val ids = EstimatedPowerSource.speedSensorIds(
            listOf(
                device("kpower::estimated-power-2000", DataType.Source.POWER),
                device("91849-122-17", DataType.Source.CADENCE),
                device("721328-123-177", DataType.Source.SPEED, "TYPE_SPD_DISTANCE_DIFF_ID"),
                device("5555-1-1", DataType.Source.SPEED, enabled = false),
                device("trainer-1", DataType.Source.SPEED, DataType.Source.POWER),
            )
        )
        assertEquals(setOf("721328-123-177"), ids)
        assertTrue(EstimatedPowerSource.isWheelSpeedSource("721328-123-177", ids))
        assertTrue(!EstimatedPowerSource.isWheelSpeedSource("DEVICE_LOCATION_ID", ids))
        assertTrue(!EstimatedPowerSource.isWheelSpeedSource(null, ids))
        assertTrue(!EstimatedPowerSource.isWheelSpeedSource("721328-123-177", emptySet()))
    }
}
