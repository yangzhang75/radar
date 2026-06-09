package com.shipradar.comms.iec61162

import com.shipradar.contract.AlarmPriority

/**
 * Display dimming / colour-palette preset (DDC §8.3.26 field codes D/K/N/O).
 * Drives the day/dusk/night palette + brightness (consumed by the framework/theme layer).
 */
enum class DimMode {
    DAY,    // D
    DUSK,   // K
    NIGHT,  // N
    OFF;    // O — non-luminous / display off

    companion object {
        fun fromCode(code: String?): DimMode? = when (code?.trim()?.uppercase()) {
            "D" -> DAY
            "K" -> DUSK
            "N" -> NIGHT
            "O" -> OFF
            else -> null
        }
    }
}

/** PPI display orientation reported by RSD (§8.3.87 field 13 codes C/H/N). */
enum class DisplayOrientation {
    HEAD_UP,    // H
    NORTH_UP,   // N
    COURSE_UP;  // C

    companion object {
        fun fromCode(code: String?): DisplayOrientation? = when (code?.trim()?.uppercase()) {
            "H" -> HEAD_UP
            "N" -> NORTH_UP
            "C" -> COURSE_UP
            else -> null
        }
    }
}

/**
 * One entry of a cyclic alert list (ALC §8.3.13): a (manufacturer, identifier, instance, revision)
 * tuple. [priority] is resolved from IEC 62923-2 Table A.1 via
 * [com.shipradar.comms.alarm.AlertCatalog] when the identifier is a known standard alert, else null.
 */
data class AlertListEntry(
    val identifier: Int,
    val instance: Int? = null,
    val revisionCounter: Int? = null,
    val manufacturer: String? = null,
    val priority: AlarmPriority? = null,
)

/**
 * Radar system data (RSD §8.3.87): the radar's display/measurement state — cursor, EBL/VRM 1·2,
 * range scale, range units and display orientation. Ranges are normalised to nautical miles.
 * Consumed by the PPI / input layers to reflect the radar's own measurement tools.
 */
data class RadarSystemData(
    val origin1RangeNm: Double? = null,
    val origin1BearingDeg: Double? = null,
    val vrm1Nm: Double? = null,
    val ebl1Deg: Double? = null,
    val origin2RangeNm: Double? = null,
    val origin2BearingDeg: Double? = null,
    val vrm2Nm: Double? = null,
    val ebl2Deg: Double? = null,
    val cursorRangeNm: Double? = null,
    /** Cursor bearing, degrees clockwise from 0° (§8.3.87). */
    val cursorBearingDeg: Double? = null,
    val rangeScaleNm: Double? = null,
    val orientation: DisplayOrientation? = null,
)
