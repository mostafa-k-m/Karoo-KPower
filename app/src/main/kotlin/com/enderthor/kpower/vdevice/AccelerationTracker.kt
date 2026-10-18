package com.enderthor.kpower.vdevice

import android.os.SystemClock

/**
 * Estimates acceleration (m/s²) from speed samples taken at each power calculation.
 *
 * Uses the least-squares slope of speed over the last [windowMs] so 1 Hz quantization and
 * sensor jitter don't turn into power spikes. Each speed is first replaced by the median of the
 * last three, so a one-second dropout isn't read as braking and then
 * sprinting. Sample at every calculation (not only when speed changes): the speed stream drops
 * repeated values, and a constant speed must give a slope of 0.
 *
 * A wheel sensor reads 0 until the wheel turns fast enough, then jumps to the riding speed
 * (0 → 12 km/h in one second at a junction), and while standing still the readings can be
 * too sparse for the window to remember the stop. So when the bike moves off from a standstill,
 * the seconds the sensor couldn't see are filled with a [START_ACCELERATION] ramp from zero.
 * Only for speeds such a jump produces (up to [MAX_START_SPEED]): anything faster after a stop
 * is a gap in the data, not a start, and just starts a new window.
 */
class AccelerationTracker(
    private val windowMs: Long = POWER_WINDOW_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {

    private val raw = ArrayDeque<Double>()
    private val samples = ArrayDeque<Pair<Long, Double>>()
    // Survives reset(): the Karoo may switch to GPS speed while stopped and back on moving off
    private var standingStill = false

    /** Mean speed over the window: the speed that matches the returned slope (used by coast calibration) */
    var windowMeanSpeed = 0.0
        private set

    fun update(speedMs: Double): Double {
        val t = now()
        if (samples.isNotEmpty() && t - samples.last().first > MAX_GAP_MS) reset()
        raw.addLast(speedMs)
        if (raw.size > MEDIAN_OF) raw.removeFirst()
        val filtered = if (raw.size < MEDIAN_OF) speedMs else raw.sorted()[MEDIAN_OF / 2]

        if (standingStill && filtered >= MOVING_SPEED) {
            samples.clear()
            if (filtered <= MAX_START_SPEED) addStartRamp(t, filtered)
        }
        standingStill = filtered < STANDSTILL_SPEED

        samples.addLast(t to filtered)
        while (t - samples.first().first > windowMs) samples.removeFirst()
        windowMeanSpeed = samples.sumOf { it.second } / samples.size

        if (samples.size < 2 || t - samples.first().first < MIN_SPAN_MS) return 0.0

        val t0 = samples.first().first
        val n = samples.size
        val meanX = samples.sumOf { (it.first - t0) / 1000.0 } / n
        val meanY = windowMeanSpeed
        var num = 0.0
        var den = 0.0
        for ((ts, v) in samples) {
            val dx = (ts - t0) / 1000.0 - meanX
            num += dx * (v - meanY)
            den += dx * dx
        }
        if (den == 0.0) return 0.0
        return (num / den).coerceIn(-MAX_ACCELERATION, MAX_ACCELERATION)
    }

    /** Fills the seconds before [t] with a [START_ACCELERATION] ramp from zero up to [speed] */
    private fun addStartRamp(t: Long, speed: Double) {
        var back = 1
        while (true) {
            val v = speed - START_ACCELERATION * back
            val at = t - back * 1000L
            if (v < 0.0 || t - at > windowMs) return
            samples.addFirst(at to v)
            back++
        }
    }

    /** Forgets the window, e.g. when the speed source switches; a standstill is still remembered */
    fun reset() {
        raw.clear()
        samples.clear()
        windowMeanSpeed = 0.0
    }

    /** Forgets everything: the readings stopped, so whether the bike was standing still is unknown */
    fun interrupt() {
        reset()
        standingStill = false
    }

    companion object {
        // Long enough to average out speed jitter on the power reading (on replayed rides:
        // far fewer 0 W readings while pedalling and no spikes to the cap); spreads a sprint over ~4 s
        const val POWER_WINDOW_MS = 8000L
        // Coasting deceleration is smooth, and a short window keeps short coasts usable
        const val COAST_WINDOW_MS = 3000L
        const val MIN_SPAN_MS = 1000L
        const val MAX_GAP_MS = 5000L
        const val MEDIAN_OF = 3
        const val MAX_ACCELERATION = 1.5 // m/s², about the most a loaded bike can do; rejects sensor spikes
        const val START_ACCELERATION = 0.7 // m/s², a brisk start: 0 → 12 km/h in about 5 s
        const val STANDSTILL_SPEED = 0.5 // m/s
        const val MOVING_SPEED = 1.0 // m/s
        const val MAX_START_SPEED = 5.0 // m/s, 18 km/h
    }
}
