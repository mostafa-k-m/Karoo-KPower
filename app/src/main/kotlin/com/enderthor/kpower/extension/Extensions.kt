package com.enderthor.kpower.extension

import android.annotation.SuppressLint
import android.content.Context

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey


import com.enderthor.kpower.activity.dataStore
import com.enderthor.kpower.data.CoastCalibration
import com.enderthor.kpower.data.GearCheck
import com.enderthor.kpower.data.GpsCoordinates
import com.enderthor.kpower.data.OpenMeteoCurrentWeatherResponse
import com.enderthor.kpower.data.OpenWeatherCurrentWeatherResponse
import com.enderthor.kpower.data.HeadwindStats
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.OpenMeteoData
import com.enderthor.kpower.data.RETRY_CHECK_STREAMS
import com.enderthor.kpower.data.STREAM_TIMEOUT
import com.enderthor.kpower.data.StreamData
import com.enderthor.kpower.data.WAIT_STREAMS_LONG
import com.enderthor.kpower.data.WAIT_STREAMS_MEDIUM
import com.enderthor.kpower.data.WAIT_STREAMS_SHORT
import com.enderthor.kpower.data.defaultConfigData
import com.enderthor.kpower.vdevice.CoastSample


import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState



import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine


import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow



import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive


import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.time.Duration.Companion.milliseconds

import kotlin.time.Duration.Companion.seconds

sealed class HeadingResponse {
    data object NoGps: HeadingResponse()
    data object NoWeatherData: HeadingResponse()
    data class Value(val diff: Double): HeadingResponse()
}


val jsonWithUnknownKeys = Json { ignoreUnknownKeys = true }

val currentDataKey = stringPreferencesKey("current")
val statsKey = stringPreferencesKey("stats")
val lastKnownPositionKey = stringPreferencesKey("lastKnownPosition")

val preferencesKey = stringPreferencesKey("configdata")
val calibrationKey = stringPreferencesKey("coastcalibration")
val pendingCoastKey = stringPreferencesKey("coastpending")
val gearCheckKey = stringPreferencesKey("gearcheck")

suspend fun savePreferences(context: Context, configDatas: MutableList<ConfigData>) {
    context.dataStore.edit { t ->
        t[preferencesKey] = Json.encodeToString(configDatas)
    }
}

/** Stores (or with null removes) the coasting calibration of one config setup, see [ConfigData.calibrationKey] */
suspend fun saveCalibration(context: Context, configKey: String, calibration: CoastCalibration?) {
    context.dataStore.edit { t ->
        val current = decodeCalibrations(t[calibrationKey]).toMutableMap()
        if (calibration == null) current.remove(configKey) else current[configKey] = calibration
        t[calibrationKey] = Json.encodeToString(current)
    }
}

/** Stores what the gear detector saw on the last ride, keyed by config id */
suspend fun saveGearCheck(context: Context, configId: Int, check: GearCheck) {
    context.dataStore.edit { t ->
        val current = decodeGearChecks(t[gearCheckKey]).toMutableMap()
        current[configId.toString()] = check
        t[gearCheckKey] = Json.encodeToString(current)
    }
}

private fun decodeGearChecks(json: String?): Map<String, GearCheck> {
    if (json == null) return emptyMap()
    return try {
        jsonWithUnknownKeys.decodeFromString<Map<String, GearCheck>>(json)
    } catch (e: Throwable) {
        Timber.e(e, "Failed to read gear check")
        emptyMap()
    }
}

fun Context.loadGearCheckFlow(): Flow<Map<String, GearCheck>> {
    return dataStore.data.map { decodeGearChecks(it[gearCheckKey]) }.distinctUntilChanged()
}

/** Forgets what was learned for one config setup, including samples still waiting for a fit */
suspend fun resetCalibration(context: Context, configKey: String) {
    saveCalibration(context, configKey, null)
    savePendingCoastSamples(context, configKey, emptyList())
}

/** Coasting samples from rides too short to fit on their own, kept for the next ride */
suspend fun savePendingCoastSamples(context: Context, configKey: String, samples: List<CoastSample>) {
    context.dataStore.edit { t ->
        val current = decodePendingCoastSamples(t[pendingCoastKey]).toMutableMap()
        if (samples.isEmpty()) current.remove(configKey) else current[configKey] = samples
        t[pendingCoastKey] = Json.encodeToString(current)
    }
}

suspend fun Context.loadPendingCoastSamples(configKey: String): List<CoastSample> {
    return decodePendingCoastSamples(dataStore.data.first()[pendingCoastKey])[configKey] ?: emptyList()
}

