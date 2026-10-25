package com.enderthor.kpower.surface

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class SurfaceMatcherTest {

    // Synthetic roads around an arbitrary point; offsets in metres
    private val lat0 = 50.0
    private val lon0 = 10.0
    private fun at(northM: Double, eastM: Double) =
        lat0 + northM / 111_320.0 to lon0 + eastM / (111_320.0 * cos(Math.toRadians(lat0)))

    // East–west paved road along y = 0, a north–south gravel track along x = 8
    private val road = MapWay(RoadSurface.PAVED, listOf(at(0.0, -500.0), at(0.0, 500.0)))
    private val track = MapWay(RoadSurface.GRAVEL, listOf(at(-500.0, 8.0), at(500.0, 8.0)))
    private val ways = listOf(road, track)

    private val matcher = SurfaceMatcher()
    private fun ride(northM: Double, eastM: Double, heading: Double?, t: Long): RoadSurface {
        val (lat, lon) = at(northM, eastM)
        return matcher.update(lat, lon, heading, ways, t * 1000)
    }

    @Test
    fun firstMatchIsTakenStraightAway() {
        assertEquals(RoadSurface.PAVED, ride(2.0, -200.0, 90.0, 0))
    }

    @Test
    fun headingPicksTheRoadAtAJunction() {
        // 4 m from the road and 4 m from the track: riding north means the track
        assertEquals(RoadSurface.GRAVEL, ride(4.0, 4.0, 0.0, 0))
        val other = SurfaceMatcher()
        val (lat, lon) = at(4.0, 4.0)
        assertEquals(RoadSurface.PAVED, other.update(lat, lon, 90.0, ways, 0))
    }

    @Test
    fun newSurfaceNeedsConfirmation() {
        ride(0.0, -100.0, 90.0, 0)
        // Turned onto the track: the first readings keep the old surface
        for (t in 1 until SurfaceMatcher.CONFIRM_SECONDS) assertEquals(RoadSurface.PAVED, ride(20.0 + t, 8.0, 0.0, t.toLong()))
        assertEquals(RoadSurface.GRAVEL, ride(30.0, 8.0, 0.0, SurfaceMatcher.CONFIRM_SECONDS.toLong()))
    }

    @Test
    fun aSingleStrayReadingDoesNotSwitch() {
        ride(0.0, -100.0, 90.0, 0)
        ride(30.0, 8.0, 0.0, 1) // GPS jumped onto the track once
        assertEquals(RoadSurface.PAVED, ride(0.0, -90.0, 90.0, 2))
        assertEquals(RoadSurface.PAVED, ride(0.0, -80.0, 90.0, 3))
    }

    @Test
    fun offTheMapForAWhileBecomesUnknown() {
        ride(0.0, -100.0, 90.0, 0)
        assertEquals(RoadSurface.PAVED, ride(200.0, -100.0, 90.0, 5))
        assertEquals(RoadSurface.UNKNOWN, ride(200.0, -100.0, 90.0, 11))
    }

    @Test
    fun farFromEveryRoadNeverMatches() {
        assertEquals(RoadSurface.UNKNOWN, ride(100.0, 100.0, null, 0))
    }
}
