package com.enderthor.kpower.vdevice

import android.os.SystemClock
import kotlin.math.abs

/**
 * Rate of climb (m/s) from the altitude readings: the least-squares slope over the last
 * [windowMs]. Gravity worked out from it adds up to exactly m·g·height over a climb, however late
 * the altitude reacts, whereas the Karoo's grade × the current speed loses the start of a climb
 * entered fast (grade still low while speed, and so the power it costs, is high).
 *
 * Returns null until the window spans [MIN_SPAN_MS]. A gap of more than [MAX_GAP_MS] or a jump no
 * bike can make (a placeholder 0 m while the altitude stream is down) starts a new window.
 */
class ClimbRateTracker(
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {

    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun update(altitude: Double): Double? {
        val t = now()
        samples.lastOrNull()?.let { (lastT, lastAlt) ->
            val dt = (t - lastT) / 1000.0
            if (t - lastT > MAX_GAP_MS || abs(altitude - lastAlt) > MAX_RATE * maxOf(dt, 1.0)) samples.clear()
        }
        samples.addLast(t to altitude)
        while (t - samples.first().first > windowMs) samples.removeFirst()
        if (samples.size < 3 || t - samples.first().first < MIN_SPAN_MS) return null

        val t0 = samples.first().first
        val mx = samples.sumOf { (it.first - t0) / 1000.0 } / samples.size
        val my = samples.sumOf { it.second } / samples.size
        var num = 0.0
        var den = 0.0
        for ((ts, alt) in samples) {
            val dx = (ts - t0) / 1000.0 - mx
            num += dx * (alt - my)
            den += dx * dx
        }
        return if (den == 0.0) null else num / den
    }

    fun reset() = samples.clear()

    companion object {
        // Of 4–12 s, closest to an energy check on replayed climbs
        const val WINDOW_MS = 6000L
        const val MIN_SPAN_MS = 3000L
        const val MAX_GAP_MS = 5000L
        const val MAX_RATE = 3.0 // m/s vertical; faster is a bad reading, not a bike
    }
}
