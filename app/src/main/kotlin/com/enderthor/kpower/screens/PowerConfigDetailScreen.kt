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
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.extension.loadCalibrationFlow
import com.enderthor.kpower.extension.loadPendingCoastCountFlow
import com.enderthor.kpower.extension.resetCalibration
import com.enderthor.kpower.extension.toDoubleLocale
import com.enderthor.kpower.vdevice.CoastFitter
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch


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
    val calibrations by remember { ctx.loadCalibrationFlow() }.collectAsState(initial = emptyMap())
    val pendingCounts by remember { ctx.loadPendingCoastCountFlow() }.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()


    fun getUpdatedConfigData(): ConfigData = ConfigData(
        configdata.id, title, isActive, bikeMass, rollingResistanceCoefficient, dragCoefficient, frontalArea, powerLoss, headwind, isOpenWeather, apikey,ftp,surface,isforcepower,autoCalibrate
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