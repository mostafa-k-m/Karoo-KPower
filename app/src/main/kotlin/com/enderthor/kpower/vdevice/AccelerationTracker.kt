package com.enderthor.kpower.vdevice

import android.os.SystemClock

/**
 * Estimates acceleration (m/s²) from speed samples taken at each power calculation.
 *
 * Uses the least-squares slope of speed over the last [WINDOW_MS] so 1 Hz quantization and
 * sensor jitter don't turn into power spikes. Sample at every calculation (not only when speed
 * changes): the speed stream drops repeated values, and a constant speed must give a slope of 0.
 */
class AccelerationTracker(private val now: () -> Long = SystemClock::elapsedRealtime) {

    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** Mean speed over the window: the speed that matches the returned slope (used by coast calibration) */
    var windowMeanSpeed = 0.0
        private set

    fun update(speedMs: Double): Double {
        val t = now()
        if (samples.isNotEmpty() && t - samples.last().first > MAX_GAP_MS) samples.clear()
        samples.addLast(t to speedMs)
        while (t - samples.first().first > WINDOW_MS) samples.removeFirst()
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
        samples.clear()
        windowMeanSpeed = 0.0
    }

    companion object {
        const val WINDOW_MS = 3000L
        const val MIN_SPAN_MS = 1000L
        const val MAX_GAP_MS = 5000L
        const val MAX_ACCELERATION = 2.5 // m/s², rejects sensor spikes
    }
}
