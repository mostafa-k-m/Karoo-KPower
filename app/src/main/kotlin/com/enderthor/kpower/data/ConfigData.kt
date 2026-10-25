package com.enderthor.kpower.data


import com.enderthor.kpower.extension.HeadingResponse
import com.enderthor.kpower.extension.toDoubleLocale
import io.hammerhead.karooext.models.StreamState

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json



const val WAIT_STREAMS_MEDIUM = 10000L // 10 seconds
const val WAIT_STREAMS_SHORT = 3000L // 3 seconds
const val STREAM_TIMEOUT = 20000L // 20 seconds

data class StreamData(val headingResponse: HeadingResponse, val weatherResponse: OpenMeteoCurrentWeatherResponse?)

data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@Serializable
data class RealKarooValues(
    val speed: StreamState? = null,
    val slope: StreamState? = null,
    val elevation: StreamState? = null,
    val cadence: StreamState? = null,
    val headwind: StreamState? = null,
    val pressure: StreamState? = null,
    val userWeight: StreamState? = null,
)

enum class KarooSurface(
    val surface: String,
    val factor: Double,
) {
    ASPHALT("Asphalt/Concrete", 0.75),  //0.8
    STANDARD("Standard/Mix/Gravel", 0.93), //1.0
    GRAVEL("Mountain Mix", 1.05), //1.17
    SAND("Mountain Off Road/Sand", 2.20), //2.50
}

/** Internal gear hub ratios (wheel turns per sprocket turn), from the manufacturers' published tables */
enum class GearHub(val label: String, val ratios: List<Double>) {
    NONE("None (derailleur or single speed)", listOf(1.0)),
    NEXUS_8("Shimano Nexus 8 / Alfine 8", listOf(0.527, 0.644, 0.748, 0.851, 1.0, 1.223, 1.419, 1.615)),
    ROHLOFF_14("Rohloff Speedhub 14", listOf(0.279, 0.316, 0.360, 0.409, 0.464, 0.528, 0.600, 0.682, 0.774, 0.881, 1.0, 1.135, 1.292, 1.467)),
    CUSTOM("Other hub (enter ratios)", emptyList()),
}


/**
 * Share of the weather service's wind that is used. Shelter at handlebar height accounts for part
 * of it, but mostly it discounts the forecast itself: one hourly value for an area kilometres wide,
 * its direction often 30–45° off. 0.65 is what the log wind profile gives at handlebar height over
 * open, low-rise country; fitting heart rate on replayed rides with forecast wind favoured less.
 */
const val DEFAULT_WIND_FACTOR = "0.65"

@Serializable
data class ConfigData(
    val id: Int,
    val name: String,
    val isActive: Boolean,
    val bikeMass: String ,
    val rollingResistanceCoefficient: String,
    val dragCoefficient: String,
    val frontalArea: String,
    val powerLoss: String ,
    val headwindconf: String,
    val isOpenWeather: Boolean,
    val apikey: String,
    val ftp: String ,
    val surface: KarooSurface = KarooSurface.STANDARD,
    val isforcepower: Boolean = false,
    val autoCalibrate: Boolean = false,
    val gearHub: GearHub = GearHub.NONE,
    val chainrings: String = "",
    val sprockets: String = "",
    val customHubRatios: String = "",
    val pedallingFloor: String = "30",
    // Road surface from the Karoo's offline map; [surface] is used where the map has no road
    val autoSurface: Boolean = false,
    // Share of the weather wind (forecast at 10 m height) that reaches a rider at handlebar height
    val windFactor: String = DEFAULT_WIND_FACTOR,
    // Write the accelerometer's per-second means into the ride file, for tuning
    val logMotion: Boolean = false,
    ) {
    // Stored inverted as isforcepower so configs saved by older versions keep their meaning
    val useCadenceSensor: Boolean get() = !isforcepower

    // Learned values belong to the hand-entered setup: editing it (e.g. for winter) starts a new
    // calibration, and entering the old values again brings the old calibration back
    val calibrationKey: String
        get() = listOf(id, bikeMass.toDoubleLocale(), dragCoefficient.toDoubleLocale(), frontalArea.toDoubleLocale(),
            rollingResistanceCoefficient.toDoubleLocale(), if (autoSurface) "AUTO" else surface.name).joinToString("|")

    /** [windFactor] as a number: 0–1.5, the default when it can't be read */
    val windFactorValue: Double
        get() = windFactor.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.5)
            ?: DEFAULT_WIND_FACTOR.toDouble()

    // With the surface from the map, coasting is only learned on paved roads, so one Crr fits all samples
    val coastSurfaceFactor: Double
        get() = if (autoSurface) KarooSurface.ASPHALT.factor else surface.factor

    /**
     * Wheel turns per crank turn in every gear, or empty when the gearing isn't entered.
     * Teeth are any whole numbers ("46", "11-13-15-17"); custom hub ratios use a dot for decimals.
     */
    fun gearRatios(): List<Double> {
        val rings = teeth(chainrings)
        val cogs = teeth(sprockets)
        val hub = if (gearHub == GearHub.CUSTOM) {
            customHubRatios.split(Regex("[\\s;,]+")).mapNotNull { it.toDoubleOrNull() }.filter { it > 0 }
        } else gearHub.ratios
        if (rings.isEmpty() || cogs.isEmpty() || hub.isEmpty()) return emptyList()
        val all = rings.flatMap { r -> cogs.flatMap { c -> hub.map { h -> r.toDouble() / c * h } } }.sorted()
        // Different combinations can give (nearly) the same gear
        return all.fold(mutableListOf()) { acc, x -> if (acc.isEmpty() || x > acc.last() * 1.005) acc.add(x); acc }
    }

    private fun teeth(text: String) = text.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }.filter { it > 0 }
}

/** What the gear detector saw on the last ride, kept per config so the wheel size carries over */
@Serializable
data class GearCheck(
    val circumference: Double,
    val steadySamples: Int,
    val matchRate: Double,
)


/**
 * CdA learned from coasting for one config setup ([ConfigData.calibrationKey]); Crr always comes
 * from the config. Older versions also stored a learned "crr", which is ignored when read.
 */
@Serializable
data class CoastCalibration(
    val cdA: Double,
    val samples: Int = 0,
    val rides: Int = 0,
)

//val previewConfigData = listOf(ConfigData(0,"default", true, "14.0","0.0095","0.8","0.9","2.2","0.0", false, "","200", KarooSurface.STANDARD,false))
val previewConfigData = listOf(ConfigData(0,"Spark", true, "14.0","0.0095","0.8","0.9","2.2","0.0", false, "","257", KarooSurface.GRAVEL,false))
val defaultConfigData = Json.encodeToString(previewConfigData)
