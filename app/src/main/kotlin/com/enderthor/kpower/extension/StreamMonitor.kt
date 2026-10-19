package com.enderthor.kpower.extension

import com.enderthor.kpower.data.STREAM_TIMEOUT
import com.enderthor.kpower.data.WAIT_STREAMS_MEDIUM
import com.enderthor.kpower.data.WAIT_STREAMS_SHORT
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps a Karoo stream's latest state flowing, starting with a 0 placeholder.
 *
 * A stream that doesn't change (a bike standing still sends no new speed) is quiet, not broken:
 * after [quietMs] without a new state the subscription is simply renewed, which hands back the
 * current state at once and also recovers a subscription that stopped delivering. Upstream counted
 * quiet periods as failures, waited between retries and after four stopped listening for two
 * minutes, so riding off after a standstill of a minute and a half read 0 W until it woke up.
 * Only real errors back off, at most [WAIT_STREAMS_MEDIUM].
 */
@OptIn(FlowPreview::class)
fun monitoredStream(
    dataTypeID: String,
    subscribe: () -> Flow<StreamState>,
    quietMs: Long = STREAM_TIMEOUT,
): Flow<StreamState> = flow {
    val initialState = StreamState.Streaming(
        DataPoint(
            dataTypeId = dataTypeID,
            values = mapOf(DataType.Field.SINGLE to 0.0)
        )
    )
    emit(initialState)

    var errors = 0
    while (currentCoroutineContext().isActive) {
        try {
            subscribe()
                .distinctUntilChanged()
                .timeout(quietMs.milliseconds)
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
                            delay(WAIT_STREAMS_SHORT / 2)
                        }
                        else -> {
                            errors = 0
                            emit(state)
                        }
                    }
                }
        } catch (e: TimeoutCancellationException) {
            Timber.d("Stream $dataTypeID quiet for $quietMs ms, renewing the subscription")
        } catch (e: CancellationException) {
            // The subscription was closed from the Karoo side; try again shortly (a cancelled
            // collector stops here, since delay throws)
            delay(WAIT_STREAMS_SHORT / 3)
        } catch (e: Exception) {
            errors++
            val backoff = (1000L shl errors.coerceAtMost(4)).coerceAtMost(WAIT_STREAMS_MEDIUM)
            Timber.e(e, "Error in stream $dataTypeID, retrying in $backoff ms")
            delay(backoff)
        }
    }
}
