package com.enderthor.kpower.surface

import android.content.Context
import android.util.TypedValue
import android.widget.RemoteViews
import com.enderthor.kpower.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Live "Surface" data field: the surface KPower currently uses from the offline map */
class SurfaceDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            if (config.preview) {
                emitter.updateView(view(context, config, RoadSurface.GRAVEL.label))
                return@launch
            }
            SurfaceTracker.state.map { it.fieldText }.distinctUntilChanged().collect { text ->
                emitter.updateView(view(context, config, text))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun view(context: Context, config: ViewConfig, text: String) =
        RemoteViews(context.packageName, R.layout.view_surface).apply {
            setTextViewText(R.id.surface_text, text)
            // Long status texts ("No map access") get a smaller size so they fit
            val size = if (text.length > 8) config.textSize * 0.55f else config.textSize.toFloat()
            setTextViewTextSize(R.id.surface_text, TypedValue.COMPLEX_UNIT_SP, size)
        }

    companion object {
        const val TYPE_ID = "surface"
    }
}
