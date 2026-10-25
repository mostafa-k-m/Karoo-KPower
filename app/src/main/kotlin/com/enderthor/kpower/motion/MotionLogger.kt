package com.enderthor.kpower.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Mean of each accelerometer axis over one period, in the device's own axes (m/s², gravity included) */
data class MotionMean(val x: Double, val y: Double, val z: Double, val samples: Int)

/** Sums accelerometer readings until [take] hands over their mean and starts again */
class MotionAverager {
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0
    private var n = 0

    @Synchronized
    fun add(x: Float, y: Float, z: Float) {
        sx += x; sy += y; sz += z; n++
    }

    /** The mean since the last call, or null when no reading came in */
    @Synchronized
    fun take(): MotionMean? {
        if (n == 0) return null
        val mean = MotionMean(sx / n, sy / n, sz / n, n)
        sx = 0.0; sy = 0.0; sz = 0.0; n = 0
        return mean
    }
}

/**
 * Listens to the Karoo's accelerometer while a ride records. Along the direction of travel it
 * measures acceleration + g·sin(grade), the two terms the power estimate gets from speed and
 * altitude with a lag; logged to the ride file first, to check it against them before it is used.
 */
class MotionLogger(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val averager = MotionAverager()

    /** False when the device has no accelerometer */
    fun start(): Boolean {
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        return sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    fun take(): MotionMean? = averager.take()

    override fun onSensorChanged(event: SensorEvent) {
        averager.add(event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
