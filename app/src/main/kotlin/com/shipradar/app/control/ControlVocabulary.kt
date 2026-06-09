package com.shipradar.app.control

import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.MasterSlave
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.SeaMode
import com.shipradar.uicore.ppi.PpiOrientation

/**
 * Single source of the English control labels / abbreviations shown on the radar control panel.
 *
 * Terminology follows IEC 62288 (presentation of navigation-related information — terms &
 * abbreviations) and the radar functions of IEC 62388 §6 (operational controls). The standard
 * radar abbreviations (GAIN, SEA, RAIN, TM/RM, H UP/N UP/C UP, STBY) are well established across
 * IMO MSC.192(79) and IEC 62388; precise on-screen *symbol artwork* (IMO Res. A.278 / IEC 62288
 * graphical symbols) is a separate symbol-modelling task and is marked TODO(待标准-符号建模) here —
 * this panel uses standardised text/abbreviation labels, which are themselves compliant.
 */
object ControlVocabulary {

    // Transmit / power state — IEC 62388 §6, radar operating states.
    const val TRANSMIT = "TRANSMIT"
    const val STANDBY = "STANDBY"
    const val STANDBY_ABBR = "STBY"
    const val POWER = "POWER"

    // Sensitivity / clutter controls — IEC 62388 §6.
    const val GAIN = "GAIN"
    const val SEA = "SEA"                 // anti-clutter sea
    const val RAIN = "RAIN"               // anti-clutter rain
    const val INTERFERENCE_REJECTION = "INTERFERENCE REJECTION"
    const val INTERFERENCE_REJECTION_ABBR = "IR"
    const val AUTO = "AUTO"
    const val MANUAL = "MAN"

    const val ROTATION = "ANTENNA SPEED"
    const val RPM = "RPM"

    // Range / motion / orientation — IEC 62388 §9.4.1 (range scales), §6 (motion/orientation).
    const val RANGE = "RANGE"
    const val NM = "NM"
    const val RINGS = "RINGS"
    const val MOTION = "MOTION"
    const val TRUE_MOTION = "TM"
    const val RELATIVE_MOTION = "RM"
    const val ORIENTATION = "ORIENT"
    const val HEAD_UP = "H UP"
    const val NORTH_UP = "N UP"
    const val COURSE_UP = "C UP"

    // Guard zones — IEC 62388 §6.x guard zone / automatic target detection.
    const val GUARD_ZONES = "GUARD ZONES"
    const val GUARD_ZONE = "GZ"
    const val SENSITIVITY = "SENSITIVITY"
    const val ALARM_ON_ENTERING = "ENTER"
    const val ALARM_ON_LEAVING = "LEAVE"
    const val ALARM_ON_BOTH = "BOTH"

    fun powerStateLabel(state: RadarPowerState): String = when (state) {
        RadarPowerState.OFF -> "OFF"
        RadarPowerState.STANDBY -> STANDBY
        RadarPowerState.TRANSMIT -> TRANSMIT
        RadarPowerState.WARMUP -> "WARM-UP"
        RadarPowerState.NO_SCANNER -> "NO SCANNER"
        RadarPowerState.DETECTING_SCANNER -> "DETECTING"
    }

    fun seaModeLabel(mode: SeaMode): String = when (mode) {
        SeaMode.MANUAL -> MANUAL
        SeaMode.HARBOUR -> "HARBOUR"
        SeaMode.OFFSHORE -> "OFFSHORE"
    }

    fun orientationLabel(o: PpiOrientation): String = when (o) {
        PpiOrientation.HEAD_UP -> HEAD_UP
        PpiOrientation.NORTH_UP -> NORTH_UP
        PpiOrientation.COURSE_UP -> COURSE_UP
    }

    fun motionLabel(m: MotionMode): String = when (m) {
        MotionMode.TRUE_MOTION -> TRUE_MOTION
        MotionMode.RELATIVE_MOTION -> RELATIVE_MOTION
    }

    fun guardAlarmLabel(t: GuardZoneAlarmType): String = when (t) {
        GuardZoneAlarmType.ENTERING -> ALARM_ON_ENTERING
        GuardZoneAlarmType.LEAVING -> ALARM_ON_LEAVING
        GuardZoneAlarmType.BOTH -> ALARM_ON_BOTH
    }

    fun masterSlaveLabel(m: MasterSlave): String = when (m) {
        MasterSlave.MASTER -> "MASTER"
        MasterSlave.SLAVE -> "SLAVE"
    }

    /** Range-scale label, e.g. 0.25 → "0.25", 24.0 → "24" (locale-independent, no trailing ".0"). */
    fun formatRangeNm(nm: Double): String =
        if (nm == nm.toLong().toDouble()) nm.toLong().toString() else nm.toString()

    /**
     * Conventional range-ring spacing label for a scale (NM). IEC 62388 requires the ring spacing be
     * indicated whenever rings are shown; HALO/most radars use 6 rings → spacing = scale / 6 except
     * on the shortest scales. Spacing here = scale / [DEFAULT_RING_COUNT]; flagged for confirmation
     * against the radar's actual ring count.
     */
    fun ringSpacingNm(scaleNm: Double): Double = scaleNm / DEFAULT_RING_COUNT

    const val DEFAULT_RING_COUNT = 6
}
