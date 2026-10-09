package io.github.unstko.garminexport.fit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds synthetic FIT files for tests. Deliberately separate from [FitReader] so the
 * tests do not merely check the reader against itself.
 */
class FitWriter {
    class Field(val number: Int, val size: Int, val baseType: Int)

    private val data = ByteArrayOutputStream()
    private val orders = HashMap<Int, ByteOrder>()
    private val defs = HashMap<Int, List<Field>>()

    fun define(local: Int, global: Int, fields: List<Field>, bigEndian: Boolean = false, devFieldSizes: List<Int> = emptyList()): FitWriter {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        data.write(0x40 or (if (devFieldSizes.isNotEmpty()) 0x20 else 0) or local)
        data.write(0)
        data.write(if (bigEndian) 1 else 0)
        data.write(ByteBuffer.allocate(2).order(order).putShort(global.toShort()).array())
        data.write(fields.size)
        for (f in fields) { data.write(f.number); data.write(f.size); data.write(f.baseType) }
        if (devFieldSizes.isNotEmpty()) {
            data.write(devFieldSizes.size)
            devFieldSizes.forEachIndexed { i, size -> data.write(i); data.write(size); data.write(0) }
        }
        orders[local] = order
        defs[local] = fields
        return this
    }

    /** [values] are raw integers, one per defined field, written with the field's size. */
    fun message(local: Int, vararg values: Long, devBytes: Int = 0): FitWriter {
        data.write(local)
        body(local, values, devBytes)
        return this
    }

    fun compressed(local: Int, timeOffset: Int, vararg values: Long): FitWriter {
        data.write(0x80 or (local shl 5) or (timeOffset and 0x1F))
        body(local, values, 0)
        return this
    }

    fun raw(vararg bytes: Int): FitWriter { bytes.forEach { data.write(it) }; return this }

    private fun body(local: Int, values: LongArray, devBytes: Int) {
        val fields = defs.getValue(local)
        val buf = ByteBuffer.allocate(fields.sumOf { it.size }).order(orders.getValue(local))
        fields.zip(values.toList()).forEach { (f, v) ->
            when (f.size) {
                1 -> buf.put(v.toByte())
                2 -> buf.putShort(v.toShort())
                4 -> buf.putInt(v.toInt())
                8 -> buf.putLong(v)
                else -> error("size ${f.size} not supported by the test writer")
            }
        }
        data.write(buf.array())
        repeat(devBytes) { data.write(0xAB) }
    }

    fun bytes(): ByteArray {
        val body = data.toByteArray()
        val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .put(14).put(0x20).putShort(2100).putInt(body.size).put(".FIT".toByteArray())
        val headerBytes = header.array()
        val headerCrc = Crc.of(headerBytes, 0, 12)
        headerBytes[12] = (headerCrc and 0xFF).toByte()
        headerBytes[13] = (headerCrc shr 8).toByte()
        val file = headerBytes + body
        val crc = Crc.of(file, 0, file.size)
        return file + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    companion object {
        const val ENUM = 0x00
        const val UINT8 = 0x02
        const val SINT16 = 0x83
        const val UINT16 = 0x84
        const val UINT32 = 0x86
        const val STRING = 0x07
        const val UINT32Z = 0x8C
    }
}
