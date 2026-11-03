package dev.georgerahul.cmftalk

import java.util.UUID

/**
 * Nothing / CMF control protocol (RFCOMM). Same commands the Nothing X app
 * sends for settings. Firmware / OTA services are never touched.
 *
 * Frame: 55 60 01 | type lo, hi | len lo, hi | seq | payload | crc lo, hi
 */
object Proto {
    val SERVICE_UUID: UUID = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")

    const val ANC_GET = 0xC01E
    const val ANC_SET = 0xF00F
    const val SPATIAL_GET = 0xC04F
    const val SPATIAL_SET = 0xF052
    const val BATTERY_GET = 0xC007
    const val GESTURES_GET = 0xC018
    const val GESTURES_SET = 0xF003
    const val GESTURES_REPLY = 0x4018
    const val GESTURES_SET_ACK = 0x7003
    const val ACTION_SPATIAL = 0x1B

    const val ANC_NOTIFY = 0xE003
    const val ANC_REPLY = 0x401E
    const val SPATIAL_NOTIFY = 0x404F
    const val BATTERY_NOTIFY = 0xE001
    const val BATTERY_REPLY = 0x4007

    const val ANC_OFF = 0x05
    const val ANC_TRANSPARENCY = 0x07
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
        0x05 -> "Off"
        0x07 -> "Transparency"
        0x01 -> "Noise cancelling (high)"
        0x02 -> "Noise cancelling (mid)"
        0x03 -> "Noise cancelling (low)"
        0x04 -> "Noise cancelling (adaptive)"
        else -> "Mode 0x%02X".format(m)
    }
}

/** Headphone buttons that can be the talk-mode trigger. */
data class ButtonSlot(val key: String, val name: String, val device: Int, val button: Int, val gesture: Int) {
    val id: Int get() = (device shl 16) or (button shl 8) or gesture

    companion object {
        val ALL = listOf(
            ButtonSlot("hold", "Action button: press and hold", 0x06, 0x0A, 0x07),
            ButtonSlot("single", "Action button: single press", 0x06, 0x0A, 0x01),
        )
        fun byKey(k: String?) = ALL.firstOrNull { it.key == k }
    }
}

fun actionName(a: Int?): String = when (a) {
    null -> "-"
    0x01 -> "No action"
    0x0B -> "Voice assistant"
    0x1B -> "Spatial Audio"
    0x1D -> "Mic on/off"
    0x1F -> "News"
    0x20 -> "Switch device"
    0x22 -> "EQ preset"
    0x0A -> "Noise control (all modes)"
    0x14 -> "Noise control (Off / ANC)"
    0x15 -> "Noise control (Off / Transparency)"
    0x16 -> "Noise control (ANC / Transparency)"
    else -> "Action 0x%02X".format(a)
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
