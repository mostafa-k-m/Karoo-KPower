package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.GearHub
import com.enderthor.kpower.data.previewConfigData
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays a recorded ride through the power pipeline and prints how the estimate behaves.
 * Skipped unless KPOWER_REPLAY_CSV points at a CSV with columns
 * t_ms,speed,cadence,grade,altitude,headwind,power (speed m/s, grade %, cadence empty when
 * the sensor wasn't streaming), e.g. exported from the ride's .fit file.
 *
 *     KPOWER_REPLAY_CSV=ride.csv ./gradlew testDebugUnitTest --tests '*RideReplayTest*' -i
 */
class RideReplayTest {

    private data class Row(val tMs: Long, val speed: Double, val cadence: Double?, val grade: Double, val altitude: Double, val headwind: Double, val recorded: Double?)

    // Example settings; edit to replay another setup
    private val config = previewConfigData[0].copy(
        bikeMass = "20", rollingResistanceCoefficient = "0.007", dragCoefficient = "0.9", frontalArea = "0.65",
        powerLoss = "5", ftp = "200", surface = com.enderthor.kpower.data.KarooSurface.ASPHALT,
        gearHub = GearHub.NEXUS_8, chainrings = "42", sprockets = "20", pedallingFloor = "30",
    )
    private val riderMass = 80.0

    @Test
    fun replay() {
        val path = System.getenv("KPOWER_REPLAY_CSV")
        assumeTrue("set KPOWER_REPLAY_CSV to replay a ride", path != null && File(path).exists())
        val rows = File(path!!).readLines().drop(1).map { line ->
            val c = line.split(",")
            Row(c[0].toLong(), c[1].toDouble(), c[2].toDoubleOrNull(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble(), c[6].toDoubleOrNull())
        }
        val off = run(rows, floor = false)
        val on = run(rows, floor = true)
        println("Replay of ${rows.size} s, ${config.gearRatios().size} gears entered")
        println("  gear driving the wheel: ${pct(on.driving.count { it }, rows.count { (it.cadence ?: 0.0) >= GearDetector.MIN_CADENCE })} of seconds at 30+ rpm")
        println("  wheel size learned: %.0f mm; ride check: %s".format(on.circumference * 1000, on.check))
        report("floor off", rows, off.power)
        report("floor on ", rows, on.power)
        val lowHard = rows.indices.filter { (rows[it].cadence ?: 0.0) >= 60 && off.power[it] < 40 }
        println("  of ${lowHard.size} s at 60+ rpm under 40 W (floor off), ${pct(lowHard.count { on.driving[it] }, lowHard.size)} had a gear driving the wheel")
        val raised = rows.indices.count { on.power[it] > off.power[it] + 0.5 }
        println("  floor raised the reading in $raised s; mean pedalling power +%.1f W".format(
            rows.indices.filter { (rows[it].cadence ?: 0.0) >= 22 }.let { idx -> idx.sumOf { on.power[it] - off.power[it] } / idx.size }))
    }

    private class Result(val power: List<Double>, val driving: List<Boolean>, val circumference: Double, val check: String)

    private fun run(rows: List<Row>, floor: Boolean): Result {
        var clock = 0L
        val tracker = AccelerationTracker { clock }
        val gears = GearDetector(config.gearRatios())
        val driving = ArrayList<Boolean>()
        val power = rows.map { r ->
            clock = r.tMs
            val a = tracker.update(r.speed)
            val drivingGear = r.cadence != null && gears.update(r.speed, r.cadence, isWheelSpeed = true)
            driving.add(drivingGear)
            CyclingWattageEstimator(
                slope = r.grade / 100,
                totalMass = riderMass + config.bikeMass.toDouble(),
                rollingResistanceCoefficient = config.rollingResistanceCoefficient.toDouble(),
                dragCoefficient = config.dragCoefficient.toDouble(),
                frontalArea = config.frontalArea.toDouble(),
                speed = r.speed,
                windSpeed = r.headwind,
                powerLoss = config.powerLoss.toDouble() / 100,
                elevation = r.altitude,
                ftp = config.ftp.toDouble(),
                cadence = r.cadence ?: 0.0,
                surface = config.surface.factor,
                isforcepower = r.cadence == null,
                acceleration = a,
                minPower = if (floor && drivingGear) config.pedallingFloor.toDouble() else 0.0,
            ).calculateCyclingWattage()
        }
        val check = gears.takeCheck()?.let { "%.0f%% of %d steady seconds matched a gear".format(it.matchRate * 100, it.steadySamples) } ?: "too little steady pedalling"
        return Result(power, driving, gears.circumference, check)
    }

    private fun report(name: String, rows: List<Row>, p: List<Double>) {
        val hard = rows.indices.filter { (rows[it].cadence ?: 0.0) >= 60 }
        val hardDown = hard.filter { rows[it].grade < -0.3 }
        val pedalling = rows.indices.filter { (rows[it].cadence ?: 0.0) >= 22 }
        println("  $name: 60+ rpm under 40 W ${pct(hard.count { p[it] < 40 }, hard.size)} (descents ${pct(hardDown.count { p[it] < 40 }, hardDown.size)}), " +
            "at 0 W ${pct(hard.count { p[it] == 0.0 }, hard.size)}, above 450 W ${p.count { it > 450 }} s, " +
            "mean pedalling %.0f W".format(pedalling.sumOf { p[it] } / pedalling.size))
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "-" else "%.1f%%".format(100.0 * n / of)
}
