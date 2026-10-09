package io.github.unstko.garminexport.garmin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Everything the importer takes from a Garmin export, independent of Health Connect. */
class ImportData(
    /** All-day heart rate outside workouts. */
    val heartRate: HeartRateSeries,
    val steps: List<StepInterval>,
    val days: List<DailySummary>,
    val workouts: List<Workout>,
    val sleep: List<SleepSession>,
    val spo2: List<InstantValue>,
    val vo2Max: List<InstantValue>,
    val problems: List<String>,
)

data class HeartRateSample(val time: Instant, val bpm: Int, val offset: ZoneOffset)

data class StepInterval(val start: Instant, val end: Instant, val count: Long, val offset: ZoneOffset)

data class InstantValue(val time: Instant, val value: Double, val offset: ZoneOffset)

/** Garmin's per-day values from the UDS files. Absent values are null. */
data class DailySummary(
    val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
    val totalSteps: Long?,
    val distanceMeters: Double?,
    val floorsAscendedMeters: Double?,
    val activeKilocalories: Double?,
    val totalKilocalories: Double?,
    val restingHeartRate: Int?,
    val avgWakingRespiration: Double?,
)

enum class WorkoutType { WALKING, RUNNING, CYCLING, SWIMMING, HIKING, ELLIPTICAL, STRENGTH, YOGA, BREATHING, OTHER }

data class Workout(
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
    val type: WorkoutType,
    val heartRate: List<HeartRateSample>,
)

data class SleepSession(val start: Instant, val end: Instant, val offset: ZoneOffset)

/**
 * All-day heart rate, kept packed in one LongArray: a Garmin export holds around a million
 * samples, far too many to keep as objects on a phone. Sorted by time, one sample per second.
 */
class HeartRateSeries(private val packed: LongArray, private val offsets: OffsetTimeline) {
    val size get() = packed.size

    /** The samples grouped by UTC hour, oldest first. */
    fun hours(): Sequence<List<HeartRateSample>> = sequence {
        var i = 0
        while (i < packed.size) {
            val hour = seconds(packed[i]) / 3600
            val group = ArrayList<HeartRateSample>()
            while (i < packed.size && seconds(packed[i]) / 3600 == hour) {
                val t = Instant.ofEpochSecond(seconds(packed[i]))
                group += HeartRateSample(t, (packed[i] and 0xFF).toInt(), offsets.at(t))
                i++
            }
            yield(group)
        }
    }

    companion object {
        /** Packs a Unix time in seconds and a bpm value (1..255). */
        fun pack(unixSeconds: Long, bpm: Int): Long = (unixSeconds shl 8) or bpm.toLong()
        fun seconds(packed: Long): Long = packed ushr 8
    }
}

/** UTC offsets as the watch recorded them; for any instant, the last one known before it. */
class OffsetTimeline(entries: Map<Instant, ZoneOffset>) {
    private val times = entries.keys.sorted()
    private val values = times.map { entries.getValue(it) }

    fun at(t: Instant): ZoneOffset {
        if (times.isEmpty()) return ZoneOffset.UTC
        var i = times.binarySearch(t)
        if (i < 0) i = -i - 2
        return values[i.coerceAtLeast(0)]
    }
}
