package com.enderthor.kpower.vdevice

import android.os.SystemClock
import com.enderthor.kpower.data.CoastCalibration
import com.enderthor.kpower.vdevice.CyclingWattageEstimator.Companion.DYNAMIC_ROLLING_FORCE_N
import com.enderthor.kpower.vdevice.CyclingWattageEstimator.Companion.GRAVITY
import com.enderthor.kpower.vdevice.CyclingWattageEstimator.Companion.ROTATING_MASS_KG
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.serialization.Serializable

/**
 * One coasting observation. The rider adds no power, so the measured deceleration is caused by
 * the resistances alone, using the same model as [CyclingWattageEstimator]:
 *
 *     -(m + m_rot)·a - m·g·sin θ - 0.1·cos θ = CrrEff · m·g·cos θ + CdA · ½·ρ·(v + wind)²
 *
 * which is linear in CrrEff (Crr × surface factor) and CdA.
 */
@Serializable
data class CoastSample(
    val timeMs: Long,
    val speed: Double,        // m/s, mean over the acceleration window
    val acceleration: Double, // m/s²
    val slope: Double,        // rise / run
    val headwind: Double,     // m/s, positive against the rider
    val airDensity: Double,   // kg/m³
    val totalMass: Double,    // kg, rider + bike
) {
    private val angle get() = atan(slope)

    /** Force (N) that rolling resistance and drag have to explain */
    val resistanceForce: Double
        get() = -(totalMass + ROTATING_MASS_KG) * acceleration -
            GRAVITY * sin(angle) * totalMass - DYNAMIC_ROLLING_FORCE_N * cos(angle)

    val rollingBasis: Double get() = GRAVITY * cos(angle) * totalMass
    val dragBasis: Double get() = 0.5 * airDensity * (speed + headwind).pow(2)

    fun predictedForce(cdA: Double, crrEff: Double) = crrEff * rollingBasis + cdA * dragBasis
}

/**
 * Collects [CoastSample]s from segments where the rider is freewheeling.
 *
 * A segment starts when the cadence sensor reads 0 rpm on wheel speed within the speed, grade and
 * wind limits. The first [WARMUP_MS] are skipped so the acceleration window holds no pedalling,
 * and the last [END_TRIM_MS] are dropped because the cadence sensor reports pedalling late.
 * Deceleration well beyond the current model means braking: the segment ends there and its last
 * acceleration window is dropped.
 */
class CoastDetector(private val now: () -> Long = SystemClock::elapsedRealtime) {

    data class Input(
        val configKey: String,    // ConfigData.calibrationKey
        val useCadenceSensor: Boolean,
        val cadence: Double?,     // null when the cadence stream isn't streaming
        val isWheelSpeed: Boolean,
        val speed: Double,        // m/s, latest
        val meanSpeed: Double,    // m/s, mean over the acceleration window
        val acceleration: Double, // m/s²
        val slope: Double,        // rise / run
        val headwind: Double,     // m/s
        val elevation: Double,    // m
        val totalMass: Double,    // kg
        val cdA: Double,          // values currently used, to recognise braking
        val crrEff: Double,
    )

    data class Batch(val configKey: String, val samples: List<CoastSample>)

    private val segment = ArrayList<CoastSample>()
    private val accepted = ArrayList<CoastSample>()
    private var configKey: String? = null
    private var segmentStart = NOT_COASTING
    private var lastUpdate = 0L
    private var braked = false

    @Synchronized
    fun update(input: Input) {
        val t = now()
        if (configKey != input.configKey) {
            accepted.clear()
            discardSegment()
            configKey = input.configKey
        }
        if (segmentStart != NOT_COASTING && t - lastUpdate > AccelerationTracker.MAX_GAP_MS) discardSegment()
        lastUpdate = t

        if (!isCoasting(input)) {
            closeSegment(t, END_TRIM_MS)
            braked = false
            return
        }
        if (braked) return
        if (segmentStart == NOT_COASTING) segmentStart = t
        if (t - segmentStart < WARMUP_MS) return

        val sample = CoastSample(
            timeMs = t,
            speed = input.meanSpeed,
            acceleration = input.acceleration,
            slope = input.slope,
            headwind = input.headwind,
            airDensity = CyclingWattageEstimator.airDensity(input.elevation),
            totalMass = input.totalMass,
        )
        if (sample.resistanceForce - sample.predictedForce(input.cdA, input.crrEff) > BRAKING_MARGIN_N) {
            closeSegment(t, AccelerationTracker.COAST_WINDOW_MS)
            braked = true
            return
        }
        segment.add(sample)
    }

    /** Hands over the accepted samples (the open segment stays) */
    @Synchronized
    fun drain(): Batch? {
        val key = configKey ?: return null
        if (accepted.isEmpty()) return null
        val batch = Batch(key, accepted.toList())
        accepted.clear()
        return batch
    }

    @Synchronized
    fun sampleCount() = accepted.size

