package com.enderthor.kpower.extension



import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.enderthor.kpower.vdevice.EstimatedPowerSource
import com.enderthor.kpower.motion.MotionLogger
import com.enderthor.kpower.surface.SurfaceDataType
import com.enderthor.kpower.surface.SurfaceTracker
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.WriteToRecordMesg
import kotlinx.coroutines.isActive
import com.enderthor.kpower.BuildConfig
import kotlinx.coroutines.flow.transformLatest
import com.enderthor.kpower.data.GpsCoordinates
import com.enderthor.kpower.data.HeadwindStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry

import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.minutes



class KpowerExtension : KarooExtension("kpower", BuildConfig.VERSION_NAME)
{

    lateinit var karooSystem: KarooSystemService
    private var serviceJob: Job? = null
    private var updateLastKnownGpsJob: Job? = null
    private var surfaceJob: Job? = null

    override val types by lazy { listOf(SurfaceDataType(extension)) }



    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun onCreate() {
        super.onCreate()

        Timber.d("Service created")
        karooSystem = KarooSystemService(applicationContext)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.d("Connected to Karoo system")
            }
        }

        surfaceJob = SurfaceTracker.start(applicationContext, karooSystem, CoroutineScope(Dispatchers.IO))

        updateLastKnownGpsJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.updateLastKnownGps(this@KpowerExtension)
        }


        serviceJob = CoroutineScope(Dispatchers.IO).launch{

            val gpsFlow = karooSystem
                .getGpsCoordinateFlow(this@KpowerExtension)
                .distinctUntilChanged { old, new ->
                    if (old != null && new != null) {
                        old.distanceTo(new).absoluteValue < 0.001
                    } else {
                        old == new
                    }
                }
                .debounce(10000L)
                .transformLatest { value: GpsCoordinates? ->
                    while(true){
                        emit(value)
                        delay(15.minutes)
                    }
                }

            loadPreferencesFlow()
                .combine(gpsFlow) { preferences, gps -> preferences to gps }
                .map { (preferences,gps) ->

                    val lastKnownStats = try {
                        streamStats().first()
                    } catch(e: Exception){
                        Timber.e("Failed to read stats $e")
                        HeadwindStats()
                    }

                    if (gps == null){
                        error("No GPS coordinates available")
                    }


                    val response = karooSystem.makeOpenMeteoHttpRequest(gps,preferences[0].isOpenWeather, preferences[0].apikey)
                    if (response.error != null){
                        try {
                            val stats = lastKnownStats.copy(failedWeatherRequest = System.currentTimeMillis())
                            launch { saveStats(this@KpowerExtension, stats) }
                        } catch(e: Exception){
                            Timber.e( "Failed to write stats $e")
                        }
                        error("HTTP request failed: ${response.error}")
                    } else {
                        try {
                            val stats = lastKnownStats.copy(
                                lastSuccessfulWeatherRequest = System.currentTimeMillis(),
                                lastSuccessfulWeatherPosition = gps
                            )
                            launch { saveStats(this@KpowerExtension, stats) }
                        } catch(e: Exception){
                            Timber.e("Failed to write stats $e")
                        }
                    }

                    // OpenWeather has no snow depth: ask Open-Meteo for it too when snow is taken into account
                    val snowResponse = if (preferences[0].isOpenWeather && preferences[0].snowAdjust) {
                        try { karooSystem.makeOpenMeteoHttpRequest(gps, false, "").takeIf { it.error == null } } catch (e: Exception) { null }
                    } else null

                    response to snowResponse
                }
                .retry(Long.MAX_VALUE) { delay(1.minutes); true }
                .collect { (response, snowResponse) ->
                    try {
                        val responseString = String(response.body ?: ByteArray(0))
                        Timber.d("Got response: $responseString")
                        var data = parseWeatherResponse(responseString)
                        snowResponse?.body?.let { body ->
                            try {
                                val extra = parseWeatherResponse(String(body)).current
                                data = data.copy(current = data.current.copy(
                                    snowDepth = extra.snowDepth,
                                    temperature = data.current.temperature ?: extra.temperature,
                                    pressureMsl = data.current.pressureMsl ?: extra.pressureMsl,
                                ))
                            } catch (e: Exception) {
                                Timber.e("Failed to read snow depth $e")
                            }
                        }
                        saveCurrentData(applicationContext, data)
                    } catch(e: Exception){
                        Timber.e("Failed to read current weather data $e")
                    }
                }

        }
    }

    override fun startScan(emitter: Emitter<Device>) {
        // Find estimated Power source
        val job = CoroutineScope(Dispatchers.IO).launch {
            delay(2000L)
            Timber.d("Start scan")
            emitter.onNext(EstimatedPowerSource(extension, 2000, karooSystem, applicationContext).source)
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("Connect Device")
        EstimatedPowerSource.Companion.fromUid(extension, uid, karooSystem,  applicationContext)?.connect(emitter,extension)
    }

    // While a ride records: the detected surface into each record, to check it against the road afterwards
    override fun startFit(emitter: Emitter<FitEffect>) {
        var motion: MotionLogger? = null
        var weather: com.enderthor.kpower.data.OpenMeteoData? = null
        val weatherJob = CoroutineScope(Dispatchers.IO).launch {
            try { streamCurrentWeatherData().collect { weather = it.current } } catch (e: Exception) { Timber.e(e, "Weather for the FIT stopped") }
        }
        val job = CoroutineScope(Dispatchers.IO).launch {
            val logMotion = try { loadPreferencesFlow().first().firstOrNull()?.logMotion == true } catch (e: Exception) { false }
            if (logMotion) motion = MotionLogger(applicationContext).takeIf { it.start() }
            while (isActive) {
                val values = ArrayList<FieldValue>()
                val state = SurfaceTracker.state.value
                if (state.enabled) {
                    values += FieldValue(SURFACE_FIELD, state.surface.fitCode.toDouble())
                    state.factor?.let { values += FieldValue(SURFACE_FACTOR_FIELD, it) }
                }
                weather?.let { w ->
                    w.temperature?.let { values += FieldValue(AIR_TEMP_FIELD, it) }
                    w.snowDepth?.let { values += FieldValue(SNOW_DEPTH_FIELD, it) }
                }
                motion?.take()?.let { m ->
                    values += FieldValue(ACCEL_X_FIELD, m.x)
                    values += FieldValue(ACCEL_Y_FIELD, m.y)
                    values += FieldValue(ACCEL_Z_FIELD, m.z)
                    values += FieldValue(ACCEL_SAMPLES_FIELD, m.samples.toDouble())
                }
                if (values.isNotEmpty()) emitter.onNext(WriteToRecordMesg(values))
                delay(1000L)
            }
        }
        emitter.setCancellable {
            job.cancel()
            weatherJob.cancel()
            motion?.stop()
        }
    }

    override fun onDestroy() {
        surfaceJob?.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        // FIT base types: 2 = uint8, 136 = float32
        val SURFACE_FIELD = DeveloperField(0, 2, "surface_class", "", null, 0)       // 0 none, 1 paved, 2 compact, 3 gravel, 4 soft
        val SURFACE_FACTOR_FIELD = DeveloperField(1, 136, "surface_factor", "", null, 0) // multiplies Crr
        // Accelerometer means per second in the Karoo's own axes, gravity included (132 = uint16)
        val ACCEL_X_FIELD = DeveloperField(2, 136, "accel_x", "m/s2", null, 0)
        val ACCEL_Y_FIELD = DeveloperField(3, 136, "accel_y", "m/s2", null, 0)
        val ACCEL_Z_FIELD = DeveloperField(4, 136, "accel_z", "m/s2", null, 0)
        val ACCEL_SAMPLES_FIELD = DeveloperField(5, 132, "accel_samples", "", null, 0)
        // The weather KPower used: air temperature and snow depth on the ground
        val AIR_TEMP_FIELD = DeveloperField(6, 136, "weather_temp", "C", null, 0)
        val SNOW_DEPTH_FIELD = DeveloperField(7, 136, "snow_depth", "m", null, 0)
    }
}