package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.GearHub
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.previewConfigData
import com.enderthor.kpower.extension.jsonWithUnknownKeys
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays a recorded ride through [PowerPipeline], the same code the Karoo runs, and prints how
 * the estimate behaves. Skipped unless KPOWER_REPLAY_CSV points at a CSV with columns
 * t_ms,speed,cadence,grade,altitude,headwind,power (speed m/s, grade %, cadence empty when the
 * sensor wasn't streaming), e.g. exported from the ride's .fit file. The rider's setup comes from
 * KPOWER_REPLAY_CONFIG (a JSON config as KPower stores it) and KPOWER_REPLAY_RIDER_KG, so it
 * stays out of the repository. With KPOWER_REPLAY_OUT set, the per-second result is written there
 * as CSV (t_ms,power,driving_gear).
 *
 *     KPOWER_REPLAY_CSV=ride.csv KPOWER_REPLAY_CONFIG=config.json KPOWER_REPLAY_RIDER_KG=80 \
 *         ./gradlew testDebugUnitTest --tests '*RideReplayTest*' -i
 */
class RideReplayTest {

    private data class Row(val tMs: Long, val speed: Double, val cadence: Double?, val grade: Double, val altitude: Double, val headwind: Double)

    private val config: ConfigData = System.getenv("KPOWER_REPLAY_CONFIG")?.let { jsonWithUnknownKeys.decodeFromString<ConfigData>(File(it).readText()) }
        ?: previewConfigData[0].copy(
            bikeMass = "20", rollingResistanceCoefficient = "0.007", dragCoefficient = "0.9", frontalArea = "0.65",
            powerLoss = "5", ftp = "200", surface = KarooSurface.ASPHALT, gearHub = GearHub.NEXUS_8, chainrings = "42", sprockets = "20",
        )
    private val riderMass = System.getenv("KPOWER_REPLAY_RIDER_KG")?.toDouble() ?: 80.0

    @Test
    fun replay() {
        val path = System.getenv("KPOWER_REPLAY_CSV")
        assumeTrue("set KPOWER_REPLAY_CSV to replay a ride", path != null && File(path).exists())
        val rows = File(path!!).readLines().drop(1).map { line ->
            val c = line.split(",")
            Row(c[0].toLong(), c[1].toDouble(), c[2].toDoubleOrNull(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
        }
        val off = run(rows, floor = "0")
        val on = run(rows, floor = config.pedallingFloor)
        println("Replay of ${rows.size} s, ${config.gearRatios().size} gears entered")
        println("  gear driving the wheel: ${pct(on.driving.count { it }, rows.count { (it.cadence ?: 0.0) >= GearDetector.MIN_CADENCE })} of seconds at 30+ rpm")
        println("  gear check: ${on.check}")
        report("floor off", rows, off.power)
        report("floor on ", rows, on.power)
        System.getenv("KPOWER_REPLAY_OUT")?.let { out ->
            File(out).writeText("t_ms,power,driving_gear\n" + rows.indices.joinToString("\n") {
                "${rows[it].tMs},${"%.1f".format(java.util.Locale.ROOT, on.power[it])},${if (on.driving[it]) 1 else 0}"
            } + "\n")
            println("  wrote ${rows.size} rows to $out")
        }
        val floorW = config.pedallingFloor.toDouble()
        val zone = rows.indices.filter { on.driving[it] && off.power[it] < CyclingWattageEstimator.SOFT_FLOOR_KNEE * floorW }
        if (zone.isNotEmpty()) {
            val soft = zone.map { on.power[it] }.sorted()
            println("  near the floor (gear driving, estimate under %.0f W): %d s; soft floor reads %.1f / %.1f / %.1f W (min / median / max)".format(
                CyclingWattageEstimator.SOFT_FLOOR_KNEE * floorW, zone.size, soft.first(), soft[soft.size / 2], soft.last()))
        }
    }

    private class Result(val power: List<Double>, val driving: List<Boolean>, val check: String)

    private fun run(rows: List<Row>, floor: String): Result {
        var clock = 0L
        val pipeline = PowerPipeline { clock }
        val setup = PowerSetup(config.copy(pedallingFloor = floor), riderMass, speedSensorIds = setOf(WHEEL))
        val driving = ArrayList<Boolean>()
        val power = rows.map { r ->
            clock = r.tMs
            pipeline.update(PowerInputs(r.speed, WHEEL, r.cadence, cadenceSourceId = r.cadence?.let { "cadence" },
                slope = r.grade / 100, elevation = r.altitude, headwind = r.headwind), setup)
                .also { driving.add(pipeline.drivingGear) }
        }
        val check = pipeline.takeGearCheck()?.second?.let {
            "%.0f%% of %d steady seconds matched a gear, wheel %.0f mm".format(it.matchRate * 100, it.steadySamples, it.circumference * 1000)
        } ?: "too little steady pedalling"
        return Result(power, driving, check)
    }

    private fun report(name: String, rows: List<Row>, p: List<Double>) {
        val hard = rows.indices.filter { (rows[it].cadence ?: 0.0) >= 60 }
        val hardDown = hard.filter { rows[it].grade < -0.3 }
        val pedalling = rows.indices.filter { (rows[it].cadence ?: 0.0) >= 22 }
        val knee = CyclingWattageEstimator.SOFT_CAP_KNEE * maxOf(1.7 * config.ftp.toDouble(), 790.0)
        val mean = "%.0f".format(pedalling.sumOf { p[it] } / pedalling.size)
        val highest = "%.0f".format(p.max())
        println("  $name: 60+ rpm under 40 W ${pct(hard.count { p[it] < 40 }, hard.size)} (descents ${pct(hardDown.count { p[it] < 40 }, hardDown.size)}), " +
            "at 0 W ${pct(hard.count { p[it] == 0.0 }, hard.size)}, mean pedalling $mean W, highest $highest W, above the soft cap's knee ${p.count { it > knee }} s")
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "-" else "%.1f%%".format(100.0 * n / of)

    private companion object {
        const val WHEEL = "wheel"
    }
}
