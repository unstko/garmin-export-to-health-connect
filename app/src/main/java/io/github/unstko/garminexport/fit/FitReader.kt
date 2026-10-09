package io.github.unstko.garminexport.fit

import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitException(message: String) : Exception(message)

/**
 * One decoded data message. Field values are kept raw (no scale, no offset, no enum names):
 * a [Long] for single integers, a [Double] for floats, a [String] for strings, and a
 * [List] of those for array fields. Invalid values ("no data" in FIT terms) are left out.
 *
 * [timestamp] is the resolved absolute time in seconds since the FIT epoch, also for
 * messages that only carry a compressed timestamp or a monitoring `timestamp_16`.
 */
class FitMessage(val global: Int, val fields: Map<Int, Any>, val timestamp: Long?) {
    fun long(field: Int): Long? = fields[field] as? Long
    override fun toString() = "FitMessage(global=$global, timestamp=$timestamp, fields=$fields)"
}

/**
 * A minimal decoder for the FIT file format, written from the public protocol description.
 * It knows nothing about Garmin's message profile beyond the handful of conventions the
 * format itself relies on (field 253 is a timestamp, monitoring field 26 is `timestamp_16`).
 * Interpreting messages is left to the caller.
 */
object FitReader {
    const val FIT_EPOCH_UNIX = 631_065_600L // 1989-12-31T00:00:00Z

    private const val TIMESTAMP_FIELD = 253
    private const val MONITORING = 55
    private const val MONITORING_TIMESTAMP_16 = 26

    private class FieldDef(val number: Int, val size: Int, val baseType: Int)
    private class Definition(val global: Int, val order: ByteOrder, val fields: List<FieldDef>, val devBytes: Int)

    fun read(bytes: ByteArray, checkCrc: Boolean = true): List<FitMessage> {
        val messages = ArrayList<FitMessage>()
        var offset = 0
        // A file may consist of several FIT files chained back to back.
        while (offset < bytes.size) {
            offset = readOne(bytes, offset, checkCrc, messages)
        }
        return messages
    }

    private fun readOne(bytes: ByteArray, start: Int, checkCrc: Boolean, out: MutableList<FitMessage>): Int {
        if (bytes.size - start < 12) throw FitException("truncated header at $start")
        val headerSize = bytes[start].toInt() and 0xFF
        if (headerSize != 12 && headerSize != 14) throw FitException("unexpected header size $headerSize")
        val header = ByteBuffer.wrap(bytes, start, headerSize).order(ByteOrder.LITTLE_ENDIAN)
        val dataSize = header.getInt(start + 4).toLong() and 0xFFFFFFFFL
        if (String(bytes, start + 8, 4, Charsets.US_ASCII) != ".FIT") throw FitException("missing .FIT signature")
        val dataStart = start + headerSize
        val dataEnd = dataStart + dataSize.toInt()
        if (dataEnd + 2 > bytes.size) throw FitException("truncated data: header announces $dataSize bytes")

        if (checkCrc) {
            val stored = (bytes[dataEnd].toInt() and 0xFF) or ((bytes[dataEnd + 1].toInt() and 0xFF) shl 8)
            val computed = Crc.of(bytes, start, dataEnd)
            if (stored != computed) throw FitException("CRC mismatch: stored $stored, computed $computed")
        }

        val definitions = arrayOfNulls<Definition>(16)
        var lastTimestamp: Long? = null
        var pos = dataStart
        while (pos < dataEnd) {
            val recordHeader = bytes[pos++].toInt() and 0xFF
            if (recordHeader and 0x80 != 0) {
                // Compressed timestamp header: local type in bits 5-6, 5-bit time offset.
                val local = (recordHeader shr 5) and 0x03
                val def = definitions[local] ?: throw FitException("no definition for local type $local at $pos")
                val base = lastTimestamp ?: throw FitException("compressed timestamp before any timestamp")
                val offsetBits = (recordHeader and 0x1F).toLong()
                var ts = (base and 0x1F.inv().toLong()) + offsetBits
                if (offsetBits < (base and 0x1F)) ts += 0x20
                val (msg, next) = readData(bytes, pos, def, ts)
                lastTimestamp = msg.timestamp
                out += msg
                pos = next
            } else if (recordHeader and 0x40 != 0) {
                val local = recordHeader and 0x0F
                val hasDev = recordHeader and 0x20 != 0
                val (def, next) = readDefinition(bytes, pos, hasDev)
                definitions[local] = def
                pos = next
            } else {
                val local = recordHeader and 0x0F
                val def = definitions[local] ?: throw FitException("no definition for local type $local at $pos")
                val (msg, next) = readData(bytes, pos, def, null, lastTimestamp)
                if (msg.timestamp != null) lastTimestamp = msg.timestamp
                out += msg
                pos = next
            }
        }
        return dataEnd + 2
    }

