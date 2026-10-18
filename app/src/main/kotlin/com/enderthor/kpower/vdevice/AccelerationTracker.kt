package com.enderthor.kpower.vdevice

import android.os.SystemClock

/**
 * Estimates acceleration (m/s²) from speed samples taken at each power calculation.
 *
 * Uses the least-squares slope of speed over the last [windowMs] so 1 Hz quantization and
 * sensor jitter don't turn into power spikes. Each speed is first replaced by the median of the
 * last three, so a one-second dropout (e.g. 14 → 8 → 14 km/h) isn't read as braking and then
 * sprinting. Sample at every calculation (not only when speed changes): the speed stream drops
 * repeated values, and a constant speed must give a slope of 0.
 */
class AccelerationTracker(
    private val windowMs: Long = POWER_WINDOW_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {

    private val raw = ArrayDeque<Double>()
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** Mean speed over the window: the speed that matches the returned slope (used by coast calibration) */
    var windowMeanSpeed = 0.0
        private set

    fun update(speedMs: Double): Double {
        val t = now()
        if (samples.isNotEmpty() && t - samples.last().first > MAX_GAP_MS) reset()
        raw.addLast(speedMs)
        if (raw.size > MEDIAN_OF) raw.removeFirst()
        val filtered = if (raw.size < MEDIAN_OF) speedMs else raw.sorted()[MEDIAN_OF / 2]

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

    fun reset() {
        raw.clear()
        samples.clear()
        windowMeanSpeed = 0.0
    }

    companion object {
        // Long enough to average out speed jitter on the power reading (on a test ride:
        // far fewer 0 W readings while pedalling and no spikes to the cap); spreads a sprint over ~4 s
        const val POWER_WINDOW_MS = 8000L
        // Coasting deceleration is smooth, and a short window keeps short coasts usable
        const val COAST_WINDOW_MS = 3000L
        const val MIN_SPAN_MS = 1000L
        const val MAX_GAP_MS = 5000L
        const val MEDIAN_OF = 3
        const val MAX_ACCELERATION = 1.5 // m/s², about the most a loaded bike can do; rejects sensor spikes
    }
}
