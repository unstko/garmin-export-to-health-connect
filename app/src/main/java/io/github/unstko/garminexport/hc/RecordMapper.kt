package io.github.unstko.garminexport.hc

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import io.github.unstko.garminexport.garmin.HeartRateSample
import io.github.unstko.garminexport.garmin.ImportData
import io.github.unstko.garminexport.garmin.WorkoutType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Turns [ImportData] into Health Connect records, category by category.
 *
 * Every record carries a clientRecordId derived from the data itself, so running the
 * import twice updates records instead of duplicating them.
 */
class RecordMapper(
    private val data: ImportData,
    private val before: LocalDate?,
    private val withSleep: Boolean = true,
    /** Health Connect replaces a record with the same clientRecordId only if this is higher. */
    private val version: Long = 1,
) {

    class Category(val name: String, val type: KClass<out Record>, val count: Int, val records: () -> Sequence<Record>)

    private val device = Device(type = Device.TYPE_WATCH, manufacturer = "Garmin", model = null)
    private fun meta(id: String) = Metadata.autoRecorded(device = device, clientRecordId = id, clientRecordVersion = version)

    /** Whether something at [t] lies before the cut-off day, judged in local time. */
    private fun keep(t: Instant, offset: ZoneOffset) = before == null || t.atOffset(offset).toLocalDate() < before

    private fun noon(date: LocalDate, offset: ZoneOffset): Instant = date.atTime(12, 0).toInstant(offset)

    private val days = data.days.filter { keep(it.start, it.offset) }
    private val steps = data.steps.filter { keep(it.start, it.offset) }
        .groupBy { it.start to it.end } // walking and running may share an interval
        .map { (_, same) -> same.first().copy(count = same.sumOf { it.count }) }
    private val workouts = data.workouts.filter { keep(it.start, it.offset) }
    private val hrHours by lazy { data.heartRate.hours().map { h -> h.filter { keep(it.time, it.offset) } }.filter { it.isNotEmpty() }.count() }

    /** In the order they get written: sleep last, because Google Health keeps it even after deletion. */
    val categories: List<Category> = listOf(
        Category("Steps", StepsRecord::class, steps.size) {
            steps.asSequence().map {
                StepsRecord(
                    startTime = it.start, startZoneOffset = it.offset, endTime = it.end, endZoneOffset = it.offset,
                    count = it.count, metadata = meta("steps-${it.start.epochSecond}-${it.end.epochSecond}"),
                )
            }
        },
        Category("Heart rate", HeartRateRecord::class, hrHours) {
            data.heartRate.hours().map { h -> h.filter { keep(it.time, it.offset) } }.filter { it.isNotEmpty() }
                .map { heartRate(it, "hr-${it.first().time.epochSecond}") }
        },
        Category("Distance", DistanceRecord::class, days.count { (it.distanceMeters ?: 0.0) > 0 }) {
            days.asSequence().filter { (it.distanceMeters ?: 0.0) > 0 }.map {
                DistanceRecord(
                    startTime = it.start, startZoneOffset = it.offset, endTime = it.end, endZoneOffset = it.offset,
                    distance = Length.meters(it.distanceMeters!!), metadata = meta("distance-${it.date}"),
                )
            }
        },
        Category("Floors", FloorsClimbedRecord::class, days.count { (it.floorsAscendedMeters ?: 0.0) > 0 }) {
            days.asSequence().filter { (it.floorsAscendedMeters ?: 0.0) > 0 }.map {
                FloorsClimbedRecord(
                    startTime = it.start, startZoneOffset = it.offset, endTime = it.end, endZoneOffset = it.offset,
                    // Garmin counts a floor per 3.048 m (10 ft) of ascent.
                    floors = it.floorsAscendedMeters!! / 3.048, metadata = meta("floors-${it.date}"),
                )
            }
        },
        Category("Active calories", ActiveCaloriesBurnedRecord::class, days.count { (it.activeKilocalories ?: 0.0) > 0 }) {
            days.asSequence().filter { (it.activeKilocalories ?: 0.0) > 0 }.map {
                ActiveCaloriesBurnedRecord(
                    startTime = it.start, startZoneOffset = it.offset, endTime = it.end, endZoneOffset = it.offset,
                    energy = Energy.kilocalories(it.activeKilocalories!!), metadata = meta("active-calories-${it.date}"),
                )
            }
        },
        // Google Health shows total calories only; active calories alone never appeared there.
        Category("Total calories", TotalCaloriesBurnedRecord::class, days.count { (it.totalKilocalories ?: 0.0) > 0 }) {
            days.asSequence().filter { (it.totalKilocalories ?: 0.0) > 0 }.map {
                TotalCaloriesBurnedRecord(
                    startTime = it.start, startZoneOffset = it.offset, endTime = it.end, endZoneOffset = it.offset,
                    energy = Energy.kilocalories(it.totalKilocalories!!), metadata = meta("total-calories-${it.date}"),
                )
            }
        },
        Category("Resting heart rate", RestingHeartRateRecord::class, days.count { it.restingHeartRate != null }) {
            days.asSequence().filter { it.restingHeartRate != null }.map {
                RestingHeartRateRecord(
                    time = noon(it.date, it.offset), zoneOffset = it.offset,
                    beatsPerMinute = it.restingHeartRate!!.toLong(), metadata = meta("resting-hr-${it.date}"),
                )
            }
        },
        Category("Respiratory rate", RespiratoryRateRecord::class, days.count { it.avgWakingRespiration != null }) {
            days.asSequence().filter { it.avgWakingRespiration != null }.map {
                RespiratoryRateRecord(
                    time = noon(it.date, it.offset), zoneOffset = it.offset,
                    rate = it.avgWakingRespiration!!, metadata = meta("respiration-${it.date}"),
                )
            }
        },
        Category("Oxygen saturation", OxygenSaturationRecord::class, data.spo2.count { keep(it.time, it.offset) }) {
            data.spo2.asSequence().filter { keep(it.time, it.offset) }.map {
                OxygenSaturationRecord(
                    time = it.time, zoneOffset = it.offset,
                    percentage = Percentage(it.value), metadata = meta("spo2-${it.time.epochSecond}"),
                )
            }
        },
        Category("VO2 max", Vo2MaxRecord::class, data.vo2Max.count { keep(it.time, it.offset) }) {
            data.vo2Max.asSequence().filter { keep(it.time, it.offset) }.map {
                Vo2MaxRecord(
                    time = it.time, zoneOffset = it.offset, vo2MillilitersPerMinuteKilogram = it.value,
                    measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER, metadata = meta("vo2max-${it.time.epochSecond}"),
                )
            }
        },
        Category("Workouts", ExerciseSessionRecord::class, workouts.size) {
            workouts.asSequence().map {
                ExerciseSessionRecord(
                    startTime = it.start, startZoneOffset = it.offset, endTime = it.end, endZoneOffset = it.offset,
                    exerciseType = exerciseType(it.type), metadata = meta("workout-${it.start.epochSecond}"),
                )
            }
        },
        Category("Workout heart rate", HeartRateRecord::class, workouts.sumOf { w -> w.heartRate.chunked(3600).size }) {
            workouts.asSequence().flatMap { w ->
                w.heartRate.chunked(3600).mapIndexed { i, chunk -> heartRate(chunk, "workout-hr-${w.start.epochSecond}-$i") }
            }
        },
        Category("Sleep", SleepSessionRecord::class, if (withSleep) data.sleep.count { keep(it.start, it.offset) } else 0) {
            data.sleep.asSequence().filter { withSleep && keep(it.start, it.offset) }.map {
                // Garmin's export has phase totals only, no timeline; the session stays without stages.
                SleepSessionRecord(
                    startTime = it.start, startZoneOffset = it.offset, endTime = it.end, endZoneOffset = it.offset,
                    metadata = meta("sleep-${it.start.epochSecond}"),
                )
            }
        },
    )

    private fun heartRate(samples: List<HeartRateSample>, id: String): HeartRateRecord {
        val first = samples.first()
        val last = samples.last()
        return HeartRateRecord(
            startTime = first.time, startZoneOffset = first.offset,
            endTime = last.time.plusSeconds(1), endZoneOffset = last.offset,
            samples = samples.map { HeartRateRecord.Sample(time = it.time, beatsPerMinute = it.bpm.toLong()) },
            metadata = meta(id),
        )
    }

    companion object {
        fun exerciseType(t: WorkoutType): Int = when (t) {
            WorkoutType.WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            WorkoutType.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            WorkoutType.CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            WorkoutType.SWIMMING -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
            WorkoutType.HIKING -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            WorkoutType.ELLIPTICAL -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
            WorkoutType.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            WorkoutType.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            WorkoutType.BREATHING -> ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING
            WorkoutType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }

        /** Every record type the app may write, for the permission request and for deletion. */
        val allTypes: List<KClass<out Record>> = listOf(
            StepsRecord::class, HeartRateRecord::class, DistanceRecord::class, FloorsClimbedRecord::class,
            ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class, RestingHeartRateRecord::class, RespiratoryRateRecord::class,
            OxygenSaturationRecord::class, Vo2MaxRecord::class, ExerciseSessionRecord::class, SleepSessionRecord::class,
        )
    }
}
