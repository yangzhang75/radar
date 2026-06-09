package com.shipradar.app.alarm

import com.shipradar.contract.AlarmPriority

/**
 * BAM priority colour coding for the alarm HMI.
 *
 * Standards basis (type-cert critical): **IEC 62288 Ed.2 §4.7.2.1 (MSC191/5.5.2)** — *"the colour
 * red shall be used for alarm / emergency-alarm coding"*; warnings and cautions use yellow-family
 * colours distinct from alarm red and from each other (§4.7.1.1 — all colours in a table shall
 * clearly differ). IEC 62923-1 presentation defers the exact palette to IEC 62288.
 *
 * Values are packed `0xAARRGGBB` Ints (same convention as
 * [com.shipradar.uicore.color.ColorMapper]) so the Compose layer wraps them with `Color(...)`
 * without this file importing any Android/Compose type — keeps the mapping unit-testable.
 *
 * TODO(待标准:IEC 62288 §4.5.1/§7.2.1) — these are DAY-palette values. Dusk/night dimming and the
 * exact chromaticity are delegated to the IHO S-52 presentation library (not in the standards
 * library, see DISP-03); the framework/theme worker (T2.9) owns global day/dusk/night switching,
 * so per-palette alarm tints are deferred to that integration.
 */
object AlarmColors {

    /** Saturated red — alarm & emergency alarm (§4.7.2.1). Brighter than the NIGHT echo red so the two are distinguishable (§4.4.1.1 NOTE 5, ≥1:2). */
    const val ALARM_RED: Int = 0xFFFF1A1A.toInt()

    /** Yellowish-orange — warning. Distinct from alarm red and from caution yellow (§4.7.1.1). */
    const val WARNING_ORANGE: Int = 0xFFFFA000.toInt()

    /** Yellow — caution. */
    const val CAUTION_YELLOW: Int = 0xFFFFE000.toInt()

    /** Foreground text/icon colour drawn on top of a priority fill (dark, for contrast on the bright fills). */
    const val ON_PRIORITY: Int = 0xFF101010.toInt()

    /** Background tint for an acknowledged / resolved row (de-emphasised, no longer demanding attention). */
    const val ACKNOWLEDGED_DIM: Int = 0xFF3A3A3A.toInt()

    /** Packed ARGB for [priority]'s coding colour. */
    fun colorFor(priority: AlarmPriority): Int = when (priority) {
        AlarmPriority.EMERGENCY_ALARM, AlarmPriority.ALARM -> ALARM_RED
        AlarmPriority.WARNING -> WARNING_ORANGE
        AlarmPriority.CAUTION -> CAUTION_YELLOW
    }
}
