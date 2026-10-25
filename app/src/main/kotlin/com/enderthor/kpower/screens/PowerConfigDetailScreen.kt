package com.enderthor.kpower.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.GearHub
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.extension.loadGearCheckFlow
import com.enderthor.kpower.vdevice.GearDetector
import com.enderthor.kpower.extension.loadCalibrationFlow
import com.enderthor.kpower.extension.loadPendingCoastCountFlow
import com.enderthor.kpower.extension.resetCalibration
import com.enderthor.kpower.extension.toDoubleLocale
import com.enderthor.kpower.vdevice.CoastFitter
import io.hammerhead.karooext.KarooSystemService
import com.enderthor.kpower.surface.OfflineMapSource
import com.enderthor.kpower.surface.SurfaceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(isCreating: Boolean, configdata: ConfigData, onSubmit: (updatedConfigData: ConfigData?) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val karooSystem = remember { KarooSystemService(ctx) }
    LaunchedEffect(Unit) {
        karooSystem.connect {}
    }

    var title by remember { mutableStateOf(configdata.name) }
    var bikeMass by remember { mutableStateOf(configdata.bikeMass) }
    var rollingResistanceCoefficient by remember { mutableStateOf(configdata.rollingResistanceCoefficient) }
    var dragCoefficient by remember { mutableStateOf(configdata.dragCoefficient) }
    var isActive by remember { mutableStateOf(configdata.isActive) }
    var powerLoss by remember { mutableStateOf(configdata.powerLoss) }
    var frontalArea by remember { mutableStateOf(configdata.frontalArea) }
    var headwind by remember { mutableStateOf(configdata.headwindconf) }
    var apikey by remember { mutableStateOf(configdata.apikey) }
    var isOpenWeather by remember { mutableStateOf(configdata.isOpenWeather) }
    var ftp by remember { mutableStateOf(configdata.ftp) }
    var surface by remember { mutableStateOf(configdata.surface) }
    var isforcepower by remember { mutableStateOf(configdata.isforcepower) }
    var autoCalibrate by remember { mutableStateOf(configdata.autoCalibrate) }
    var gearHub by remember { mutableStateOf(configdata.gearHub) }
    var chainrings by remember { mutableStateOf(configdata.chainrings) }
    var sprockets by remember { mutableStateOf(configdata.sprockets) }
    var customHubRatios by remember { mutableStateOf(configdata.customHubRatios) }
    var pedallingFloor by remember { mutableStateOf(configdata.pedallingFloor) }
    var autoSurface by remember { mutableStateOf(configdata.autoSurface) }
    var windFactor by remember { mutableStateOf(configdata.windFactor) }
    var logMotion by remember { mutableStateOf(configdata.logMotion) }
    var snowAdjust by remember { mutableStateOf(configdata.snowAdjust) }
    val surfaceState by SurfaceTracker.state.collectAsState()
    var mapStatus by remember { mutableStateOf<OfflineMapSource.Status?>(null) }
    LaunchedEffect(autoSurface) {
        mapStatus = withContext(Dispatchers.IO) { OfflineMapSource(hasAccess = { SurfaceTracker.hasMapAccess(ctx) }).status() }
    }
    val gearChecks by remember { ctx.loadGearCheckFlow() }.collectAsState(initial = emptyMap())
    val calibrations by remember { ctx.loadCalibrationFlow() }.collectAsState(initial = emptyMap())
    val pendingCounts by remember { ctx.loadPendingCoastCountFlow() }.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()


    fun getUpdatedConfigData(): ConfigData = ConfigData(
        configdata.id, title, isActive, bikeMass, rollingResistanceCoefficient, dragCoefficient, frontalArea, powerLoss, headwind, isOpenWeather, apikey,ftp,surface,isforcepower,autoCalibrate,
        gearHub, chainrings, sprockets, customHubRatios, pedallingFloor, autoSurface, windFactor, logMotion, snowAdjust
    )

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text(if (isCreating) "Create Power Config" else "Edit Power Config") })
        Column(modifier = Modifier
            .padding(5.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            OutlinedTextField(value = bikeMass, modifier = Modifier.fillMaxWidth(),
                onValueChange = { bikeMass = it },
                label = { Text("Bike Mass") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(value = rollingResistanceCoefficient, modifier = Modifier.fillMaxWidth(),
                onValueChange = { rollingResistanceCoefficient = it },
                label = { Text("Crr") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            apply {
                val dropdownOptions = KarooSurface.entries.toList()
                    .map { unit -> DropdownOption(unit.factor.toString(), unit.surface) }
                val dropdownInitialSelection by remember(surface) {
                    mutableStateOf(dropdownOptions.find { option -> option.id == surface.factor.toString() }!!)
                }
                KarooKeyDropdown(
                    remotekey = "Surface", options = dropdownOptions, selectedOption = dropdownInitialSelection
                ) { selectedOption ->
                    surface =
                        KarooSurface.entries.find { unit -> unit.factor.toString() == selectedOption.id }!!
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoSurface, onCheckedChange = { autoSurface = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Detect surface from the Karoo's offline map? (the surface above is used where the map has no road)")
            }
            if (autoSurface) {
                val status = when (val st = mapStatus) {
                    null -> "Checking the offline maps…"
                    OfflineMapSource.Status.NoAccess -> "No access to the offline maps yet. Grant it once over adb:\nadb shell pm grant com.enderthor.kpower android.permission.WRITE_EXTERNAL_STORAGE"
                    OfflineMapSource.Status.NoMaps -> "No offline maps found. Download a region in the Karoo's map settings."
                    is OfflineMapSource.Status.Ready -> "Maps: ${st.maps.joinToString()}" +
                        if (surfaceState.enabled) "\nNow: ${surfaceState.fieldText}" else ""
                }
                Text(status + "\nMap data © OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(value = dragCoefficient, modifier = Modifier.fillMaxWidth(),
                onValueChange = { dragCoefficient = it },
                label = { Text("Cdr") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(value = frontalArea, modifier = Modifier.fillMaxWidth(),
                onValueChange = { frontalArea = it },
                label = { Text("Frontal Area") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                suffix = { Text("m2") },
            )
            OutlinedTextField(value = powerLoss, modifier = Modifier.fillMaxWidth(),
                onValueChange = { powerLoss = it },
                label = { Text("Power Loss") },
                suffix = { Text("%") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(value = ftp, modifier = Modifier.fillMaxWidth(),
                onValueChange = { ftp = it },
                label = { Text("FTP") },
                suffix = { Text("W") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(value = apikey.toString(), modifier = Modifier.fillMaxWidth(),
                onValueChange = { apikey = it },
                label = { Text("API OpenWeather") },
                singleLine = true,
                enabled = isOpenWeather
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isOpenWeather, onCheckedChange = {
                    isOpenWeather = it
                   // if (it) isActive = false
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text("OpenMeteo or OpenWeather(checked)?")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = snowAdjust, onCheckedChange = { snowAdjust = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text("More rolling resistance with snow on the ground? (snow depth from Open-Meteo)")
            }

            OutlinedTextField(value = windFactor, modifier = Modifier.fillMaxWidth(),
                onValueChange = { windFactor = it },
                label = { Text("Wind factor") },
                supportingText = { Text("How much of the weather service's wind to use: 0.65 by default (forecast wind is only a rough guide to the wind you meet), up to 1 to trust it fully, 0 to ignore wind. 0–1.5.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = !isforcepower, onCheckedChange = {
                    isforcepower = !it
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Use cadence sensor? (off: always calculate power)")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoCalibrate, onCheckedChange = {
                    autoCalibrate = it
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Auto-calibrate from coasting? (needs cadence and wheel speed sensors)")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = logMotion, onCheckedChange = { logMotion = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Record the Karoo's motion sensor in ride files? (for tuning; doesn't change power)")
            }

            if (!isCreating) {
                // Follows the fields as they are edited: learned values belong to the setup entered above
                val calibrationKey = getUpdatedConfigData().calibrationKey
                val calibration = calibrations[calibrationKey]
                val pending = pendingCounts[calibrationKey] ?: 0
                val pendingText = if (pending > 0) {
                    "\n$pending coasting samples from earlier rides wait for the next fit (needs ${CoastFitter.MIN_FIT_SAMPLES}+)."
                } else ""
                val text = if (calibration == null) {
                    "Learned: no fit yet for these values. Cd × Area and Crr above are used." + pendingText
                } else {
                    "Learned CdA %.3f (Cd × Area above: %.3f)\nCrr is not learned: the Crr above is used\n%d samples over %d rides".format(
                        calibration.cdA,
                        dragCoefficient.toDoubleLocale() * frontalArea.toDoubleLocale(),
                        calibration.samples, calibration.rides
                    ) + pendingText + if (autoCalibrate) "" else "\nNot used while auto-calibrate is off."
                }
                Text(text, style = MaterialTheme.typography.bodySmall)
                if (calibration != null || pending > 0) {
                    TextButton(onClick = { scope.launch { resetCalibration(ctx, calibrationKey) } }) {
                        Text("Reset learned values")
                    }
                }
            }

            Text("Gearing (optional): with a cadence and a speed sensor, KPower can tell when your gear is driving the wheel and then won't show less than the floor below.", style = MaterialTheme.typography.bodySmall)

            apply {
                val hubOptions = GearHub.entries.map { DropdownOption(it.name, it.label) }
                val hubSelection by remember(gearHub) { mutableStateOf(hubOptions.first { it.id == gearHub.name }) }
                KarooKeyDropdown(remotekey = "Gear hub", options = hubOptions, selectedOption = hubSelection) { selected ->
                    gearHub = GearHub.valueOf(selected.id)
                }
            }

            OutlinedTextField(value = chainrings, modifier = Modifier.fillMaxWidth(),
                onValueChange = { chainrings = it },
                label = { Text("Chainring teeth (e.g. 46 or 34-50)") },
                singleLine = true
            )
            OutlinedTextField(value = sprockets, modifier = Modifier.fillMaxWidth(),
                onValueChange = { sprockets = it },
                label = { Text("Sprocket teeth (e.g. 22 or 11-13-15-17)") },
                singleLine = true
            )
            OutlinedTextField(value = customHubRatios, modifier = Modifier.fillMaxWidth(),
                onValueChange = { customHubRatios = it },
                label = { Text("Hub ratios (e.g. 0.75 1 1.33)") },
                singleLine = true,
                enabled = gearHub == GearHub.CUSTOM
            )
            OutlinedTextField(value = pedallingFloor, modifier = Modifier.fillMaxWidth(),
                onValueChange = { pedallingFloor = it },
                label = { Text("Minimum power while a gear drives the wheel") },
                suffix = { Text("W") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            run {
                val ratios = getUpdatedConfigData().gearRatios()
                val check = gearChecks[configdata.id.toString()]
                val wheel = check?.circumference ?: GearDetector.DEFAULT_CIRCUMFERENCE
                val text = if (ratios.isEmpty()) {
                    "Gearing not entered: power can drop to 0 W on descents while you pedal."
                } else {
                    val gears = ratios.joinToString(" · ") { "%.2f".format(it * wheel) }
                    val last = when {
                        check == null -> "No ride checked yet."
                        check.matchRate >= GearDetector.GOOD_MATCH_RATE ->
                            "Last ride: %.0f%% of steady pedalling matched these gears.".format(check.matchRate * 100)
                        else -> "Last ride: only %.0f%% of steady pedalling matched these gears. Check the teeth and hub.".format(check.matchRate * 100)
                    }
                    "${ratios.size} gears, m per crank turn (wheel ${"%.0f".format(wheel * 1000)} mm): $gears\n$last"
                }
                Text(text, style = MaterialTheme.typography.bodySmall)
            }

            FilledTonalButton(modifier = Modifier
                .fillMaxWidth()
                .height(50.dp), onClick = {
                onSubmit(getUpdatedConfigData())
            }) {
                Icon(Icons.Default.Done, contentDescription = "Save Power Config")
                Spacer(modifier = Modifier.width(5.dp))
                Text("Save")
            }

            FilledTonalButton(modifier = Modifier
                .fillMaxWidth()
                .height(50.dp), onClick = {
                onCancel()
            }) {
                Icon(Icons.Default.Close, contentDescription = "Cancel Editing")
                Spacer(modifier = Modifier.width(5.dp))
                Text("Cancel")
            }
        }
    }
}