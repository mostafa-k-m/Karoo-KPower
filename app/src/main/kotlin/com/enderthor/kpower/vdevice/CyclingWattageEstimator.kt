package com.enderthor.kpower.vdevice

import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.pow

import kotlin.math.exp
import kotlin.math.tanh


class CyclingWattageEstimator(
    private val gravity: Double = GRAVITY,
    private val slope:  Double,
    private val totalMass: Double,
    private val rollingResistanceCoefficient: Double,
    private val dragCoefficient: Double,
    private val frontalArea: Double,
    private val speed:  Double,
    private val windSpeed: Double,
    private val powerLoss: Double,
    private val elevation: Double,
    private val ftp: Double,
    private val cadence: Double,
    private val surface: Double,
    private val isforcepower: Boolean,
    private val acceleration: Double = 0.0,
    // While a gear is driving the wheel the rider is pushing, so the reading shouldn't drop to 0 W
    private val minPower: Double = 0.0,
    // Weather: air temperature and pressure reduced to sea level; null = the standard atmosphere
    private val airTemperatureC: Double? = null,
    private val seaLevelPressurePa: Double? = null,
) {

    fun smoothPower(estimatedPower: Double): Double {
   // Timber.d("Is force power: $isforcepower")
        if (!isforcepower && cadence < 22) return 0.0

        // Upstream capped at max(factor × FTP, 790 W) with a factor that only mattered above 400 W
        // (1.7), so that ceiling is kept; instead of flat-topping, readings bend towards it
        val ceiling = maxOf(1.7 * ftp, 790.0)
        val capped = if (estimatedPower > 0.0) softCap(estimatedPower, ceiling) else estimatedPower
        // While a gear drives the wheel, low and negative estimates bend towards the floor rather
        // than stopping at it; otherwise negative estimates read 0 W like a meter on a freewheel
        val floor = minPower.coerceIn(0.0, SOFT_CAP_KNEE * ceiling / SOFT_FLOOR_KNEE)
        return if (floor > 0.0) softFloor(capped, floor) else maxOf(capped, 0.0)
    }

    fun calculateCyclingWattage(): Double {
        val slopeAngle = atan(slope)

        val gravityForce = calculateGravityForce(slopeAngle)
        val rollingResistanceForce = calculateRollingResistanceForce(slopeAngle)
        val aerodynamicDragForce = calculateAerodynamicDragForce()
        val accelerationForce = calculateAccelerationForce()
        val estimatedPower = ((gravityForce + rollingResistanceForce + aerodynamicDragForce + accelerationForce + calculateDynamicRollingResistanceForce(slopeAngle)) * speed * (1 - powerLoss).pow(-1))

        //Timber.d("Force cycling calculation: estimatedPower is $estimatedPower, gravityForce is $gravityForce, rollingResistance is $rollingResistanceForce,aerodynamicDrag is $aerodynamicDragForce")

        return smoothPower(estimatedPower)
    }

    // Rotating wheels and tyres need extra energy to spin up; treated as added mass
    private fun calculateAccelerationForce(): Double {
        return (totalMass + ROTATING_MASS_KG) * acceleration
    }

    private fun calculateDynamicRollingResistanceForce(slopeAngle: Double): Double {
        return DYNAMIC_ROLLING_FORCE_N * cos(slopeAngle)
    }
    private fun calculateGravityForce(slopeAngle: Double): Double {
        return gravity * sin(slopeAngle) * totalMass
    }

    private fun calculateRollingResistanceForce(slopeAngle: Double): Double {
        return gravity * cos(slopeAngle) * totalMass * rollingResistanceCoefficient * surface
    }

    private fun calculateAerodynamicDragForce(): Double {
        return (0.5 * dragCoefficient * frontalArea * airDensity(elevation, airTemperatureC, seaLevelPressurePa) * (speed + windSpeed).pow(2))   // windspeed is positive because it is a headwind
    }

    companion object {
        const val ROTATING_MASS_KG = 2.0
        const val GRAVITY = 9.80665
        const val DYNAMIC_ROLLING_FORCE_N = 0.1

        private const val R_AIR = 287.05 // J/(kg·K), dry air

        /**
         * Air density (kg/m³) at [elevation] m. With the weather's temperature and sea-level pressure it
         * is the ideal gas law, with the pressure brought down to the rider's altitude for that
         * temperature, so a climb keeps its thinner air (a station pressure from one weather grid point
         * would hold for the whole ride). Without them, the standard atmosphere at 15 °C as before.
         * Cold air is denser: about 10% more drag at -10 °C than at +15 °C.
         */
        fun airDensity(elevation: Double, temperatureC: Double? = null, seaLevelPressurePa: Double? = null): Double {
            if (temperatureC == null && seaLevelPressurePa == null) return 1.225 * exp(-0.00011856 * elevation)
            val tempK = (temperatureC ?: 15.0) + 273.15
            val pressure = (seaLevelPressurePa ?: 101_325.0) * exp(-GRAVITY * elevation / (R_AIR * tempK))
            return pressure / (R_AIR * tempK)
        }

        const val SOFT_CAP_KNEE = 0.6

        /**
         * Unchanged up to [SOFT_CAP_KNEE] × [ceiling], then bends smoothly (no corner) towards the
         * ceiling without reaching it, so a larger estimate still reads a little higher instead of
         * every glitch showing the same flat maximum.
         */
        fun softCap(power: Double, ceiling: Double): Double {
            val knee = SOFT_CAP_KNEE * ceiling
            if (power <= knee) return power
            val room = ceiling - knee
            return knee + room * tanh((power - knee) / room)
        }

        const val SOFT_FLOOR_KNEE = 2.0

        /**
         * The mirror of [softCap] at the bottom: unchanged down to [SOFT_FLOOR_KNEE] × [floor], then
         * bends smoothly towards the floor without reaching it, so a lower (or more negative)
         * estimate still reads a little lower instead of sitting on a flat minimum.
         */
        fun softFloor(power: Double, floor: Double): Double {
            val knee = SOFT_FLOOR_KNEE * floor
            if (power >= knee) return power
            val room = knee - floor
            return floor + room * exp((power - knee) / room)
        }
    }
}