    private fun readDefinition(bytes: ByteArray, start: Int, hasDev: Boolean): Pair<Definition, Int> {
        var pos = start + 1 // reserved
        val order = if (bytes[pos++].toInt() == 1) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val global = ByteBuffer.wrap(bytes, pos, 2).order(order).getShort(pos).toInt() and 0xFFFF
        pos += 2
        val count = bytes[pos++].toInt() and 0xFF
        val fields = (0 until count).map {
            val f = FieldDef(bytes[pos].toInt() and 0xFF, bytes[pos + 1].toInt() and 0xFF, bytes[pos + 2].toInt() and 0xFF)
            pos += 3
            f
        }
        var devBytes = 0
        if (hasDev) {
            val devCount = bytes[pos++].toInt() and 0xFF
            repeat(devCount) {
                devBytes += bytes[pos + 1].toInt() and 0xFF
                pos += 3
            }
        }
        return Definition(global, order, fields, devBytes) to pos
    }

    private fun readData(
        bytes: ByteArray, start: Int, def: Definition, compressedTs: Long?, lastTimestamp: Long? = null,
    ): Pair<FitMessage, Int> {
        var pos = start
        val values = HashMap<Int, Any>()
        for (f in def.fields) {
            decode(bytes, pos, f, def.order)?.let { values[f.number] = it }
            pos += f.size
        }
        pos += def.devBytes // developer fields are skipped

        var ts = (values[TIMESTAMP_FIELD] as? Long) ?: compressedTs
        if (ts == null && def.global == MONITORING && lastTimestamp != null) {
            val t16 = values[MONITORING_TIMESTAMP_16] as? Long
            if (t16 != null) ts = lastTimestamp + nearest16(t16 - (lastTimestamp and 0xFFFF))
        }
        return FitMessage(def.global, values, ts) to pos
    }

    /**
     * Resolves a 16-bit difference to the nearest point in time, up to about nine hours in
     * either direction. The protocol description only counts forward, but Garmin devices
     * write `event` messages a minute ahead of the monitoring samples that follow them.
     * Counting forward from such an event pushes those samples 65,536 seconds (18 hours)
     * into the future.
     */
    private fun nearest16(diff: Long): Long = ((diff + 0x8000) and 0xFFFF) - 0x8000

    // Base type number (low 5 bits) -> element size in bytes.
    private val sizes = intArrayOf(1, 1, 1, 2, 2, 4, 4, 1, 4, 8, 1, 2, 4, 1, 8, 8, 8)

    private fun decode(bytes: ByteArray, pos: Int, f: FieldDef, order: ByteOrder): Any? {
        val type = f.baseType and 0x1F
        if (type >= sizes.size) return null
        if (type == 7) {
            val end = (pos until pos + f.size).firstOrNull { bytes[it].toInt() == 0 } ?: (pos + f.size)
            return if (end == pos) null else String(bytes, pos, end - pos, Charsets.UTF_8)
        }
        val size = sizes[type]
        if (f.size == 0 || f.size % size != 0) return null
        val buf = ByteBuffer.wrap(bytes).order(order)
        val elements = (0 until f.size / size).map { element(buf, pos + it * size, type) }
        return if (elements.size == 1) elements[0] else elements.takeIf { l -> l.any { it != null } }
    }

    private fun element(buf: ByteBuffer, p: Int, type: Int): Any? = when (type) {
        0, 2, 13 -> (buf.get(p).toLong() and 0xFF).takeIf { it != 0xFFL }
        1 -> buf.get(p).toLong().takeIf { it != 0x7FL }
        3 -> buf.getShort(p).toLong().takeIf { it != 0x7FFFL }
        4 -> (buf.getShort(p).toLong() and 0xFFFF).takeIf { it != 0xFFFFL }
        5 -> buf.getInt(p).toLong().takeIf { it != 0x7FFFFFFFL }
        6 -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).takeIf { it != 0xFFFFFFFFL }
        8 -> buf.getInt(p).takeIf { it != -1 }?.let { Float.fromBits(it).toDouble() }
        9 -> buf.getLong(p).takeIf { it != -1L }?.let { Double.fromBits(it) }
        10 -> (buf.get(p).toLong() and 0xFF).takeIf { it != 0L }
        11 -> (buf.getShort(p).toLong() and 0xFFFF).takeIf { it != 0L }
        12 -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).takeIf { it != 0L }
        14 -> buf.getLong(p).takeIf { it != Long.MAX_VALUE }
        15 -> buf.getLong(p).takeIf { it != -1L }
        16 -> buf.getLong(p).takeIf { it != 0L }
        else -> null
    }
}

/** The CRC-16 used by FIT, computed nibble-wise as in the protocol description. */
object Crc {
    private val table = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun of(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[b and 0xF]
            tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[(b shr 4) and 0xF]
        }
        return crc
    }
}
