package com.enderthor.kpower.vdevice

import android.os.SystemClock
import com.enderthor.kpower.data.CoastCalibration
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.GearCheck
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
    val headwind: Double,        // m/s
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

        if (config.autoCalibrate) {
            coastDetector.update(
                CoastDetector.Input(
                    configKey = config.calibrationKey,
                    useCadenceSensor = config.useCadenceSensor,
                    cadence = inputs.cadence,
                    isWheelSpeed = isWheelSpeed,
                    speed = speed,
                    meanSpeed = coastTracker.windowMeanSpeed,
                    acceleration = coastAcceleration,
                    slope = inputs.slope,
                    headwind = inputs.headwind,
                    elevation = inputs.elevation,
                    totalMass = totalMass,
                    cdA = cdA,
                    crrEff = crr * config.surface.factor,
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
            slope = inputs.slope,
            totalMass = totalMass,
            rollingResistanceCoefficient = crr,
            // A learned CdA replaces Cd × frontal area
            dragCoefficient = calibration?.cdA ?: config.dragCoefficient.toDoubleLocale(),
            speed = speed,
            elevation = inputs.elevation,
            windSpeed = inputs.headwind,
            powerLoss = config.powerLoss.toDoubleLocale() / 100,
            frontalArea = if (calibration != null) 1.0 else config.frontalArea.toDoubleLocale(),
            ftp = config.ftp.toDoubleLocale(),
            cadence = cadence,
            surface = config.surface.factor,
            // Without a cadence reading, power is always calculated
            isforcepower = !config.useCadenceSensor || !cadenceStreaming,
            acceleration = acceleration,
            minPower = minPower,
        ).calculateCyclingWattage()
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

    private companion object {
        const val NO_READING_YET = "\u0000"
    }
}
