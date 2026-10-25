package com.enderthor.kpower.vdevice

import android.os.SystemClock
import com.enderthor.kpower.data.CoastCalibration
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.GearCheck
import com.enderthor.kpower.surface.RoadSurface
import com.enderthor.kpower.extension.toDoubleLocale
import timber.log.Timber

/** One reading of the Karoo streams, in SI units */
data class PowerInputs(
    val speed: Double?,          // m/s, null when the speed stream isn't streaming
    val speedSourceId: String?,  // null for the stream monitor's placeholder value
    val cadence: Double?,        // rpm, null when the cadence stream isn't streaming
    val cadenceSourceId: String? = null,
    val slope: Double,           // rise / run
    val elevation: Double,       // m
    val headwind: Double,        // m/s, from the weather (10 m height); scaled by the config's wind factor
    val surfaceFactor: Double? = null, // from the offline map; null = the config's surface
    val elevationStreaming: Boolean = true, // false while [elevation] is the stream monitor's placeholder
    val airTemperatureC: Double? = null,    // weather; null = standard atmosphere
    val seaLevelPressurePa: Double? = null, // weather, reduced to sea level
    val snowDepth: Double? = null,          // m on the ground, from the weather
)

/** The rider, bike and what has been learned about them */
data class PowerSetup(
    val config: ConfigData,
    val riderMass: Double,                         // in the Karoo's weight unit, like config.bikeMass
    val massFactor: Double = 1.0,                  // to kg
    val calibrations: Map<String, CoastCalibration> = emptyMap(),
    val speedSensorIds: Set<String> = emptySet(),
    val gearChecks: Map<String, GearCheck> = emptyMap(),
)

/**
 * Turns each reading into a power value: acceleration, coast calibration samples, gear
 * detection and the physics estimate. Plain Kotlin, so a recorded ride can be replayed through
 * exactly the code that runs on the Karoo.
 */
class PowerPipeline(now: () -> Long = SystemClock::elapsedRealtime) {

    private val accelerationTracker = AccelerationTracker(now = now)
    private val coastTracker = AccelerationTracker(AccelerationTracker.COAST_WINDOW_MS, now)
    private val climbRateTracker = ClimbRateTracker(now = now)
    val coastDetector = CoastDetector(now)

    @Volatile private var gearDetector: GearDetector? = null
    @Volatile private var gearDetectorConfig: Pair<Int, List<Double>>? = null
    private var lastSpeedSourceId: String? = NO_READING_YET
    private var lastCadenceSourceId: String? = NO_READING_YET

    /** Whether a gear was driving the wheel at the last update */
    var drivingGear = false
        private set

    fun update(inputs: PowerInputs, setup: PowerSetup): Double {
        val config = setup.config
        val speed = inputs.speed ?: 0.0
        val isWheelSpeed = EstimatedPowerSource.isWheelSpeedSource(inputs.speedSourceId, setup.speedSensorIds)

        // The Karoo falls back to GPS speed while the wheel sensor is quiet (and back again); the
        // two disagree, so a slope across the switch would be a phantom acceleration
        if (inputs.speedSourceId != lastSpeedSourceId) {
            accelerationTracker.reset()
            coastTracker.reset()
        }
        if (inputs.speedSourceId != lastSpeedSourceId || inputs.cadenceSourceId != lastCadenceSourceId) {
            Timber.i("Sources: speed=${inputs.speedSourceId} cadence=${inputs.cadenceSourceId}")
        }
        lastSpeedSourceId = inputs.speedSourceId
        lastCadenceSourceId = inputs.cadenceSourceId

        val acceleration: Double
        val coastAcceleration: Double
        if (inputs.speed != null && inputs.speedSourceId != null) {
            acceleration = accelerationTracker.update(speed)
            coastAcceleration = coastTracker.update(speed)
        } else {
            // No real reading (stream dropped out): forget the window, and don't take the
            // placeholder 0 for a standstill, or the next real reading would look like a start
            accelerationTracker.interrupt()
            coastTracker.interrupt()
            acceleration = 0.0
            coastAcceleration = 0.0
        }

        val cadenceStreaming = inputs.cadence != null
        val cadence = inputs.cadence ?: 0.0
        val totalMass = (setup.riderMass + config.bikeMass.toDoubleLocale()) * setup.massFactor
        val calibration = if (config.autoCalibrate) setup.calibrations[config.calibrationKey] else null
        val cdA = calibration?.cdA ?: (config.dragCoefficient.toDoubleLocale() * config.frontalArea.toDoubleLocale())
        val crr = config.rollingResistanceCoefficient.toDoubleLocale()

        val climbRate = if (inputs.elevationStreaming) climbRateTracker.update(inputs.elevation) else { climbRateTracker.reset(); null }
        val slope = effectiveSlope(gravitySlope(inputs.slope, climbRate, speed))
        val headwind = inputs.headwind * config.windFactorValue
        val baseSurfaceFactor = inputs.surfaceFactor ?: config.surface.factor
        val snow = if (config.snowAdjust) snowFactor(inputs.snowDepth, baseSurfaceFactor) else 1.0
        val surfaceFactor = baseSurfaceFactor * snow
        // Snow changes rolling resistance from minute to minute, so coasting then isn't learned
        val coastingSurfaceOk = snow == 1.0 && (!config.autoSurface || inputs.surfaceFactor == RoadSurface.PAVED.factor)
        if (config.autoCalibrate) {
            coastDetector.update(
                CoastDetector.Input(
                    configKey = config.calibrationKey,
                    // Off paved roads the segment ends, as if pedalling
                    useCadenceSensor = config.useCadenceSensor && coastingSurfaceOk,
                    cadence = inputs.cadence,
                    isWheelSpeed = isWheelSpeed,
                    speed = speed,
                    meanSpeed = coastTracker.windowMeanSpeed,
                    acceleration = coastAcceleration,
                    slope = slope,
                    headwind = headwind,
                    elevation = inputs.elevation,
                    airTemperatureC = inputs.airTemperatureC,
                    seaLevelPressurePa = inputs.seaLevelPressurePa,
                    totalMass = totalMass,
                    cdA = cdA,
                    crrEff = crr * config.coastSurfaceFactor,
                )
            )
        }

        // Pedals driving the wheel through one of the entered gears: the rider is pushing, so a
        // 0 W estimate (grade noise on a descent) is wrong and the reading keeps a soft floor
        val ratios = config.gearRatios()
        drivingGear = ratios.isNotEmpty() && config.useCadenceSensor && cadenceStreaming &&
            gearDetectorFor(config.id, ratios, setup.gearChecks).update(speed, cadence, isWheelSpeed)
        val minPower = if (drivingGear) config.pedallingFloor.toDoubleLocale() else 0.0

        return CyclingWattageEstimator(
            slope = slope,
            totalMass = totalMass,
            rollingResistanceCoefficient = crr,
            // A learned CdA replaces Cd × frontal area
            dragCoefficient = calibration?.cdA ?: config.dragCoefficient.toDoubleLocale(),
            speed = speed,
            elevation = inputs.elevation,
            windSpeed = headwind,
            powerLoss = config.powerLoss.toDoubleLocale() / 100,
            frontalArea = if (calibration != null) 1.0 else config.frontalArea.toDoubleLocale(),
            ftp = config.ftp.toDoubleLocale(),
            cadence = cadence,
            surface = surfaceFactor,
            // Without a cadence reading, power is always calculated
            isforcepower = !config.useCadenceSensor || !cadenceStreaming,
            acceleration = acceleration,
            minPower = minPower,
            airTemperatureC = inputs.airTemperatureC,
            seaLevelPressurePa = inputs.seaLevelPressurePa,
        ).calculateCyclingWattage()
    }

