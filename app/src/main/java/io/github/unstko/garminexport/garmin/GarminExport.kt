package io.github.unstko.garminexport.garmin

import io.github.unstko.garminexport.fit.FitException
import io.github.unstko.garminexport.fit.FitMessage
import io.github.unstko.garminexport.fit.FitReader
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Collects a Garmin export file by file ([accept]) and turns it into [ImportData] ([finish]).
 * Files may arrive in any order; everything that spans files (step counters, duplicates
 * from repeated syncs, time zones) is resolved in [finish].
 */
class GarminExport {
    /**
     * UTC offsets as the watch's clock had them, from the FIT files only. Garmin's daily
     * summaries switch a travel day to the new zone from its new midnight, while the watch
     * keeps counting in the old one until it syncs; mixing both splits step counters wrongly.
     */
    private val offsets = HashMap<Instant, ZoneOffset>()
    private var heartRate = LongArray(1 shl 16)
    private var heartRateCount = 0
    private val stepPoints = HashMap<Pair<Long, Int>, StepPoint>() // (unix second, activity type)
    private val workouts = ArrayList<Workout>()
    private val days = HashMap<LocalDate, DailySummary>()
    private val sleep = HashMap<Instant, SleepSession>()
    private val spo2 = HashMap<Instant, Double>()
    private val vo2Max = HashMap<LocalDate, Pair<Instant, Double>>()
    private val problems = ArrayList<String>()
    var fitFiles = 0; private set
    private var wholeDays = 0

    private class StepPoint(var cycles: Long, var activeMs: Long?)

    fun accept(name: String, bytes: ByteArray) {
        when {
            name.lowercase().endsWith(".fit") -> acceptFit(name, bytes)
            "UDSFile_" in name -> json(name, bytes)?.let(::acceptDays)
            "sleepData" in name -> json(name, bytes)?.let(::acceptSleep)
            "MetricsMaxMetData" in name -> json(name, bytes)?.let(::acceptVo2Max)
        }
    }

    private fun json(name: String, bytes: ByteArray): JSONArray? =
        runCatching { JSONArray(String(bytes, Charsets.UTF_8)) }
            .onFailure { problems += "${shortName(name)}: not a JSON array" }
            .getOrNull()

    // ---- FIT -------------------------------------------------------------------------

    private fun acceptFit(name: String, bytes: ByteArray) {
        val messages = try {
            FitReader.read(bytes)
        } catch (e: FitException) {
            problems += "${shortName(name)}: ${e.message}; read without CRC check"
            runCatching { FitReader.read(bytes, checkCrc = false) }.getOrElse {
                problems += "${shortName(name)}: skipped (${it.message})"
                return
            }
        }
        fitFiles++
        when (messages.firstOrNull { it.global == FILE_ID }?.long(0)?.toInt()) {
            FILE_MONITORING_A, FILE_MONITORING_B -> acceptMonitoring(messages)
            FILE_ACTIVITY -> acceptActivity(messages)
        }
    }

    private fun acceptMonitoring(messages: List<FitMessage>) {
        for (m in messages) {
            val ts = m.timestamp ?: continue
            when (m.global) {
                MONITORING_INFO -> m.long(0)?.let { local -> offsets[unix(ts)] = offset(local - ts) }
                MONITORING -> {
                    m.long(27)?.let { bpm -> if (bpm in 1..255) addHeartRate(unixSeconds(ts), bpm.toInt()) }
                    // activity_type is often only present inside current_activity_type_intensity
                    // (field 24), in its low five bits.
                    val type = (m.long(5) ?: m.long(24)?.and(0x1F))?.toInt()
                    val cycles = m.long(3)
                    if (cycles != null && (type == ACTIVITY_WALKING || type == ACTIVITY_RUNNING)) {
                        // Repeated syncs deliver the same minute twice; the later one has counted more.
                        val point = stepPoints.getOrPut(unixSeconds(ts) to type) { StepPoint(cycles, m.long(4)) }
                        if (cycles > point.cycles) { point.cycles = cycles; point.activeMs = m.long(4) }
                    }
                }
            }
        }
    }

    private fun acceptActivity(messages: List<FitMessage>) {
        val activity = messages.firstOrNull { it.global == ACTIVITY && it.timestamp != null && it.long(5) != null }
        val offset = activity?.let { offset(it.long(5)!! - it.timestamp!!) }
        val samples = messages.filter { it.global == RECORD && it.timestamp != null && (it.long(3) ?: 0) in 1..255 }
            .map { unixSeconds(it.timestamp!!) to it.long(3)!!.toInt() }
            .distinctBy { it.first }
            .sortedBy { it.first }
        for (s in messages.filter { it.global == SESSION }) {
            val start = s.long(2)?.let(::unix) ?: continue
            val elapsed = s.long(7)?.let { it / 1000 }
            val end = s.timestamp?.let(::unix)?.takeIf { it > start }
                ?: elapsed?.let { start.plusSeconds(it) } ?: continue
            val off = offset ?: offsets[start] ?: ZoneOffset.UTC
            val hr = samples.filter { it.first in start.epochSecond..end.epochSecond }
                .map { HeartRateSample(Instant.ofEpochSecond(it.first), it.second, off) }
            workouts += Workout(start, end, off, workoutType(s.long(5)?.toInt(), s.long(6)?.toInt()), hr)
        }
    }

