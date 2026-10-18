package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.GearCheck
import kotlin.math.abs
import kotlin.math.ln

/**
 * Tells whether the pedals are driving the wheel, from wheel speed and cadence.
 *
 * While a gear drives the wheel, the distance per crank turn (speed ÷ cadence) is the wheel
 * circumference times that gear's ratio. When the wheel runs ahead of the cranks (freewheeling
 * with the legs still turning) it is larger and keeps changing. A power meter reads clearly
 * above zero in the first case and close to zero in the second; the physics estimate can't
 * tell them apart on a descent, where grade noise often takes it to 0 W.
 *
 * The circumference is learned from steady pedalling rather than entered, because the Karoo
 * applies its own (auto) wheel size to the speed reading.
 */
class GearDetector(
    private val gearRatios: List<Double>,
    initialCircumference: Double = DEFAULT_CIRCUMFERENCE,
) {
    var circumference = initialCircumference.coerceIn(MIN_CIRCUMFERENCE, MAX_CIRCUMFERENCE)
        private set

    private val recent = ArrayDeque<Pair<Double, Double>>()
    private var matchedInARow = 0
    private var steadySamples = 0
    private var steadyMatches = 0

    /** Returns true while a gear has been driving the wheel for [MATCHES_NEEDED] updates in a row */
    @Synchronized
    fun update(speedMs: Double, cadence: Double, isWheelSpeed: Boolean): Boolean {
        if (gearRatios.isEmpty() || !isWheelSpeed || cadence < MIN_CADENCE || speedMs < MIN_SPEED) {
            recent.clear()
            matchedInARow = 0
            return false
        }
        recent.addLast(speedMs to cadence)
        if (recent.size > STEADY_UPDATES) recent.removeFirst()

        val perTurn = speedMs * 60.0 / cadence
        val ratio = gearRatios.minBy { abs(ln(perTurn / (circumference * it))) }
        val error = perTurn / (circumference * ratio) - 1.0
        // For matching, average over the last updates: the cadence sensor reports a little later than
        // the speed sensor, which matters while speed is changing
        val smoothedPerTurn = recent.sumOf { it.first } * 60.0 / recent.sumOf { it.second }
        val smoothedRatio = gearRatios.minBy { abs(ln(smoothedPerTurn / (circumference * it))) }
        val smoothedError = smoothedPerTurn / (circumference * smoothedRatio) - 1.0

        if (cadence >= MIN_LEARN_CADENCE && isSteady()) {
            steadySamples++
            if (abs(error) <= MATCH_TOLERANCE) steadyMatches++
            // Close to a gear: nudge the wheel size towards what this sample says
            if (abs(error) <= LEARN_TOLERANCE) {
                circumference = (circumference + LEARN_RATE * (perTurn / ratio - circumference))
                    .coerceIn(MIN_CIRCUMFERENCE, MAX_CIRCUMFERENCE)
            }
        }

        matchedInARow = if (abs(smoothedError) <= MATCH_TOLERANCE) matchedInARow + 1 else 0
        return matchedInARow >= MATCHES_NEEDED
    }

    /** What was seen since the last call, or null with too little steady pedalling to judge */
    @Synchronized
    fun takeCheck(): GearCheck? {
        val check = if (steadySamples >= MIN_CHECK_SAMPLES) {
            GearCheck(circumference, steadySamples, steadyMatches.toDouble() / steadySamples)
        } else null
        steadySamples = 0
        steadyMatches = 0
        return check
    }

    private fun isSteady(): Boolean {
        if (recent.size < STEADY_UPDATES) return false
        val speeds = recent.map { it.first }
        val cadences = recent.map { it.second }
        return speeds.max() <= speeds.min() * (1 + STEADY_SPREAD) && cadences.max() <= cadences.min() * (1 + STEADY_SPREAD)
    }

    companion object {
        const val DEFAULT_CIRCUMFERENCE = 2.15   // m, a 700C/29" touring tyre
        const val MIN_CIRCUMFERENCE = 1.5
        const val MAX_CIRCUMFERENCE = 2.6
        const val MIN_CADENCE = 30.0             // rpm; cadence sensors are coarse below this
        const val MIN_LEARN_CADENCE = 40.0
        const val MIN_SPEED = 2.0                // m/s
        // Internal hubs step 13–23%, so ±4% leaves clear gaps between gears; with a derailleur's
        // 6–10% steps nearly every ratio is near some gear and the check is much less selective.
        // 4% with averaging caught 63% of the near-zero readings while pedalling hard on a 28 km
        // replay (3% without averaging: 43%), while 99% of steady pedalling matched a gear
        const val MATCH_TOLERANCE = 0.04
        const val LEARN_TOLERANCE = 0.06
        const val LEARN_RATE = 0.05
        const val STEADY_UPDATES = 3
        const val STEADY_SPREAD = 0.03
        const val MATCHES_NEEDED = 2
        const val MIN_CHECK_SAMPLES = 120
        /** Below this share of steady pedalling in a gear, the entered gearing is probably wrong */
        const val GOOD_MATCH_RATE = 0.5
    }
}
