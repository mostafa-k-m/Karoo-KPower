package com.enderthor.kpower.surface

import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import timber.log.Timber
import java.io.File

/**
 * Roads around a position from the Karoo's own offline maps (Mapsforge files the Karoo downloads
 * per region). Reading needs the storage permission ([hasAccess]); without it, or without maps,
 * [status] says so and [waysAround] returns nothing.
 */
class OfflineMapSource(
    private val dir: File = File(DEFAULT_DIR),
    private val hasAccess: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    sealed interface Status {
        data object NoAccess : Status
        data object NoMaps : Status
        data class Ready(val maps: List<String>) : Status
    }

    private var maps: List<MapFile>? = null
    private var mapNames: List<String> = emptyList()
    private var lastScanMs = Long.MIN_VALUE
    private val tiles = object : LinkedHashMap<Triple<Int, Int, Int>, List<MapWay>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Int, Int, Int>, List<MapWay>>?) = size > MAX_TILES
    }

    @Synchronized
    fun status(): Status {
        // A folder that exists but can't be listed means the permission (and its group) is missing
        if (!hasAccess() || (dir.exists() && dir.listFiles() == null)) return Status.NoAccess
        val files = openMaps()
        return if (files.isEmpty()) Status.NoMaps else Status.Ready(mapNames)
    }

    /** Rideable roads near [lat]/[lon]: the zoom-15 tile there, plus a neighbour when close to its edge */
    @Synchronized
    fun waysAround(lat: Double, lon: Double): List<MapWay> {
        if (!hasAccess()) return emptyList()
        val files = openMaps()
        val index = files.indexOfFirst { it.boundingBox().contains(lat, lon) }
        if (index < 0) return emptyList()

        val mapSize = MercatorProjection.getMapSize(ZOOM, TILE_SIZE)
        val px = MercatorProjection.longitudeToPixelX(lon, mapSize)
        val py = MercatorProjection.latitudeToPixelY(lat, mapSize)
        val tx = (px / TILE_SIZE).toInt()
        val ty = (py / TILE_SIZE).toInt()
        val edge = EDGE_PX
        val xs = listOfNotNull(tx, if (px - tx * TILE_SIZE < edge) tx - 1 else null, if ((tx + 1) * TILE_SIZE - px < edge) tx + 1 else null)
        val ys = listOfNotNull(ty, if (py - ty * TILE_SIZE < edge) ty - 1 else null, if ((ty + 1) * TILE_SIZE - py < edge) ty + 1 else null)
        return xs.flatMap { x -> ys.flatMap { y -> tile(index, files[index], x, y) } }
    }

    private fun tile(index: Int, file: MapFile, x: Int, y: Int): List<MapWay> =
        tiles.getOrPut(Triple(index, x, y)) {
            try {
                file.readMapData(Tile(x, y, ZOOM, TILE_SIZE))?.ways.orEmpty().flatMap { way ->
                    val surface = RoadSurface.fromTags(way.tags.associate { it.key to it.value }) ?: return@flatMap emptyList()
                    way.latLongs.filter { it.size >= 2 }.map { part -> MapWay(surface, part.map { it.latitude to it.longitude }) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Reading map tile $x/$y failed")
                emptyList()
            }
        }

    // Opened once; looked for again now and then while none are found (maps downloading)
    private fun openMaps(): List<MapFile> {
        maps?.takeIf { it.isNotEmpty() }?.let { return it }
        if (lastScanMs != Long.MIN_VALUE && nowMs() - lastScanMs < RESCAN_MS) return maps.orEmpty()
        lastScanMs = nowMs()
        // Smallest first: where regions overlap (e.g. along a border) the more local map is asked first
        val opened = dir.listFiles { f -> f.isFile && f.name.endsWith(".map") }.orEmpty().sortedBy { it.length() }.mapNotNull { f ->
            try {
                f.nameWithoutExtension to MapFile(f)
            } catch (e: Exception) {
                Timber.e(e, "Can't open offline map ${f.name}")
                null
            }
        }
        maps = opened.map { it.second }
        mapNames = opened.map { it.first }
        Timber.i("Offline maps: $mapNames")
        return maps.orEmpty()
    }

    companion object {
        const val DEFAULT_DIR = "/sdcard/offline/maps"
        const val ZOOM: Byte = 15
        const val TILE_SIZE = 256
        // About 25 m at zoom 15 at these latitudes: a road just across the tile edge still counts
        const val EDGE_PX = 12.0
        const val MAX_TILES = 16
        const val RESCAN_MS = 60_000L
    }
}
