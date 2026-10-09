package io.github.unstko.garminexport.fit

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a regular test: runs only when FIT_DIR points at a folder of real .fit files, and
 * writes one summary line per file to FIT_SUMMARY for comparison with another decoder.
 * No real data ever lives in this repository.
 */
class OwnExportCheck {
    @Test
    fun summarise() {
        val dir = System.getenv("FIT_DIR")
        val out = System.getenv("FIT_SUMMARY")
        assumeTrue("FIT_DIR and FIT_SUMMARY not set", dir != null && out != null)
        File(out!!).printWriter().use { w ->
            File(dir!!).listFiles { f -> f.name.endsWith(".fit") }!!.sortedBy { it.name }.forEach { f ->
                val bytes = f.readBytes()
                val crcOk = runCatching { FitReader.read(bytes) }.isSuccess
                val msgs = FitReader.read(bytes, checkCrc = false)
                val counts = msgs.groupingBy { it.global }.eachCount().toSortedMap()
                val ts = msgs.sumOf { it.long(253) ?: 0L }
                val hr = msgs.filter { it.global == 55 }.mapNotNull { it.long(27) }
                val cycles = msgs.filter { it.global == 55 }.sumOf { it.long(3) ?: 0L }
                val sleep = msgs.filter { it.global == 275 }.mapNotNull { it.long(0) }
                val resolved = msgs.count { it.timestamp != null }
                w.println(listOf(
                    f.name, msgs.size, counts.entries.joinToString(",") { "${it.key}:${it.value}" }, ts,
                    hr.size, hr.sum(), cycles, sleep.size, sleep.sum(), crcOk, resolved,
                ).joinToString("\t"))
            }
        }
    }
}
