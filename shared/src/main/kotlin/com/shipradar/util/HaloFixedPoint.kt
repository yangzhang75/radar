package com.shipradar.util

import kotlin.math.roundToInt

/**
 * HALO advanced-control float encoding (00CB commands). The dB value is a signed 32-bit fixed-point
 * with 12 fractional bits (Q12): value = rawInt / 4096.0, stored little-endian, two's complement.
 *
 * Worked examples from 雷达天线端协议文档-HALO.docx §雷达高级控制:
 *   98.351  -> 0x0006259E  (= 402846 / 4096)
 *   -0.997  -> 0xFFFFF00C  (signed -4084 / 4096)
 */
object HaloFixedPoint {
    /** Decode a signed Q12 int into a dB value. */
    fun decodeQ12(raw: Int): Double = raw / 4096.0

    /** Encode a dB value into a signed Q12 int (rounded). */
    fun encodeQ12(value: Double): Int = (value * 4096.0).roundToInt()

    /** Encode value as 4 little-endian bytes (as placed on the wire). */
    fun encodeQ12LeBytes(value: Double): ByteArray {
        val v = encodeQ12(value)
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )
    }

    /** Decode 4 little-endian bytes into a dB value. */
    fun decodeQ12LeBytes(b: ByteArray, offset: Int = 0): Double {
        val raw = (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
        return decodeQ12(raw)
    }
}
