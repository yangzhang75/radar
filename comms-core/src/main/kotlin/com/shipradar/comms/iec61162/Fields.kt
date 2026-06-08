package com.shipradar.comms.iec61162

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Field-type decoders for the special-format fields of IEC 61162-1 ED6 §8.2 (Table 5):
 * latitude `llll.ll`, longitude `yyyyy.yy`, time `hhmmss.ss`, plus the common date/numeric forms.
 *
 * All decoders return null for a null/blank field (§7.2.3.4) or for a value that does not match
 * the standard's fixed-prefix layout, so a malformed sub-field degrades to "unavailable" rather
 * than producing a fabricated number.
 */
internal object Fields {

    /**
     * §8.2 Table 5 — Latitude `llll.ll`: two fixed digits of degrees, two fixed digits of minutes,
     * and an optional decimal fraction of minutes. [hemisphere] is "N"/"S". Returns signed degrees
     * (south negative), or null if unavailable/malformed.
     */
    fun parseLatitude(value: String?, hemisphere: String?): Double? {
        if (value.isNullOrBlank() || hemisphere.isNullOrBlank()) return null
        if (value.length < 4) return null
        val deg = value.substring(0, 2).toIntOrNull() ?: return null
        val min = value.substring(2).toDoubleOrNull() ?: return null
        if (min < 0.0 || min >= 60.0) return null
        val magnitude = deg + min / 60.0
        return when (hemisphere.uppercase()) {
            "N" -> magnitude
            "S" -> -magnitude
            else -> null
        }
    }

    /**
     * §8.2 Table 5 — Longitude `yyyyy.yy`: three fixed digits of degrees, two fixed digits of
     * minutes, optional decimal fraction. [hemisphere] is "E"/"W". West negative.
     */
    fun parseLongitude(value: String?, hemisphere: String?): Double? {
        if (value.isNullOrBlank() || hemisphere.isNullOrBlank()) return null
        if (value.length < 5) return null
        val deg = value.substring(0, 3).toIntOrNull() ?: return null
        val min = value.substring(3).toDoubleOrNull() ?: return null
        if (min < 0.0 || min >= 60.0) return null
        val magnitude = deg + min / 60.0
        return when (hemisphere.uppercase()) {
            "E" -> magnitude
            "W" -> -magnitude
            else -> null
        }
    }

    /** Seconds-of-day for a `hhmmss.ss` time field (§8.2 Table 5), or null if unavailable/malformed. */
    fun parseTimeOfDaySeconds(value: String?): Double? {
        if (value.isNullOrBlank() || value.length < 6) return null
        val hh = value.substring(0, 2).toIntOrNull() ?: return null
        val mm = value.substring(2, 4).toIntOrNull() ?: return null
        val ss = value.substring(4).toDoubleOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59 || ss < 0.0 || ss >= 60.0) return null
        return hh * 3600.0 + mm * 60.0 + ss
    }

    /**
     * Combine a `hhmmss.ss` time-of-day with a `ddmmyy` date field (as carried by RMC) into a UTC
     * epoch-millisecond timestamp. Two-digit year is mapped into 2000-2099 (acceptable for an
     * in-service radar; a four-digit source is preferred via ZDA). Returns null if either is
     * unavailable/malformed.
     */
    fun parseUtcEpochMillis(time: String?, dateDdmmyy: String?): Long? {
        if (dateDdmmyy.isNullOrBlank() || dateDdmmyy.length != 6) return null
        val tod = parseTimeOfDaySeconds(time) ?: return null
        val dd = dateDdmmyy.substring(0, 2).toIntOrNull() ?: return null
        val mm = dateDdmmyy.substring(2, 4).toIntOrNull() ?: return null
        val yy = dateDdmmyy.substring(4, 6).toIntOrNull() ?: return null
        return epochMillis(2000 + yy, mm, dd, tod)
    }

    /** Combine explicit y/m/d (as carried by ZDA) with a `hhmmss.ss` time into UTC epoch millis. */
    fun parseUtcEpochMillis(time: String?, year: Int, month: Int, day: Int): Long? {
        val tod = parseTimeOfDaySeconds(time) ?: return null
        return epochMillis(year, month, day, tod)
    }

    private fun epochMillis(year: Int, month: Int, day: Int, secondsOfDay: Double): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            val midnight = LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant()
            midnight.toEpochMilli() + (secondsOfDay * 1000.0).toLong()
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    /** Numeric field `x.x`; null/blank → null (§7.2.3.4). */
    fun parseDouble(value: String?): Double? = value?.takeIf { it.isNotBlank() }?.toDoubleOrNull()

    /** Numeric integer field `x`; null/blank → null. */
    fun parseInt(value: String?): Int? = value?.takeIf { it.isNotBlank() }?.toIntOrNull()
}
