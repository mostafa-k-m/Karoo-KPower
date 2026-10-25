package com.enderthor.kpower.surface

import com.enderthor.kpower.data.KarooSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadSurfaceTest {

    private fun of(vararg tags: Pair<String, String>) = RoadSurface.fromTags(mapOf(*tags))

    @Test
    fun untaggedRoadsArePaved() {
        assertEquals(RoadSurface.PAVED, of("highway" to "residential"))
        assertEquals(RoadSurface.PAVED, of("highway" to "cycleway"))
        assertEquals(RoadSurface.PAVED, of("highway" to "primary"))
    }

    @Test
    fun surfaceTagWins() {
        assertEquals(RoadSurface.GRAVEL, of("highway" to "unclassified", "surface" to "gravel"))
        assertEquals(RoadSurface.COMPACT, of("highway" to "footway", "surface" to "fine_gravel"))
        assertEquals(RoadSurface.SOFT, of("highway" to "path", "surface" to "sand"))
        assertEquals(RoadSurface.PAVED, of("highway" to "track", "surface" to "asphalt"))
    }

    @Test
    fun trackTypeWhenNoSurface() {
        assertEquals(RoadSurface.COMPACT, of("highway" to "track", "tracktype" to "grade1"))
        assertEquals(RoadSurface.GRAVEL, of("highway" to "track", "tracktype" to "grade3"))
        assertEquals(RoadSurface.SOFT, of("highway" to "track", "tracktype" to "grade5"))
    }

    @Test
    fun untaggedTracksAndPathsAreCompact() {
        assertEquals(RoadSurface.COMPACT, of("highway" to "track"))
        assertEquals(RoadSurface.COMPACT, of("highway" to "path"))
    }

    @Test
    fun rougherSurfacesRollHarder() {
        val factors = listOf(RoadSurface.PAVED, RoadSurface.COMPACT, RoadSurface.GRAVEL, RoadSurface.SOFT).map { it.factor!! }
        assertEquals(factors.sorted(), factors)
        assertTrue(RoadSurface.PAVED.factor!! > KarooSurface.ASPHALT.factor)
        assertNull(RoadSurface.UNKNOWN.factor)
    }

    @Test
    fun notARideableRoad() {
        assertNull(of("highway" to "steps"))
        assertNull(of("highway" to "raceway"))
        assertNull(of("building" to "yes"))
    }
}
