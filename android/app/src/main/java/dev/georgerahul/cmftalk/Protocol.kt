package dev.georgerahul.cmftalk

import java.util.UUID

/**
 * Nothing / CMF control protocol (RFCOMM), as used by the CMF Headphone Pro
 * (model B175). Only settings commands that Nothing X also sends are used.
 * Firmware / OTA services are never touched.
 *
 * Frame: 55 60 01 | type lo, hi | len lo, hi | seq | payload | crc lo, hi
 * Every command below was checked against real headphones.
 */
object Proto {
    val SERVICE_UUID: UUID = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")

    // Noise control
    const val ANC_GET = 0xC01E
    const val ANC_SET = 0xF00F          // [01, mode, 00]
    const val ANC_NOTIFY = 0xE003       // [01, mode, ...]
    const val ANC_REPLY = 0x401E

    // Spatial audio: 0 = off, 2, 3 (modes cycle 0 -> 2 -> 3 -> 0 on the button)
    const val SPATIAL_GET = 0xC04F
    const val SPATIAL_SET = 0xF052      // [mode]
    const val SPATIAL_NOTIFY = 0x404F   // [mode, 00]

    // Battery: [count, (id, level|0x80 charging)...]
    const val BATTERY_GET = 0xC007
    const val BATTERY_NOTIFY = 0xE001
    const val BATTERY_REPLY = 0x4007

    const val ANC_OFF = 0x05
    const val ANC_TRANSPARENCY = 0x07
    const val ANC_HIGH = 0x01
    const val ANC_MID = 0x02
    const val ANC_LOW = 0x03
    const val ANC_ADAPTIVE = 0x04
    val ANC_LEVELS = setOf(ANC_HIGH, ANC_MID, ANC_LOW, ANC_ADAPTIVE)

    const val UNSOLICITED_SEQ = 0xFF

    fun crc16(d: ByteArray, len: Int = d.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (d[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    fun encode(type: Int, payload: IntArray, seq: Int): ByteArray {
        val out = ByteArray(8 + payload.size + 2)
        val head = intArrayOf(0x55, 0x60, 0x01, type and 0xFF, type shr 8,
            payload.size and 0xFF, payload.size shr 8, seq)
        head.forEachIndexed { i, v -> out[i] = v.toByte() }
        payload.forEachIndexed { i, v -> out[8 + i] = v.toByte() }
        val c = crc16(out, 8 + payload.size)
        out[8 + payload.size] = (c and 0xFF).toByte()
        out[9 + payload.size] = (c shr 8).toByte()
        return out
    }

    fun ancName(m: Int?): String = when (m) {
        null -> "-"
        ANC_OFF -> "Off"
        ANC_TRANSPARENCY -> "Transparency"
        ANC_HIGH -> "Noise cancelling · High"
        ANC_MID -> "Noise cancelling · Mid"
        ANC_LOW -> "Noise cancelling · Low"
        ANC_ADAPTIVE -> "Noise cancelling · Adaptive"
        else -> "Mode 0x%02X".format(m)
    }
}

object Spatial {
    val MODES = listOf(0 to "Off", 2 to "Concert", 3 to "Cinema")
}

class Frame(val type: Int, val seq: Int, val payload: IntArray)

/** Splits the byte stream into frames. Several frames can come in one read. */
class FrameParser {
    private val buf = ArrayList<Int>()

    fun feed(bytes: ByteArray, n: Int): List<Frame> {
        for (i in 0 until n) buf.add(bytes[i].toInt() and 0xFF)
        val out = ArrayList<Frame>()
        while (true) {
            val start = buf.indexOf(0x55)
            if (start < 0) { buf.clear(); break }
            if (start > 0) buf.subList(0, start).clear()
            if (buf.size < 8) break
            val control = buf[1] or (buf[2] shl 8)
            val hasCrc = control and 0x20 != 0
            val len = buf[5] or (buf[6] shl 8)
            if (len > 1024) { buf.removeAt(0); continue }
            val total = 8 + len + if (hasCrc) 2 else 0
            if (buf.size < total) break
            val type = buf[3] or (buf[4] shl 8)
            out.add(Frame(type, buf[7], IntArray(len) { buf[8 + it] }))
            buf.subList(0, total).clear()
        }
        return out
    }
}