    private fun isCoasting(input: Input) =
        input.useCadenceSensor &&
            input.cadence != null && input.cadence < 1.0 &&
            input.isWheelSpeed &&
            input.speed in MIN_SPEED..MAX_SPEED &&
            abs(input.slope) <= MAX_GRADE &&
            abs(input.headwind) <= MAX_HEADWIND &&
            input.speed + input.headwind >= MIN_AIRSPEED

    private fun closeSegment(t: Long, trimMs: Long) {
        if (segmentStart == NOT_COASTING) return
        segment.removeAll { it.timeMs > t - trimMs }
        val windRange = (segment.maxOfOrNull { it.headwind } ?: 0.0) - (segment.minOfOrNull { it.headwind } ?: 0.0)
        if (segment.size >= MIN_SEGMENT_SAMPLES && windRange <= MAX_WIND_RANGE) {
            accepted.addAll(segment)
            while (accepted.size > MAX_SAMPLES) accepted.removeAt(0)
        }
        discardSegment()
    }

    private fun discardSegment() {
        segment.clear()
        segmentStart = NOT_COASTING
    }

    companion object {
        private const val NOT_COASTING = -1L
        const val WARMUP_MS = AccelerationTracker.COAST_WINDOW_MS
        const val END_TRIM_MS = 2000L
        const val MIN_SEGMENT_SAMPLES = 3
        const val MIN_SPEED = 10.0 / 3.6
        const val MAX_SPEED = 35.0 / 3.6
        const val MAX_GRADE = 0.03
        const val MAX_HEADWIND = 8.0      // m/s
        const val MAX_WIND_RANGE = 2.0    // m/s within one segment: gusts or turning
        const val MIN_AIRSPEED = 1.0      // m/s, the drag model needs air coming from the front
        const val BRAKING_MARGIN_N = 30.0
        const val MAX_SAMPLES = 5000
    }
}

/**
 * Least-squares fit of CdA over coasting samples, with outlier rejection.
 *
 * Crr comes from the config instead of being fitted: it can be looked up for the tyre (e.g. on
 * bicyclerollingresistance.com), while the Karoo's grade noise (about ±0.5%, i.e. ±7 N on a
 * 145 kg bike) is as large as the whole rolling resistance. Fitting both gave impossible Crr
 * values on a real ride; with one unknown the fit is well determined.
 */
object CoastFitter {

    data class Fit(val cdA: Double, val samples: Int)

    fun fit(samples: List<CoastSample>, crrEff: Double): Fit? {
        var kept = samples
        for (i in 0 until ITERATIONS) {
            val cdA = leastSquares(kept, crrEff) ?: return null
            // Robust spread (MAD), so braking or a bad grade reading doesn't hide itself.
            // Re-select from all samples: good ones the first, skewed fit rejected can come back.
            val residuals = samples.map { it.resistanceForce - it.predictedForce(cdA, crrEff) }
            val center = median(residuals)
            val sigma = maxOf(MIN_SIGMA_N, 1.4826 * median(residuals.map { abs(it - center) }))
            val next = samples.filterIndexed { j, _ -> abs(residuals[j] - center) <= OUTLIER_SIGMAS * sigma }
            if (next == kept) break
            kept = next
        }
        if (kept.size < MIN_FIT_SAMPLES) return null
        val cdA = leastSquares(kept, crrEff) ?: return null
        // A negative value means the data (usually the grade) is off, not that the bike is unusual
        if (cdA <= 0.0) return null
        return Fit(cdA, kept.size)
    }

    /** Moves the stored CdA a step towards [fit], so one odd ride can't swing it */
    fun blend(current: CoastCalibration?, fit: Fit, configCdA: Double, configCrr: Double): CoastCalibration {
        val oldCdA = current?.cdA ?: configCdA
        val alpha = MAX_BLEND * min(1.0, fit.samples / FULL_WEIGHT_SAMPLES)
        val cdA = oldCdA + alpha * (fit.cdA.coerceIn(CDA_RANGE) - oldCdA)
        return CoastCalibration(
            cdA = cdA.coerceIn(CDA_RANGE),
            crr = configCrr,
            samples = (current?.samples ?: 0) + fit.samples,
            rides = (current?.rides ?: 0) + 1,
        )
    }

    private fun leastSquares(samples: List<CoastSample>, crrEff: Double): Double? {
        var sxx = 0.0
        var sxy = 0.0
        for (s in samples) {
            val x = s.dragBasis
            sxx += x * x
            sxy += x * (s.resistanceForce - crrEff * s.rollingBasis)
        }
        return if (sxx > 0.0) sxy / sxx else null
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private const val ITERATIONS = 4
    private const val OUTLIER_SIGMAS = 3.0
    private const val MIN_SIGMA_N = 2.0
    const val MIN_FIT_SAMPLES = 30
    const val MAX_BLEND = 0.25
    const val FULL_WEIGHT_SAMPLES = 200.0
    val CDA_RANGE = 0.3..1.0
}