/** Number of coasting samples waiting for a fit, per config setup */
fun Context.loadPendingCoastCountFlow(): Flow<Map<String, Int>> {
    return dataStore.data.map { t -> decodePendingCoastSamples(t[pendingCoastKey]).mapValues { it.value.size } }
        .distinctUntilChanged()
}

private fun decodePendingCoastSamples(json: String?): Map<String, List<CoastSample>> {
    if (json == null) return emptyMap()
    return try {
        jsonWithUnknownKeys.decodeFromString<Map<String, List<CoastSample>>>(json)
    } catch (e: Throwable) {
        Timber.e(e, "Failed to read pending coast samples")
        emptyMap()
    }
}

private fun decodeCalibrations(json: String?): Map<String, CoastCalibration> {
    if (json == null) return emptyMap()
    return try {
        jsonWithUnknownKeys.decodeFromString<Map<String, CoastCalibration>>(json)
    } catch (e: Throwable) {
        Timber.e(e, "Failed to read coast calibration")
        emptyMap()
    }
}

fun Context.loadCalibrationFlow(): Flow<Map<String, CoastCalibration>> {
    return dataStore.data.map { decodeCalibrations(it[calibrationKey]) }.distinctUntilChanged()
}

suspend fun saveStats(context: Context, stats: HeadwindStats) {
    context.dataStore.edit { t ->
        t[statsKey] = Json.encodeToString(stats)
    }
}