    companion object {
        /**
         * A descent's gravity term is large next to the rider's power, and the altitude behind it lags
         * and is noisy: replayed rides often read 0 W while pedalling on descents. Taking 80%
         * of a negative slope matched a lag-free grade best; climbs are left as they are. Kept with
         * gravity from the climb rate, where an energy check on descents can't see braking.
         */
        const val NEGATIVE_GRADE_FACTOR = 0.8

        fun effectiveSlope(slope: Double) = if (slope < 0) slope * NEGATIVE_GRADE_FACTOR else slope

        /**
         * The slope gravity is worked out from: rate of climb / speed, so gravity's power is m·g·climb
         * rate. The Karoo's grade is used until the altitude window has filled and when barely moving.
         */
        fun gravitySlope(karooSlope: Double, climbRate: Double?, speed: Double): Double =
            if (climbRate == null || speed < MIN_CLIMB_RATE_SPEED) karooSlope
            else (climbRate / speed).coerceIn(-MAX_SLOPE, MAX_SLOPE)

        /**
         * Extra rolling resistance with snow on the ground. The weather's snow depth is for open ground:
         * cleared roads can be bare under it, unpaved paths usually aren't cleared. So paved surfaces
         * (factor up to [PAVED_MAX_FACTOR]) get a fixed [SNOW_PAVED] for leftover snow and slush, unpaved
         * ones rise with depth up to (1 + [SNOW_UNPAVED_MAX]) at [SNOW_FULL_DEPTH]. Soft ground (sand,
         * grass) is already slow and is left alone. Packed snow rolls roughly 2–3× slower than asphalt and
         * loose snow more; these are cautious starting values, to tune on winter rides.
         */
        fun snowFactor(snowDepth: Double?, surfaceFactor: Double): Double = when {
            snowDepth == null || snowDepth < SNOW_MIN_DEPTH -> 1.0
            surfaceFactor >= SOFT_MIN_FACTOR -> 1.0
            surfaceFactor <= PAVED_MAX_FACTOR -> SNOW_PAVED
            else -> 1.0 + SNOW_UNPAVED_MAX * minOf(snowDepth, SNOW_FULL_DEPTH) / SNOW_FULL_DEPTH
        }

        const val SNOW_MIN_DEPTH = 0.01   // m
        const val SNOW_FULL_DEPTH = 0.10  // m
        const val SNOW_PAVED = 1.25
        const val SNOW_UNPAVED_MAX = 1.5
        const val PAVED_MAX_FACTOR = 0.9
        const val SOFT_MIN_FACTOR = 2.0

        const val MIN_CLIMB_RATE_SPEED = 1.0 // m/s
        const val MAX_SLOPE = 0.15

        private const val NO_READING_YET = "\u0000"
    }

    /** What the gear detector saw since the last call, with the config it belongs to */
    fun takeGearCheck(): Pair<Int, GearCheck>? {
        val configId = gearDetectorConfig?.first ?: return null
        val check = gearDetector?.takeCheck() ?: return null
        return configId to check
    }

    private fun gearDetectorFor(configId: Int, ratios: List<Double>, gearChecks: Map<String, GearCheck>): GearDetector {
        val existing = gearDetector
        if (existing != null && gearDetectorConfig == configId to ratios) return existing
        val circumference = gearChecks[configId.toString()]?.circumference ?: GearDetector.DEFAULT_CIRCUMFERENCE
        Timber.i("Gear detector: ${ratios.size} gears, wheel $circumference m")
        return GearDetector(ratios, circumference).also {
            gearDetector = it
            gearDetectorConfig = configId to ratios
        }
    }
}
