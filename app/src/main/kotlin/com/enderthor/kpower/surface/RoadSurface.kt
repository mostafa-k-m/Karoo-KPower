package com.enderthor.kpower.surface

import com.enderthor.kpower.data.KarooSurface

/**
 * Surface class of the road under the wheels, as read from the Karoo's offline map, with the
 * factor it multiplies the config's Crr by. Paved is above the manual Asphalt setting (0.75): scored
 * against rides with a real power meter, asphalt read low at 0.75 and closest around 0.85–0.95. The
 * unpaved ones are steeper than the manual settings (Standard is 1.24× Asphalt, Mountain Mix 1.4×), which
 * replayed mixed rides showed under-reading flat compact paths and gravel; published rolling
 * resistance for touring tyres off asphalt is about 2× or more. Compact and Gravel sit a little
 * below that until more rides confirm it.
 */
enum class RoadSurface(val label: String, val factor: Double?, val fitCode: Int) {
    UNKNOWN("-", null, 0),
    PAVED("Paved", 0.85, 1),
    COMPACT("Compact", 1.25, 2),
    GRAVEL("Gravel", 1.45, 3),
    SOFT("Soft", KarooSurface.SAND.factor, 4);

    companion object {
        private val SOFT_SURFACES = setOf("sand", "grass", "mud", "woodchips", "snow", "ice")
        private val GRAVEL_SURFACES = setOf("gravel", "unpaved", "dirt", "ground", "earth", "pebblestone", "rock", "grass_paver")
        private val COMPACT_SURFACES = setOf("compacted", "fine_gravel")
        private val PAVED_SURFACES = setOf(
            "asphalt", "concrete", "concrete:plates", "concrete:lanes", "paved", "paving_stones", "sett",
            "cobblestone", "unhewn_cobblestone", "chipseal", "metal", "wood",
        )

        // The offline map only tags unpaved surfaces, so these road classes without a tag are paved
        private val PAVED_ROADS = setOf(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
            "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street",
            "service", "road", "cycleway", "footway", "pedestrian",
        )
        private val UNPAVED_ROADS = setOf("track", "path", "bridleway")

        /** The surface for a way's OSM tags, or null when it isn't a road a bike rides on */
        fun fromTags(tags: Map<String, String>): RoadSurface? {
            val highway = tags["highway"] ?: return null
            if (highway !in PAVED_ROADS && highway !in UNPAVED_ROADS) return null
            when (tags["surface"]) {
                in SOFT_SURFACES -> return SOFT
                in GRAVEL_SURFACES -> return GRAVEL
                in COMPACT_SURFACES -> return COMPACT
                in PAVED_SURFACES -> return PAVED
            }
            when (tags["tracktype"]) {
                "grade1", "grade2" -> return COMPACT
                "grade3", "grade4" -> return GRAVEL
                "grade5" -> return SOFT
            }
            return if (highway in PAVED_ROADS) PAVED else COMPACT
        }
    }
}
