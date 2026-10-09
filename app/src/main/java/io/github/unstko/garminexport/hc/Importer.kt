package io.github.unstko.garminexport.hc

import android.content.SharedPreferences
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Writes the mapped records in batches. Progress is remembered per category, so an
 * interrupted import continues where it stopped; since every record has a stable
 * clientRecordId, repeating a batch would do no harm either.
 */
class Importer(
    private val client: HealthConnectClient,
    private val progress: SharedPreferences,
    private val say: (String) -> Unit,
) {
    suspend fun run(mapper: RecordMapper, runKey: String) {
        if (progress.getString("run", null) != runKey) progress.edit().clear().putString("run", runKey).apply()
        for (c in mapper.categories) {
            val done = progress.getInt("done:${c.name}", 0)
            val batches = (c.count + BATCH - 1) / BATCH
            if (done >= batches) { say("${c.name}: already written (${c.count})"); continue }
            say("${c.name}: ${c.count} records in $batches batches" + if (done > 0) ", resuming after batch $done" else "")
            c.records().chunked(BATCH).forEachIndexed { i, batch ->
                if (i < done) return@forEachIndexed
                insertWithRetry(batch, "${c.name} batch ${i + 1}/$batches")
                progress.edit().putInt("done:${c.name}", i + 1).apply()
                if ((i + 1) % 20 == 0 || i + 1 == batches) say("${c.name}: ${i + 1}/$batches")
            }
        }
        say("Import finished.")
    }

    private suspend fun insertWithRetry(batch: List<androidx.health.connect.client.records.Record>, label: String) {
        var wait = 30_000L
        repeat(ATTEMPTS) { attempt ->
            try {
                client.insertRecords(batch)
                return
            } catch (e: Exception) {
                if (attempt == ATTEMPTS - 1) throw e
                say("$label failed (${e.javaClass.simpleName}: ${e.message}); retrying in ${wait / 1000} s")
                delay(wait)
                wait = minOf(wait * 2, 600_000L)
            }
        }
    }

    /** Deletes everything this app has written. Health Connect only lets an app delete its own records. */
    suspend fun deleteAll() {
        val everything = TimeRangeFilter.between(Instant.parse("1990-01-01T00:00:00Z"), Instant.now().plusSeconds(86_400))
        for (type in RecordMapper.allTypes) {
            client.deleteRecords(type, everything)
            say("Deleted ${type.simpleName}")
        }
        progress.edit().clear().apply()
        say("Everything this app wrote is deleted from Health Connect.")
    }

    companion object {
        private const val BATCH = 500
        private const val ATTEMPTS = 8
    }
}
