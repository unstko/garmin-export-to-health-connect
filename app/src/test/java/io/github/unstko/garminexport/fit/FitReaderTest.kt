package io.github.unstko.garminexport.fit

import io.github.unstko.garminexport.fit.FitWriter.Companion.ENUM
import io.github.unstko.garminexport.fit.FitWriter.Companion.SINT16
import io.github.unstko.garminexport.fit.FitWriter.Companion.STRING
import io.github.unstko.garminexport.fit.FitWriter.Companion.UINT16
import io.github.unstko.garminexport.fit.FitWriter.Companion.UINT32
import io.github.unstko.garminexport.fit.FitWriter.Companion.UINT32Z
import io.github.unstko.garminexport.fit.FitWriter.Companion.UINT8
import io.github.unstko.garminexport.fit.FitWriter.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FitReaderTest {
    private val t0 = 1_000_000_000L // 2021-09-09 in FIT seconds

    private fun fileId() = FitWriter()
        .define(0, 0, listOf(Field(0, 1, ENUM), Field(4, 4, UINT32)))
        .message(0, 32, t0)

    @Test
    fun `reads file_id with raw values`() {
        val msgs = FitReader.read(fileId().bytes())
        assertEquals(1, msgs.size)
        assertEquals(0, msgs[0].global)
        assertEquals(32L, msgs[0].long(0))
        assertEquals(t0, msgs[0].long(4))
    }

    @Test
    fun `resolves monitoring timestamp_16 against the last full timestamp`() {
        val bytes = fileId()
            .define(1, 55, listOf(Field(253, 4, UINT32)))
            .define(2, 55, listOf(Field(26, 2, UINT16), Field(27, 1, UINT8)))
            .message(1, t0)
            .message(2, (t0 + 60) and 0xFFFF, 72)
            .message(2, (t0 + 180) and 0xFFFF, 75)
            .bytes()
        val hr = FitReader.read(bytes).filter { it.global == 55 && it.long(27) != null }
        assertEquals(listOf(t0 + 60, t0 + 180), hr.map { it.timestamp })
        assertEquals(listOf(72L, 75L), hr.map { it.long(27) })
    }

    @Test
    fun `timestamp_16 wraps around at 65536 seconds`() {
        val base = (t0 or 0xFFFF) - 10 // ten seconds before the low 16 bits roll over
        val bytes = FitWriter()
            .define(1, 55, listOf(Field(253, 4, UINT32)))
            .define(2, 55, listOf(Field(26, 2, UINT16), Field(27, 1, UINT8)))
            .message(1, base)
            .message(2, (base + 30) and 0xFFFF, 70)
            .bytes()
        assertEquals(base + 30, FitReader.read(bytes).last().timestamp)
    }

    @Test
    fun `timestamp_16 slightly before the last timestamp resolves backwards, not 18 hours ahead`() {
        val bytes = FitWriter()
            .define(1, 21, listOf(Field(253, 4, UINT32))) // an event, written a minute ahead
            .define(2, 55, listOf(Field(26, 2, UINT16), Field(27, 1, UINT8)))
            .message(1, t0 + 60)
            .message(2, (t0 + 16) and 0xFFFF, 70)
            .bytes()
        assertEquals(t0 + 16, FitReader.read(bytes).last().timestamp)
    }

    @Test
    fun `resolves compressed timestamp headers including rollover of the 5-bit offset`() {
        val start = t0 - (t0 and 0x1F) + 28 // low five bits = 28
        val bytes = FitWriter()
            .define(0, 20, listOf(Field(253, 4, UINT32), Field(3, 1, UINT8)))
            .define(1, 20, listOf(Field(3, 1, UINT8)))
            .message(0, start, 80)
            .compressed(1, 30, 81) // +2 s
            .compressed(1, 3, 82)  // wraps: +7 s from start
            .bytes()
        assertEquals(listOf(start, start + 2, start + 7), FitReader.read(bytes).map { it.timestamp })
    }

    @Test
    fun `omits invalid values and keeps strings and signed numbers`() {
        val bytes = FitWriter()
            .define(0, 12, listOf(Field(0, 1, ENUM), Field(1, 2, SINT16), Field(2, 4, UINT32Z), Field(3, 4, STRING)))
            .message(0, 0xFF, -5, 0, 0x6B6C6177) // "walk" little-endian
            .bytes()
        val msg = FitReader.read(bytes).single()
        assertNull(msg.fields[0])
        assertEquals(-5L, msg.long(1))
        assertNull(msg.fields[2])
        assertEquals("walk", msg.fields[3])
    }

    @Test
    fun `reads big-endian definitions`() {
        val bytes = FitWriter()
            .define(0, 55, listOf(Field(253, 4, UINT32), Field(27, 1, UINT8)), bigEndian = true)
            .message(0, t0, 64)
            .bytes()
        val msg = FitReader.read(bytes).single()
        assertEquals(55, msg.global)
        assertEquals(t0, msg.timestamp)
    }

    @Test
    fun `skips developer fields`() {
        val bytes = FitWriter()
            .define(0, 20, listOf(Field(253, 4, UINT32), Field(3, 1, UINT8)), devFieldSizes = listOf(2, 3))
            .message(0, t0, 90, devBytes = 5)
            .message(0, t0 + 1, 91, devBytes = 5)
            .bytes()
        assertEquals(listOf(90L, 91L), FitReader.read(bytes).map { it.long(3) })
    }

    @Test
    fun `reads chained files`() {
        val bytes = fileId().bytes() + fileId().bytes()
        assertEquals(2, FitReader.read(bytes).size)
    }

    @Test
    fun `rejects a corrupted file unless the CRC check is off`() {
        val bytes = fileId().bytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        assertThrows(FitException::class.java) { FitReader.read(bytes) }
        assertFalse(FitReader.read(bytes, checkCrc = false).isEmpty())
    }

    @Test
    fun `rejects files that are not FIT`() {
        assertThrows(FitException::class.java) { FitReader.read(ByteArray(20)) }
    }
}