    private fun addHeartRate(unixSeconds: Long, bpm: Int) {
        if (heartRateCount == heartRate.size) heartRate = heartRate.copyOf(heartRate.size * 2)
        heartRate[heartRateCount++] = HeartRateSeries.pack(unixSeconds, bpm)
    }

    // ---- JSON ------------------------------------------------------------------------

    private fun acceptDays(days: JSONArray) {
        for (d in objects(days)) {
            val date = d.optString("calendarDate").takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: continue
            val startGmt = gmt(d, "wellnessStartTimeGmt") ?: continue
            val endGmt = gmt(d, "wellnessEndTimeGmt") ?: continue
            val startLocal = local(d, "wellnessStartTimeLocal")
            val offset = startLocal?.let { offset(ChronoUnit.SECONDS.between(startGmt.atOffset(ZoneOffset.UTC).toLocalDateTime(), it)) }
                ?: ZoneOffset.UTC
            this.days[date] = DailySummary(
                date = date, start = startGmt, end = endGmt, offset = offset,
                totalSteps = d.optLongOrNull("totalSteps"),
                distanceMeters = d.optDoubleOrNull("totalDistanceMeters"),
                floorsAscendedMeters = d.optDoubleOrNull("floorsAscendedInMeters"),
                activeKilocalories = d.optDoubleOrNull("activeKilocalories"),
                totalKilocalories = d.optDoubleOrNull("totalKilocalories"),
                restingHeartRate = d.optLongOrNull("restingHeartRate")?.toInt(),
                avgWakingRespiration = d.optJSONObject("respiration")?.optDoubleOrNull("avgWakingRespirationValue"),
            )
        }
    }

    private fun acceptSleep(nights: JSONArray) {
        for (n in objects(nights)) {
            val measured = listOf("deepSleepSeconds", "lightSleepSeconds", "remSleepSeconds", "awakeSleepSeconds")
                .sumOf { n.optLongOrNull(it) ?: 0L }
            val start = gmt(n, "sleepStartTimestampGMT")
            val end = gmt(n, "sleepEndTimestampGMT")
            // Garmin lists a default sleep window for every night; only some were measured.
            if (measured > 0 && start != null && end != null && end > start) {
                sleep[start] = SleepSession(start, end, ZoneOffset.UTC)
            }
            n.optJSONObject("spo2SleepSummary")?.let { s ->
                val from = gmt(s, "sleepMeasurementStartGMT")
                val to = gmt(s, "sleepMeasurementEndGMT")
                val avg = s.optDoubleOrNull("averageSPO2")
                if (from != null && to != null && avg != null && avg in 0.0..100.0) {
                    spo2[from.plusSeconds(ChronoUnit.SECONDS.between(from, to) / 2)] = avg
                }
            }
        }
    }

    private fun acceptVo2Max(entries: JSONArray) {
        for (e in objects(entries)) {
            val date = e.optString("calendarDate").takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: continue
            val time = gmt(e, "updateTimestamp") ?: continue
            val value = e.optDoubleOrNull("vo2MaxValue") ?: continue
            // Several updates per day are recalculations; the last one of the day stands.
            val known = vo2Max[date]
            if (known == null || time > known.first) vo2Max[date] = time to value
        }
    }

    // ---- Result ----------------------------------------------------------------------

    fun finish(): ImportData {
        val timeline = OffsetTimeline(offsets)

        val sorted = heartRate.copyOf(heartRateCount).also { it.sort() }
        val windows = workouts.filter { it.heartRate.isNotEmpty() }.map { it.start.epochSecond..it.end.epochSecond }
        val kept = LongArray(sorted.size)
        var n = 0
        var last = -1L
        for (p in sorted) {
            val s = HeartRateSeries.seconds(p)
            if (s == last) continue // same second from two syncs
            last = s
            if (windows.any { s in it }) continue // the workout's own recording replaces it
            kept[n++] = p
        }

        val steps = steps(timeline)
        return ImportData(
            heartRate = HeartRateSeries(kept.copyOf(n), timeline),
            steps = steps,
            days = days.values.sortedBy { it.date },
            workouts = workouts.distinctBy { it.start }.sortedBy { it.start },
            sleep = sleep.values.map { it.copy(offset = timeline.at(it.start)) }.sortedBy { it.start },
            spo2 = spo2.map { (t, v) -> InstantValue(t, v, timeline.at(t)) }.sortedBy { it.time },
            vo2Max = vo2Max.values.map { (t, v) -> InstantValue(t, v, timeline.at(t)) }.sortedBy { it.time },
            problems = problems + stepCheck(steps),
        )
    }

