package com.shipradar.util

/** Angle conversions between HALO raw encodings and degrees. */
object Angles {
    /** spokeAzimuth/spokeCompass: 0..4095 -> 0..360 degrees. Values > 4095 map to 4095. */
    fun rawAzimuthToDeg(raw: Int): Double {
        val clamped = raw.coerceIn(0, 4095)
        return 360.0 * clamped / 4096.0
    }

    /** degrees -> 0..4095 raw azimuth. */
    fun degToRawAzimuth(deg: Double): Int =
        (normalizeDeg(deg) / 360.0 * 4096.0).toInt().coerceIn(0, 4095)

    /** Tenths of a degree (0..3599) used by bearing/placement corrections. */
    fun tenthsDegToDeg(tenths: Int): Double = tenths / 10.0
    fun degToTenthsDeg(deg: Double): Int = (normalizeDeg(deg) * 10.0).toInt().coerceIn(0, 3599)

    /** Wrap any angle into [0, 360). */
    fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}
