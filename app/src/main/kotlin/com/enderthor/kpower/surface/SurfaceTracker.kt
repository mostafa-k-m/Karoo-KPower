package com.enderthor.kpower.surface

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.enderthor.kpower.extension.loadPreferencesFlow
import com.enderthor.kpower.extension.streamLocation
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import timber.log.Timber

/** What the surface detection currently knows */
data class SurfaceState(
    val enabled: Boolean = false,
    val status: OfflineMapSource.Status? = null,
    val surface: RoadSurface = RoadSurface.UNKNOWN,
) {
    /** Surface factor for the power estimate, or null to use the config's own surface */
    val factor: Double? get() = if (enabled) surface.factor else null

    /** Short text for the live data field */
    val fieldText: String
        get() = when {
            !enabled -> "Off"
            status is OfflineMapSource.Status.NoAccess -> "No map access"
            status is OfflineMapSource.Status.NoMaps -> "No map"
            surface == RoadSurface.UNKNOWN -> "No road"
            else -> surface.label
        }
}

/**
 * Follows the Karoo's position and looks up the road surface in its offline map, once per GPS
 * fix, while the first config has "Detect surface from the offline map" on.
 */
object SurfaceTracker {

    private val mutableState = MutableStateFlow(SurfaceState())
    val state: StateFlow<SurfaceState> = mutableState.asStateFlow()

    // The offline map folder is readable only with the sdcard_rw group, which comes with this permission
    fun hasMapAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context, karooSystem: KarooSystemService, scope: CoroutineScope): Job {
        val source = OfflineMapSource(hasAccess = { hasMapAccess(context) })
        return context.loadPreferencesFlow()
            .map { configs -> configs.firstOrNull()?.autoSurface == true }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    mutableState.value = SurfaceState()
                    return@flatMapLatest emptyFlow()
                }
                val matcher = SurfaceMatcher()
                karooSystem.streamLocation()
                    .onStart { mutableState.value = SurfaceState(enabled = true, status = source.status()) }
                    .onEach { location ->
                        val status = source.status()
                        val ways = if (status is OfflineMapSource.Status.Ready) source.waysAround(location.lat, location.lng) else emptyList()
                        val surface = matcher.update(location.lat, location.lng, location.orientation, ways, System.currentTimeMillis())
                        val next = SurfaceState(enabled = true, status = status, surface = surface)
                        if (next.surface != mutableState.value.surface) Timber.i("Surface: ${next.surface}")
                        mutableState.value = next
                    }
            }
            .catch { e -> Timber.e(e, "Surface detection stopped") }
            .launchIn(scope)
    }
}
