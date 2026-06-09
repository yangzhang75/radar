package com.shipradar.app.alarm

import com.shipradar.contract.AlarmPriority
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.color.PaletteDimming

/**
 * BAM priority colour coding for the alarm HMI, with day/dusk/night palettes (W5-C).
 *
 * Standards basis (type-cert critical): **IEC 62288 Ed.2 §4.7.2.1 (MSC191/5.5.2)** — *"the colour
 * red shall be used for alarm / emergency-alarm coding"*; warnings/cautions use yellow-family
 * colours distinct from alarm red and from each other (§4.7.1.1). Day → dusk → night dimming follows
 * the §4.5 / §7.2 luminance-decrease mechanism via [PaletteDimming]. IEC 62923-1 presentation defers
 * the exact palette to IEC 62288.
 *
 * **Dusk/night strategy:**
 *  - **Alarm / emergency-alarm red is held constant** across day/dusk/night. An active alarm must
 *    stay maximally conspicuous (safety), red is the night-vision-safe hue (it least disturbs dark
 *    adaptation, §4.5.1 night viewing), and it must remain ≥1:2 *brighter* than the NIGHT echo red
 *    of [ColorMapper] so the two are distinguishable (§5.4.1.1, §4.4.1.1 NOTE 5). Dimming it would
 *    break that separation — so it is exempt from [PaletteDimming].
 *  - **Warning (orange) and caution (yellow) are dimmed** for dusk/night ([PaletteDimming]) to
 *    protect dark adaptation, keeping their hue coding and staying distinguishable (§4.7.1.1).
 *
 * Values are packed `0xAARRGGBB` Ints (same convention as [ColorMapper]) so the Compose layer wraps
 * them with `Color(...)` without this file importing any Android/Compose type — keeps it unit-testable.
 *
 * TODO(待标准: IHO S-52) — exact chromaticity is delegated to the IHO S-52 presentation library (not
 * in the standards library, see DISP-03). The dusk/night *relative* dimming below is implemented per
 * the 62288 §4.5/§7.2 mechanism; only the absolute chromaticity remains pending.
 */
object AlarmColors {

    /** Saturated red — alarm & emergency alarm (§4.7.2.1). Constant across palettes (see class KDoc). */
    const val ALARM_RED: Int = 0xFFFF1A1A.toInt()

    /** DAY yellowish-orange — warning. Distinct from alarm red and caution yellow (§4.7.1.1). */
    const val WARNING_ORANGE: Int = 0xFFFFA000.toInt()

    /** DAY yellow — caution. */
    const val CAUTION_YELLOW: Int = 0xFFFFE000.toInt()

    /** Dark foreground for text/icons drawn on a *bright* priority fill (§4.5.1 contrast). */
    const val ON_PRIORITY_DARK: Int = 0xFF101010.toInt()

    /** Light foreground for text/icons drawn on a *dark/dimmed* priority fill (§4.5.1 contrast). */
    const val ON_PRIORITY_LIGHT: Int = 0xFFF0F0F0.toInt()

    /** Back-compat alias of [ON_PRIORITY_DARK] (DAY foreground); prefer [onColorFor] for palette-aware contrast. */
    const val ON_PRIORITY: Int = ON_PRIORITY_DARK

    /** Back-compat DAY acknowledged-row dim; prefer [acknowledgedDim] for palette-aware dimming. */
    const val ACKNOWLEDGED_DIM: Int = 0xFF3A3A3A.toInt()

    /** Luminance at/above which a fill counts as "bright" and takes dark foreground. */
    private const val BRIGHT_FILL_LUMINANCE = 140.0

    /**
     * Packed ARGB coding colour for [priority] under [palette] (defaults to DAY for existing callers).
     * Alarm/emergency red is palette-independent; warning/caution are dimmed per [PaletteDimming].
     */
    fun colorFor(priority: AlarmPriority, palette: ColorMapper.Palette = ColorMapper.Palette.DAY): Int =
        when (priority) {
            AlarmPriority.EMERGENCY_ALARM, AlarmPriority.ALARM -> ALARM_RED
            AlarmPriority.WARNING -> PaletteDimming.dim(WARNING_ORANGE, palette)
            AlarmPriority.CAUTION -> PaletteDimming.dim(CAUTION_YELLOW, palette)
        }

    /**
     * Contrasting foreground (text/icon) colour to draw on top of [priority]'s fill under [palette]:
     * dark on a bright fill, light on a dark/dimmed fill (§4.5.1 — sufficient contrast under all
     * ambient conditions). The foreground itself is not dimmed — it must contrast the fill; absolute
     * brightness is handled by the runtime brightness control (§7.2.1).
     */
    fun onColorFor(priority: AlarmPriority, palette: ColorMapper.Palette = ColorMapper.Palette.DAY): Int =
        if (relativeLuminance(colorFor(priority, palette)) >= BRIGHT_FILL_LUMINANCE) ON_PRIORITY_DARK
        else ON_PRIORITY_LIGHT

    /** Background tint for an acknowledged / resolved row, dimmed per palette (de-emphasised). */
    fun acknowledgedDim(palette: ColorMapper.Palette = ColorMapper.Palette.DAY): Int =
        PaletteDimming.dim(ACKNOWLEDGED_DIM, palette)

    /** Rec.709 relative luminance of an ARGB Int, ignoring alpha. */
    private fun relativeLuminance(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
