package com.enderthor.kpower.surface

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** A road from the map: its surface and its shape as (latitude, longitude) points */
class MapWay(val surface: RoadSurface, val points: List<Pair<Double, Double>>)

/**
 * Decides which road the bike is on and keeps a steady surface for it.
 *
 * The nearest way within [MAX_DISTANCE_M] wins; ways nearly as close that run in the direction of
 * travel are preferred, so a path next to the road doesn't take over. A new surface is accepted
 * after [CONFIRM_SECONDS] matching readings (GPS wanders across junctions), and after
 * [LOST_MS] without any match the surface becomes [RoadSurface.UNKNOWN].
 */
class SurfaceMatcher {

    var surface = RoadSurface.UNKNOWN
        private set

    private var pending: RoadSurface? = null
    private var pendingCount = 0
    private var lastMatchMs = Long.MIN_VALUE

    /** [heading] in degrees clockwise from north, null when unknown */
    fun update(lat: Double, lon: Double, heading: Double?, ways: List<MapWay>, nowMs: Long): RoadSurface {
        val match = nearest(lat, lon, heading, ways)
        if (match == null) {
            if (lastMatchMs != Long.MIN_VALUE && nowMs - lastMatchMs > LOST_MS) {
                surface = RoadSurface.UNKNOWN
                pending = null
            }
            return surface
        }
        lastMatchMs = nowMs
        when {
            match == surface -> pending = null
            surface == RoadSurface.UNKNOWN -> { surface = match; pending = null }
            match == pending -> if (++pendingCount >= CONFIRM_SECONDS) { surface = match; pending = null }
            else -> { pending = match; pendingCount = 1 }
        }
        return surface
    }

    private fun nearest(lat: Double, lon: Double, heading: Double?, ways: List<MapWay>): RoadSurface? {
        val metresPerLon = METRES_PER_DEGREE * cos(Math.toRadians(lat))
        val candidates = ArrayList<Pair<Double, Pair<RoadSurface, Double>>>() // distance to (surface, bearing)
        for (way in ways) {
            var best = Double.MAX_VALUE
            var bestBearing = 0.0
            for (i in 0 until way.points.size - 1) {
                val (ax, ay) = project(way.points[i], lat, lon, metresPerLon)
                val (bx, by) = project(way.points[i + 1], lat, lon, metresPerLon)
                val d = distanceToSegment(ax, ay, bx, by)
                if (d < best) {
                    best = d
                    bestBearing = Math.toDegrees(atan2(bx - ax, by - ay))
                }
            }
            if (best <= MAX_DISTANCE_M) candidates += best to (way.surface to bestBearing)
        }
        if (candidates.isEmpty()) return null
        val closest = candidates.minOf { it.first }
        val near = candidates.filter { it.first <= closest + SAME_DISTANCE_M }
        if (heading != null && near.size > 1) {
            near.filter { angleOff(heading, it.second.second) <= MAX_HEADING_OFF }
                .minByOrNull { it.first }
                ?.let { return it.second.first }
        }
        return candidates.minBy { it.first }.second.first
    }

    private fun project(p: Pair<Double, Double>, lat: Double, lon: Double, metresPerLon: Double) =
        (p.second - lon) * metresPerLon to (p.first - lat) * METRES_PER_DEGREE

    /** Distance from the origin to the segment a–b */
    private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSq).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** Angle between a heading and a road, which can be ridden either way */
    private fun angleOff(heading: Double, bearing: Double): Double {
        val diff = abs(((heading - bearing) % 360 + 540) % 360 - 180)
        return minOf(diff, 180 - diff)
    }

    companion object {
        const val MAX_DISTANCE_M = 15.0
        const val SAME_DISTANCE_M = 5.0
        const val MAX_HEADING_OFF = 45.0
        const val CONFIRM_SECONDS = 5
        const val LOST_MS = 10_000L
        private const val METRES_PER_DEGREE = 111_320.0
    }
}