    /**
     * Garmin's monitoring counters ("cycles") run up per activity type from local midnight;
     * the day's highest value is exactly Garmin's daily step total. Each increase becomes
     * one interval. A sample stamped exactly at midnight closes the day before.
     */
    private fun steps(timeline: OffsetTimeline): List<StepInterval> {
        val out = ArrayList<StepInterval>()
        val byDayAndType = stepPoints.entries.groupBy { (key, _) ->
            val t = Instant.ofEpochSecond(key.first)
            localDate(t.minusSeconds(1), timeline.at(t)) to key.second
        }
        val covered = HashSet<LocalDate>()
        for ((dayType, entries) in byDayAndType) {
            val (date, _) = dayType
            var max = 0L
            var maxActive = 0L
            var previous: Instant? = null
            for ((key, point) in entries.sortedBy { it.key.first }) {
                val end = Instant.ofEpochSecond(key.first)
                val offset = timeline.at(end)
                if (point.cycles > max) {
                    val activeSeconds = point.activeMs?.let { (it - maxActive) / 1000 }?.takeIf { it > 0 } ?: 60
                    val dayStart = date.atStartOfDay().toInstant(offset)
                    var start = maxOf(end.minusSeconds(activeSeconds), previous ?: dayStart)
                    if (start >= end) start = end.minusSeconds(1)
                    out += StepInterval(start, end, point.cycles - max, offset)
                    covered += date
                    max = point.cycles
                    point.activeMs?.let { maxActive = maxOf(maxActive, it) }
                }
                previous = end
            }
        }
        // A day with Garmin's total but no monitoring file: one interval over the whole day.
        wholeDays = 0
        for (d in days.values) {
            val total = d.totalSteps ?: continue
            if (total > 0 && d.date !in covered) {
                out += StepInterval(d.start, d.end, total, d.offset)
                wholeDays++
            }
        }
        return out.sortedBy { it.start }
    }

    private fun stepCheck(steps: List<StepInterval>): List<String> {
        val sums = HashMap<LocalDate, Long>()
        for (s in steps) sums.merge(localDate(s.end.minusSeconds(1), s.offset), s.count, Long::plus)
        val compared = days.values.filter { it.totalSteps != null }
        val off = compared.filter { sums[it.date] ?: 0L != it.totalSteps }
        val notes = ArrayList<String>()
        if (wholeDays > 0) notes += "$wholeDays days without step counters: Garmin's daily total written as one interval per day"
        if (off.isNotEmpty()) notes += "Step totals differ from Garmin's daily total on ${off.size} of ${compared.size} days: " +
            off.sortedBy { it.date }.joinToString { "${it.date} (${(sums[it.date] ?: 0L) - it.totalSteps!!})" }
        return notes
    }

    companion object {
        private const val FILE_ID = 0
        private const val SESSION = 18
        private const val RECORD = 20
        private const val ACTIVITY = 34
        private const val MONITORING = 55
        private const val MONITORING_INFO = 103

        private const val FILE_ACTIVITY = 4
        private const val FILE_MONITORING_A = 15
        private const val FILE_MONITORING_B = 32

        private const val ACTIVITY_RUNNING = 1
        private const val ACTIVITY_WALKING = 6

        fun isWantedJson(name: String) =
            name.endsWith(".json") && ("UDSFile_" in name || "sleepData" in name || "MetricsMaxMetData" in name)

        /** Garmin's sport and sub_sport numbers as far as they matter here. */
        fun workoutType(sport: Int?, subSport: Int?): WorkoutType = when {
            subSport == 15 -> WorkoutType.ELLIPTICAL
            subSport == 62 -> WorkoutType.BREATHING
            subSport == 20 -> WorkoutType.STRENGTH
            subSport == 43 -> WorkoutType.YOGA
            sport == 1 -> WorkoutType.RUNNING
            sport == 2 -> WorkoutType.CYCLING
            sport == 5 -> WorkoutType.SWIMMING
            sport == 11 -> WorkoutType.WALKING
            sport == 17 -> WorkoutType.HIKING
            else -> WorkoutType.OTHER
        }

        private fun unixSeconds(fit: Long) = fit + FitReader.FIT_EPOCH_UNIX
        private fun unix(fit: Long): Instant = Instant.ofEpochSecond(unixSeconds(fit))
        private fun offset(seconds: Long): ZoneOffset = ZoneOffset.ofTotalSeconds(seconds.toInt())
        private fun localDate(t: Instant, offset: ZoneOffset): LocalDate = t.atOffset(offset).toLocalDate()
        private fun shortName(name: String) = name.substringAfterLast('/').replace(Regex("^[^_]*@[^_]*_"), "")

        private fun objects(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

        /** Garmin writes "2021-03-09T20:01:00.0" without a zone; the field name says which. */
        private fun local(o: JSONObject, key: String): LocalDateTime? =
            o.optString(key).takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

        private fun gmt(o: JSONObject, key: String): Instant? = local(o, key)?.toInstant(ZoneOffset.UTC)

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key) else null

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
    }
}
