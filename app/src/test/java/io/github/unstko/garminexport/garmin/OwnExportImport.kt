package io.github.unstko.garminexport.garmin

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a regular test: runs the whole import against a real export when GARMIN_ZIP points
 * at one, and prints counts only — never values. No real data lives in this repository.
 */
class OwnExportImport {
    @Test
    fun import() {
        val zip = System.getenv("GARMIN_ZIP")
        assumeTrue("GARMIN_ZIP not set", zip != null)
        val export = GarminExport()
        val started = System.currentTimeMillis()
        File(zip!!).inputStream().buffered().use { ExportWalker.walk(it) { name, bytes -> export.accept(name, bytes) } }
        val d = export.finish()
        println("""
            fit files:            ${export.fitFiles}
            heart rate samples:   ${d.heartRate.size} in ${d.heartRate.hours().count()} hours
            step intervals:       ${d.steps.size}
            days:                 ${d.days.size} (resting HR ${d.days.count { it.restingHeartRate != null }}, respiration ${d.days.count { it.avgWakingRespiration != null }})
            workouts:             ${d.workouts.size} ${d.workouts.groupingBy { it.type }.eachCount()} with HR ${d.workouts.count { it.heartRate.isNotEmpty() }}
            sleep sessions:       ${d.sleep.size}
            spo2:                 ${d.spo2.size}
            vo2max:               ${d.vo2Max.size}
            first/last day:       ${d.days.firstOrNull()?.date} / ${d.days.lastOrNull()?.date}
            offsets seen:         ${d.steps.map { it.offset }.toSet()}
            seconds:              ${(System.currentTimeMillis() - started) / 1000}
            problems:             ${d.problems.size}
        """.trimIndent())
        d.problems.forEach { println("  - $it") }
    }
}
