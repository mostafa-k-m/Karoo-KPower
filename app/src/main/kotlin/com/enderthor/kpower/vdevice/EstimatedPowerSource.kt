package com.enderthor.kpower.vdevice

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import com.enderthor.kpower.data.CoastCalibration
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.OpenMeteoCurrentWeatherResponse
import com.enderthor.kpower.data.GearCheck
import com.enderthor.kpower.data.RealKarooValues
import com.enderthor.kpower.data.previewConfigData
import com.enderthor.kpower.extension.*
import com.enderthor.kpower.surface.SurfaceTracker


class EstimatedPowerSource(
    extension: String,
    private val hr: Int,
    private val karooSystem: KarooSystemService,
    private val context: Context
) {
    val source by lazy {
        Device(
            extension,
            "estimated-power-$hr",
            listOf(DataType.Source.POWER),
            "Estimated Power $hr Source"
        )
    }

    private var lastRandomPowerTime = 0L
    private var currentRandomPower = 150.0  // Valor inicial
    private val randomPowerUpdateInterval = 5000L  // Cambiar

    private val pipeline = PowerPipeline()
    private val calibrationMutex = Mutex()

    private fun getRandomPower(): Double {
        val currentTime = System.currentTimeMillis()

        // Si han pasado más de X segundos desde la última actualización, generar un nuevo valor
        if (currentTime - lastRandomPowerTime > randomPowerUpdateInterval) {
            currentRandomPower = (100..259).random().toDouble()
            lastRandomPowerTime = currentTime
        }

        return currentRandomPower
    }

    @OptIn(FlowPreview::class)
    fun connect(emitter: Emitter<DeviceEvent>, extension: String) {
        Timber.d("Init Connect Power Estimator")
        val scope = CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {

                emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                delay(2000)
                emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                delay(1000)
                emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
                delay(1000)
                emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", "1234", "POWER-EXT-1")))
                delay(1000)


                val userProfile = karooSystem.consumerFlow<UserProfile>().first()
                val (userMass, factorMass, factorDistance, factorElevation) = getUserProfileFactors(userProfile)

                val calibrationFlow = context.loadCalibrationFlow()
                    .catch { e ->
                        Timber.e(e, "Error loading coast calibration")
                        emit(emptyMap())
                    }
                    .stateIn(
                        scope = scope,
                        started = SharingStarted.Eagerly,
                        initialValue = emptyMap()
                    )

                val gearCheckFlow = context.loadGearCheckFlow()
                    .catch { e ->
                        Timber.e(e, "Error loading gear check")
                        emit(emptyMap())
                    }
                    .stateIn(
                        scope = scope,
                        started = SharingStarted.Eagerly,
                        initialValue = emptyMap()
                    )

                // Weather for air density and snow; the Karoo's own temperature (inside the warm unit,
                // a few °C high) only when the weather has none
                val weatherFlow = context.streamCurrentWeatherData()
                    .map<OpenMeteoCurrentWeatherResponse, OpenMeteoCurrentWeatherResponse?> { it }
                    .catch { e -> Timber.e(e, "Error loading weather"); emit(null) }
                    .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = null)
                val karooTemperatureFlow = karooSystem.streamDataMonitorFlow(DataType.Type.TEMPERATURE, noCheck = true)
                    .map { s -> (s as? StreamState.Streaming)?.dataPoint?.singleValue }
                    .catch { emit(null) }
                    .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = null)

                val powerConfigFlow = context.loadPreferencesFlow()
                    .catch { e ->
                        Timber.e(e, "Error loading power configs")
                        emit(previewConfigData)
                    }
                    .stateIn(
                        scope = scope,
                        started = SharingStarted.Eagerly,
                        initialValue = previewConfigData
                    )

                // Sensor data points carry the sensor's SavedDevice id as sourceId; GPS speed comes as DEVICE_LOCATION_ID
                val speedSensorIdsFlow = karooSystem.consumerFlow<SavedDevices>()
                    .map { saved -> speedSensorIds(saved.devices) }
                    .onEach { Timber.i("Speed sensors: $it") }
                    .catch { e ->
                        Timber.e(e, "Error loading saved devices")
                        emit(emptySet())
                    }
                    .stateIn(
                        scope = scope,
                        started = SharingStarted.Eagerly,
                        initialValue = emptySet()
                    )

                // Fit the coasting samples once a ride ends
                scope.launch {
                    var wasRiding = false
                    karooSystem.consumerFlow<RideState>().collect { state ->
                        if (state is RideState.Idle && wasRiding) {
                            commitCalibration(powerConfigFlow.value)
                            commitGearCheck()
                        }
                        wasRiding = state !is RideState.Idle
                    }
                }

                combine(
                    karooSystem.streamDataMonitorFlow(DataType.Type.SPEED),
                    karooSystem.streamDataMonitorFlow(DataType.Type.ELEVATION_GRADE),
                    karooSystem.streamDataMonitorFlow(DataType.Type.PRESSURE_ELEVATION_CORRECTION),
                    karooSystem.streamDataMonitorFlow(DataType.Type.CADENCE,true),
                    karooSystem.headwindFlow(context),
                    powerConfigFlow
                ) { streams: Array<*> ->
                    Timber.d("Streams: ${streams.joinToString { it.toString() }}")

                    val speed = streams[0] as StreamState
                    val slope = streams[1] as StreamState
                    val elevation = streams[2] as StreamState
                    val cadence = streams[3] as StreamState
                    val headwind = streams[4] as StreamState

                    Timber.w(" Despues del FLOW COMBINE Speed: $speed, Slope: $slope, Elevation: $elevation, Cadence: $cadence, Headwind: $headwind")

                    @Suppress("UNCHECKED_CAST")
                    val configs = streams[5] as List<ConfigData>
                    val karooValues = RealKarooValues(
                        speed = speed,
                        slope = slope,
                        elevation = elevation,
                        cadence = cadence,
                        headwind = headwind
                    )
                    Pair(karooValues, configs)
                }.throttle(900L)
                    .collect { (karooValues, configs) ->
                        if (configs.isNotEmpty()) {
                            val speed = karooValues.speed as? StreamState.Streaming
                            val cadence = karooValues.cadence as? StreamState.Streaming
                            val powerValue = pipeline.update(
                                PowerInputs(
                                    speed = speed?.let { karooValues.speed.getValueOrDefault() * factorDistance },
                                    speedSourceId = speed?.dataPoint?.sourceId,
                                    cadence = cadence?.let { karooValues.cadence.getValueOrDefault() },
                                    cadenceSourceId = cadence?.dataPoint?.sourceId,
                                    slope = karooValues.slope.getValueOrDefault() / 100,
                                    elevation = karooValues.elevation.getValueOrDefault() * factorElevation,
                                    elevationStreaming = karooValues.elevation?.isRealReading() == true,
                                    headwind = karooValues.headwind.getValueOrDefault(),
                                    surfaceFactor = SurfaceTracker.state.value.factor,
                                    airTemperatureC = weatherFlow.value?.current?.temperature
                                        ?: karooTemperatureFlow.value?.minus(KAROO_TEMPERATURE_BIAS_C),
                                    seaLevelPressurePa = weatherFlow.value?.current?.pressureMsl?.times(100.0),
                                    snowDepth = weatherFlow.value?.current?.snowDepth,
                                ),
                                PowerSetup(
                                    config = configs[0],
                                    riderMass = userMass,
                                    massFactor = factorMass,
                                    calibrations = calibrationFlow.value,
                                    speedSensorIds = speedSensorIdsFlow.value,
                                    gearChecks = gearCheckFlow.value,
                                )
                            )
                            //val powerValue = getRandomPower()
                            emitter.onNext(
                                OnDataPoint(
                                    DataPoint(
                                        source.dataTypes.first(),
                                        values = mapOf(DataType.Field.POWER to powerValue),
                                        sourceId = source.uid
                                    )
                                )
                            )
                        }
                    }

                awaitCancellation()
            } catch (e: CancellationException) {
                Timber.w("Connect coroutine was cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Error in connect function")
                emitter.onError(e)
            }
        }

        emitter.setCancellable {
            Timber.w("Stopping connect coroutine")
            scope.cancel()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    commitCalibration(context.loadPreferencesFlow().first())
                    commitGearCheck()
                } catch (e: Exception) {
                    Timber.e(e, "Error saving coast calibration")
                }
            }
        }
    }

    private suspend fun commitGearCheck() {
        val (configId, check) = pipeline.takeGearCheck() ?: return
        Timber.i("Gear check: $check")
        saveGearCheck(context, configId, check)
    }


    private suspend fun commitCalibration(configs: List<ConfigData>) {
        calibrationMutex.withLock {
            val batch = pipeline.coastDetector.drain() ?: return
            val config = configs.find { it.calibrationKey == batch.configKey } ?: return
            if (!config.autoCalibrate) return

            val key = config.calibrationKey
            val samples = context.loadPendingCoastSamples(key) + batch.samples
            val crr = config.rollingResistanceCoefficient.toDoubleLocale()
            val fit = CoastFitter.fit(samples, crrEff = crr * config.coastSurfaceFactor)
            if (fit == null) {
                Timber.i("Coast calibration: ${samples.size} samples, not enough for a fit yet")
                savePendingCoastSamples(context, key, samples.takeLast(MAX_PENDING_SAMPLES))
                return
            }

            val configCdA = config.dragCoefficient.toDoubleLocale() * config.frontalArea.toDoubleLocale()
            if (!CoastFitter.isPlausible(fit, configCdA)) {
                Timber.i("Coast calibration: fit CdA ${fit.cdA} from ${fit.samples} samples is not plausible next to $configCdA, dropped")
                savePendingCoastSamples(context, key, emptyList())
                return
            }
            val updated = CoastFitter.blend(context.loadCalibrationFlow().first()[key], fit, configCdA)
            Timber.i("Coast calibration: fit CdA ${fit.cdA} from ${fit.samples}/${samples.size} samples -> $updated")
            saveCalibration(context, key, updated)
            savePendingCoastSamples(context, key, emptyList())
        }
    }

    companion object {
        private const val MAX_PENDING_SAMPLES = 1000
        // The Karoo's temperature sensor sits inside the warm unit and reads about this much high
        private const val KAROO_TEMPERATURE_BIAS_C = 5.0

        // Without a speed sensor the Karoo derives speed from GPS, which is too coarse to see deceleration
        fun isWheelSpeedSource(sourceId: String?, speedSensorIds: Set<String>): Boolean =
            sourceId != null && sourceId in speedSensorIds

        // Enabled speed sensors; a device that also measures power is a trainer, whose speed isn't road physics
        fun speedSensorIds(devices: List<SavedDevices.SavedDevice>): Set<String> =
            devices.filter {
                it.enabled && DataType.Source.SPEED in it.supportedDataTypes && DataType.Source.POWER !in it.supportedDataTypes
            }.map { it.id }.toSet()

        fun fromUid(extension: String, uid: String, karooSystem: KarooSystemService, context: Context): EstimatedPowerSource? {
            return uid.substringAfterLast("-").toIntOrNull()?.let {
                EstimatedPowerSource(extension, it, karooSystem, context)
            }
        }
    }
}


