package com.enderthor.kpower.data


import com.enderthor.kpower.extension.HeadingResponse
import com.enderthor.kpower.extension.toDoubleLocale
import io.hammerhead.karooext.models.StreamState

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json



const val RETRY_CHECK_STREAMS = 4
const val WAIT_STREAMS_LONG = 120000L // 120 seconds
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
    ) {
    // Stored inverted as isforcepower so configs saved by older versions keep their meaning
    val useCadenceSensor: Boolean get() = !isforcepower

    // Learned values belong to the hand-entered setup: editing it (e.g. for winter) starts a new
    // calibration, and entering the old values again brings the old calibration back
    val calibrationKey: String
        get() = listOf(id, bikeMass.toDoubleLocale(), dragCoefficient.toDoubleLocale(), frontalArea.toDoubleLocale(),
            rollingResistanceCoefficient.toDoubleLocale(), surface.name).joinToString("|")

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
 * CdA and Crr learned from coasting for one config setup ([ConfigData.calibrationKey]). [crr] is the base value (like the Crr field):
 * the estimator still multiplies it by the surface factor.
 */
@Serializable
data class CoastCalibration(
    val cdA: Double,
    val crr: Double,
    val samples: Int = 0,
    val rides: Int = 0,
)

//val previewConfigData = listOf(ConfigData(0,"default", true, "14.0","0.0095","0.8","0.9","2.2","0.0", false, "","200", KarooSurface.STANDARD,false))
val previewConfigData = listOf(ConfigData(0,"Spark", true, "14.0","0.0095","0.8","0.9","2.2","0.0", false, "","257", KarooSurface.GRAVEL,false))
val defaultConfigData = Json.encodeToString(previewConfigData)
