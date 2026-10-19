package com.enderthor.kpower.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamMonitorTest {

    private fun speed(v: Double) = StreamState.Streaming(
        DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to v), sourceId = "12345-123-1")
    )

    @Test
    fun movingOffAfterALongStandstillIsSeenAtOnce() = runTest {
        // The Karoo only sends a new speed when it changes: standing still sends nothing
        val karoo = MutableStateFlow<StreamState>(speed(0.0))
        var subscriptions = 0
        val seen = mutableListOf<Pair<Long, StreamState>>()
        backgroundScope.launch {
            monitoredStream(DataType.Type.SPEED, { karoo.onStart { subscriptions++ } }).collect { seen.add(currentTime to it) }
        }
        advanceTimeBy(150_000)          // two and a half minutes at the lights
        karoo.value = speed(5.0)        // ride off
        advanceTimeBy(500); runCurrent()

        val last = seen.last()
        assertEquals(speed(5.0), last.second)
        assertTrue("seen after ${last.first - 150_000} ms", last.first - 150_000 <= 500)
        // Quiet periods only renew the subscription, without waiting in between
        assertTrue("$subscriptions subscriptions", subscriptions in 7..9)
    }

    @Test
    fun errorsBackOffButNeverForMinutes() = runTest {
        var subscriptions = 0
        backgroundScope.launch {
            monitoredStream(DataType.Type.SPEED, { flow<StreamState> { subscriptions++; throw IllegalStateException("broken") } }).collect { }
        }
        advanceTimeBy(60_000); runCurrent()
        // 2, 4, 8, 10, 10, ... s between attempts
        assertTrue("$subscriptions subscriptions in a minute", subscriptions >= 6)
    }

    @Test
    fun searchingShowsThePlaceholderThenTheReading() = runTest {
        val karoo = MutableStateFlow<StreamState>(StreamState.Searching)
        val seen = mutableListOf<StreamState>()
        backgroundScope.launch { monitoredStream(DataType.Type.SPEED, { karoo }).collect { seen.add(it) } }
        advanceTimeBy(1_000); runCurrent()
        assertTrue(seen.all { it is StreamState.Streaming && it.dataPoint.singleValue == 0.0 })
        karoo.value = speed(4.0)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(speed(4.0), seen.last())
    }
}
