package com.enderthor.kpower.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


@Serializable
data class OpenMeteoData(
    val time: Long,
    val interval: Int,
    @SerialName("wind_speed_10m") val windSpeed: Double,
    @SerialName("wind_direction_10m") val windDirection: Double,
    @SerialName("temperature_2m") val temperature: Double? = null, // °C
    @SerialName("pressure_msl") val pressureMsl: Double? = null,   // hPa, reduced to sea level
    @SerialName("snow_depth") val snowDepth: Double? = null,       // m
)


@Serializable
data class OpenWeatherData(
    @SerialName("speed") val speed: Double,
    @SerialName("deg") val deg: Double,
)

@Serializable
data class OpenWeatherMain(
    @SerialName("temp") val temp: Double,         // K (no units parameter in the request)
    @SerialName("pressure") val pressure: Double, // hPa at sea level
)

@Serializable
data class OpenWeatherCoord(
    @SerialName("lon") val lon: Double,
    @SerialName("lat") val lat: Double,
)


@Serializable
data class OpenMeteoCurrentWeatherResponse(
    val current: OpenMeteoData,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val elevation: Double,
    @SerialName("utc_offset_seconds") val utfOffsetSeconds: Int
)

@Serializable
data class OpenWeatherCurrentWeatherResponse(
    val wind: OpenWeatherData,
    val main: OpenWeatherMain? = null,
    @SerialName("dt") val time: Long,
    val coord: OpenWeatherCoord,
)

@Serializable
data class HeadwindStats(
    val lastSuccessfulWeatherRequest: Long? = null,
    val lastSuccessfulWeatherPosition: GpsCoordinates? = null,
    val failedWeatherRequest: Long? = null,
){
    companion object {
        val defaultStats = Json.encodeToString(HeadwindStats())
    }
}