suspend fun saveCurrentData(context: Context, forecast: OpenMeteoCurrentWeatherResponse) {
    context.dataStore.edit { t ->
        Timber.d("Saving current data forecast $forecast")
        t[currentDataKey] = Json.encodeToString(forecast)
        Timber.d("Saved current data $t[currentDataKey]")
    }
}

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> {
    return callbackFlow {
        val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySendBlocking(event.state)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

fun Context.streamCurrentWeatherData(): Flow<OpenMeteoCurrentWeatherResponse> {
    return dataStore.data.map { settingsJson ->
        try {
            val data = settingsJson[currentDataKey]
            data?.let { d -> jsonWithUnknownKeys.decodeFromString<OpenMeteoCurrentWeatherResponse>(d) }
        } catch (e: Throwable) {
            Timber.e("Failed to stream current weather data $e")
            null
        }
    }.filterNotNull().distinctUntilChanged().filter { it.current.time * 1000 >= System.currentTimeMillis() - (1000 * 60 * 60 ) }
}

fun Context.streamStats(): Flow<HeadwindStats> {
    return dataStore.data.map { statsJson ->
        try {
            jsonWithUnknownKeys.decodeFromString<HeadwindStats>(
                statsJson[statsKey] ?: HeadwindStats.defaultStats
            )
        } catch(e: Throwable){
            Timber.e("Failed to read stats $e")
            jsonWithUnknownKeys.decodeFromString<HeadwindStats>(HeadwindStats.defaultStats)
        }
    }.distinctUntilChanged()
}
fun Context.loadPreferencesFlow(): Flow<List<ConfigData>> {
    return dataStore.data.map { settingsJson ->
        try {
            jsonWithUnknownKeys.decodeFromString<List<ConfigData>>(
                settingsJson[preferencesKey] ?: defaultConfigData
            ).map { configData ->
                configData.copy(surface = configData.surface)
            }

        } catch(e: Throwable){
            Timber.tag("kpower").e(e, "Failed to read preferences Flow Extension")
            jsonWithUnknownKeys.decodeFromString<List<ConfigData>>(defaultConfigData)
        }
    }.distinctUntilChanged()
}



fun Context.parseWeatherResponse(responseString: String): OpenMeteoCurrentWeatherResponse {
    Timber.d("Decoded weather: $responseString")

    val decoded = try {
        if (responseString.contains("\"current\"")) {
            jsonWithUnknownKeys.decodeFromString<OpenMeteoCurrentWeatherResponse>(responseString)
        } else {
            val weather = jsonWithUnknownKeys.decodeFromString<OpenWeatherCurrentWeatherResponse>(responseString)
            Timber.d("Decoded weather: $weather")
            OpenMeteoCurrentWeatherResponse(
                current = OpenMeteoData(
                    windSpeed = weather.wind.speed,
                    windDirection = weather.wind.deg,
                    time = weather.time,
                    interval = 0
                ),
                latitude = weather.coord.lat,
                longitude = weather.coord.lon,
                timezone = "",
                elevation = 0.0,
                utfOffsetSeconds = 0
            )
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid response format parse weather", e)

    }

    return decoded
}


@OptIn(FlowPreview::class)
suspend fun KarooSystemService.makeOpenMeteoHttpRequest(gpsCoordinates: GpsCoordinates, isOpenWeather: Boolean, api: String): HttpResponseState.Complete {
    return callbackFlow {

        val url = if(isOpenWeather && api.trim().isNotEmpty())  "https://api.openweathermap.org/data/2.5/weather?lat=${gpsCoordinates.lat}&lon=${gpsCoordinates.lon}&appid=$api"
        else "https://api.open-meteo.com/v1/forecast?latitude=${gpsCoordinates.lat}&longitude=${gpsCoordinates.lon}&current=wind_speed_10m,wind_direction_10m&timeformat=unixtime&wind_speed_unit=ms"

        Timber.d("Http request to ${url}...")

        val listenerId = addConsumer(
            OnHttpResponse.MakeHttpRequest(
                "GET",
                url= url,
                waitForConnection = false,
            ),
        ) { event: OnHttpResponse ->
            Timber.d("Http response event $event")
            if (event.state is HttpResponseState.Complete){
                trySend(event.state as HttpResponseState.Complete)
                close()
            }
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }.timeout(20.seconds).catch { e: Throwable ->
        if (e is TimeoutCancellationException){
            emit(HttpResponseState.Complete(500, mapOf(), null, "Timeout"))
        } else {
            throw e
        }
    }.single()
}

fun KarooSystemService.getRelativeHeadingFlow(context: Context): Flow<HeadingResponse> {
    val currentWeatherData = context.streamCurrentWeatherData()

    return getHeadingFlow(context)
        .combine(currentWeatherData) { bearing, data -> bearing to data }
        .map { (bearing, data) ->
            when (bearing) {
                is HeadingResponse.Value -> {
                    val windBearing = data.current.windDirection + 180
                    val diff = signedAngleDifference(bearing.diff, windBearing)

                    Timber.d("Wind bearing: $bearing vs $windBearing => $diff")

                    HeadingResponse.Value(diff)
                }

                is HeadingResponse.NoGps -> HeadingResponse.NoGps
                is HeadingResponse.NoWeatherData -> HeadingResponse.NoWeatherData
                else -> bearing
            }
        }
}


@SuppressLint("SuspiciousIndentation")
fun KarooSystemService.getHeadingFlow(context: Context): Flow<HeadingResponse> {
    // return flowOf(HeadingResponse.Value(20.0))

    return getGpsCoordinateFlow(context)
        .map { coords ->
            val heading = coords?.bearing
                Timber.d( "Updated gps bearing: $heading")
            val headingValue = heading?.let { HeadingResponse.Value(it) }

            headingValue ?: HeadingResponse.NoGps
        }
        .distinctUntilChanged()
}

fun signedAngleDifference(angle1: Double, angle2: Double): Double {
    val a1 = angle1 % 360
    val a2 = angle2 % 360
    var diff = abs(a1 - a2)

    val sign = if (a1 < a2) {
        if (diff > 180.0) -1 else 1
    } else {
        if (diff > 180.0) 1 else -1
    }

    if (diff > 180.0) {
        diff = 360.0 - diff
    }

    return sign * diff
}


fun <T> concatenate(vararg flows: Flow<T>) = flow {
    for (flow in flows) {
        emitAll(flow)
    }
}
fun<T> Flow<T>.dropNullsIfNullEncountered(): Flow<T?> = flow {
    var hadValue = false

    collect { value ->
        if (!hadValue) {
            emit(value)
            if (value != null) hadValue = true
        } else {
            if (value != null) emit(value)
        }
    }
}


@OptIn(FlowPreview::class)
fun KarooSystemService.getGpsCoordinateFlow(context: Context): Flow<GpsCoordinates?> {

    val initialFlow = flow {
        val lastKnownPosition = context.getLastKnownPosition()

        emit(lastKnownPosition)
    }

    val gpsFlow = streamLocation()
        .filter { it.orientation != null }
        .map { GpsCoordinates(it.lat, it.lng, it.orientation) }

    val concatenatedFlow = concatenate(initialFlow, gpsFlow)

    return concatenatedFlow.dropNullsIfNullEncountered()
}

suspend fun KarooSystemService.updateLastKnownGps(context: Context) {
    while (true) {
        getGpsCoordinateFlow(context)
            .filterNotNull()
            .throttle(60 * 1_000) // Only update last known gps position once every minute
            .collect { gps ->
                saveLastKnownPosition(context, gps)
            }
        delay(1_000)
    }
}

suspend fun Context.getLastKnownPosition(): GpsCoordinates? {
    val settingsJson = dataStore.data.first()

    try {
        val lastKnownPositionString = settingsJson[lastKnownPositionKey] ?: return null
        val lastKnownPosition = jsonWithUnknownKeys.decodeFromString<GpsCoordinates>(
            lastKnownPositionString
        )

        return lastKnownPosition
    } catch(e: Throwable){
       Timber.e( "Failed to read last known position $e")
        return null
    }
}

fun KarooSystemService.streamLocation(): Flow<OnLocationChanged> {
    return callbackFlow {
        val listenerId = addConsumer { event: OnLocationChanged ->
            trySendBlocking(event)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

fun<T> Flow<T>.throttle(timeout: Long): Flow<T> = flow {
    var lastEmissionTime = 0L

    collect { value ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmissionTime >= timeout) {
            emit(value)
            lastEmissionTime = currentTime
        }
    }
}

suspend fun saveLastKnownPosition(context: Context, gpsCoordinates: GpsCoordinates) {
    Timber.i("Saving last known position: $gpsCoordinates")

    try {
        context.dataStore.edit { t ->
            t[lastKnownPositionKey] = Json.encodeToString(gpsCoordinates)
        }
    } catch(e: Throwable){
       Timber.e( "Failed to save last known position $e")
    }

}
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> {
    return callbackFlow {
        val listenerId = addConsumer<T> {
            trySend(it)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}



@OptIn(FlowPreview::class)
fun KarooSystemService.streamDataMonitorFlow(
    dataTypeID: String,
    noCheck: Boolean = false
): Flow<StreamState> = flow {

    if (noCheck) {
        streamDataFlow(dataTypeID).collect { emit(it) }
        return@flow
    }

    var retryAttempt = 0


    val initialState = StreamState.Streaming(
        DataPoint(
            dataTypeId = dataTypeID,
            values = mapOf(DataType.Field.SINGLE to 0.0)
        )
    )

    emit(initialState)

    while (currentCoroutineContext().isActive) {
        try {
            streamDataFlow(dataTypeID)
                .distinctUntilChanged()
                .timeout(STREAM_TIMEOUT.milliseconds)
                .collect { state ->
                    when (state) {
                        is StreamState.Idle -> {
                            Timber.w("Stream estado inactivo: $dataTypeID, esperando...")
                            if (dataTypeID == DataType.Type.SPEED) emit(initialState)
                            delay(WAIT_STREAMS_SHORT)
                        }
                        is StreamState.NotAvailable -> {
                            Timber.w("Stream estado NotAvailable: $dataTypeID, esperando...")
                            emit(initialState)
                            delay(WAIT_STREAMS_SHORT * 2)
                        }
                        is StreamState.Searching -> {
                            Timber.w("Stream estado searching: $dataTypeID, esperando...")
                            emit(initialState)
                            delay(WAIT_STREAMS_SHORT/2)
                        }
                        else -> {
                            retryAttempt = 0
                            Timber.d("Stream estado: $state")
                            emit(state)
                        }
                    }
                }

        } catch (e: Exception) {
            when (e) {
                is TimeoutCancellationException -> {
                    if (retryAttempt++ < RETRY_CHECK_STREAMS) {
                        val backoffDelay = (1000L * (1 shl retryAttempt))
                            .coerceAtMost(WAIT_STREAMS_MEDIUM)
                        Timber.w("Timeout/Cancel en stream $dataTypeID, reintento $retryAttempt en ${backoffDelay}ms. Motivo $e")
                        delay(backoffDelay)
                    } else {
                        Timber.e("Máximo de reintentos alcanzado")
                        retryAttempt = 0
                        delay(WAIT_STREAMS_LONG)
                    }
                }
                is CancellationException -> {
                    Timber.d("Cancelación ignorada en streamDataFlow")
                }
                else -> {
                    Timber.e(e, "Error en stream")
                    delay(WAIT_STREAMS_LONG)
                }
            }
        }
    }
}



fun KarooSystemService.headwindFlow(context: Context): Flow<StreamState> = flow {
    try {
        getRelativeHeadingFlow(context)
            .combine(context.streamCurrentWeatherData()) { value, data -> StreamData(value, data) }
            .filter { it.weatherResponse != null }
            .onStart {
                emit(StreamState.Streaming(
                    DataPoint("headwindspeed", mapOf(DataType.Field.SINGLE to 0.0))
                ))
            }
            .collect { streamData ->
                val windSpeed = streamData.weatherResponse?.current?.windSpeed ?: 0.0
                val windDirection = (streamData.headingResponse as? HeadingResponse.Value)?.diff ?: 0.0
                val headwindSpeed = cos((windDirection + 180) * Math.PI / 180.0) * windSpeed

                emit(StreamState.Streaming(DataPoint("headwindspeed", mapOf(DataType.Field.SINGLE to headwindSpeed))))
                delay(1000L)
            }
    } catch (e: Exception) {
        Timber.e(e, "Error en headwindFlow")
        emit(StreamState.Streaming(
            DataPoint("headwindspeed", mapOf(DataType.Field.SINGLE to 0.0))
        ))
        delay(WAIT_STREAMS_SHORT/2)
        // No lanzamos la excepción para permitir que el flujo continúe
    }
}

