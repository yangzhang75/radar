package com.shipradar.app.framework

import com.shipradar.uicore.color.ColorMapper

/**
 * T2.9 — OpenBridge chrome colour tokens (DISP-03). **Pure Kotlin, no Compose / Android types** so the
 * mapping is verifiable by inspection and could be promoted to `:ui-core` for unit testing if wanted
 * (the `:app` module is not buildable without the Android SDK — see delivery report). All values are
 * packed ARGB `Int` (0xAARRGGBB), the same encoding [ColorMapper] uses; [OpenBridgeTheme] turns them
 * into Compose `Color`.
 *
 * ## Standards basis (type-cert critical)
 *
 * This reuses the day/dusk/night palette approach of [ColorMapper] — [ObTheme] maps 1:1 to
 * [ColorMapper.Palette]. The governing IEC 62288 Ed.2 clauses (verified against
 * `80-733_IEC 62288 Ed.2_2014_FDIS.pdf`):
 *  - **§5.4.1.1 (MSC191/6.3.1)** — "for radar displays a dark non-reflecting background shall be used"
 *    under *every* ambient condition (the NOTE warns the light "Day" table is unreadable for radar).
 *    ⇒ [ObColorTokens.operationalBackground] is dark for day/dusk/night.
 *  - **§4.5.1 (MSC191/5.3.2)** — "lighter foreground information on a dark non-reflecting background".
 *    ⇒ foreground tokens are lighter than the chrome/background tokens.
 *  - **§4.4.1.1 + Table 1** — ambient light day 200 / dusk 10 cd/m² / night darkness; **§7.2.1** night
 *    vision preserved. ⇒ foreground/peak luminance decreases day → dusk → night; night is a low-
 *    luminance red/black scheme (same rationale as [ColorMapper] night).
 *  - **§4.7.2.1 (MSC191/5.5.2)** — "the colour red shall be used for ... alarm and emergency alarm".
 *    ⇒ [ObColorTokens.alarm] is red; [accent] is deliberately non-red so it cannot be confused with it.
 *  - **§4.7.1.1 (MSC191/5.5.1)** — all colours in a colour table shall clearly differ. ⇒ alarm /
 *    warning / caution / accent / foreground are mutually distinguishable (alarm red vs warning orange
 *    vs caution yellow follows the IMO alert-priority colour convention).
 *  - **§4.4.1.1 NOTE 5** — "visually distinguishable is at least luminance ratio 1:2" (instrumental).
 *
 * ## What is NOT standard-numeric (flagged)
 *
 * IEC 62288 pins the *method/relationships* above but delegates exact colour-table chromaticity to the
 * **IHO S-52 Presentation Library** (§4.5.1), which is not in the project standards library. The
 * specific ARGB below follow the **OpenBridge 6.0** maritime design system as the agreed visual
 * reference (native redraw target). OpenBridge's exact published token values could not be retrieved
 * in this environment, so these are *compliant-by-construction* (dark bg, lighter-fg, day>dusk>night,
 * red=alarm, all distinguishable) approximations of the OpenBridge look — `TODO(待标准/视觉参考)`
 * marks where an exact OB / S-52 token should replace them.
 */
enum class ObTheme {
    DAY, DUSK, NIGHT;

    /** The matching echo palette so chrome and the PPI render (T2.1) stay in lockstep. */
    val echoPalette: ColorMapper.Palette
        get() = when (this) {
            DAY -> ColorMapper.Palette.DAY
            DUSK -> ColorMapper.Palette.DUSK
            NIGHT -> ColorMapper.Palette.NIGHT
        }
}

/**
 * Semantic OpenBridge chrome colours for one ambient theme. Packed ARGB `Int`.
 * Field meaning is semantic (OpenBridge token role), not literal — see [obTokens].
 */
data class ObColorTokens(
    /** PPI / operational display area background. Dark in every theme (IEC 62288 §5.4.1.1). */
    val operationalBackground: Int,
    /** Chrome container background (bars, panels) — OpenBridge container surface. */
    val chromeBackground: Int,
    /** Raised element background (buttons, selected menu rows). */
    val chromeElevated: Int,
    /** Dividers / element borders. */
    val chromeBorder: Int,
    /** Primary foreground: text, icons, active labels (lighter on dark, §4.5.1). */
    val foregroundPrimary: Int,
    /** Secondary foreground: passive labels, units. */
    val foregroundSecondary: Int,
    /** Disabled / inactive foreground. */
    val foregroundDisabled: Int,
    /** OpenBridge "active"/brand accent — selection, focus. Non-red so it never reads as alarm (§4.7.1.1). */
    val accent: Int,
    /** Foreground on top of [accent]. */
    val accentForeground: Int,
    /** Alarm / emergency-alarm coding — red (IEC 62288 §4.7.2.1). */
    val alarm: Int,
    /** Warning alert coding — orange. */
    val warning: Int,
    /** Caution alert coding — yellow. */
    val caution: Int,
    /** Echo "strongest return" swatch, taken straight from [ColorMapper] so a legend matches the PPI. */
    val echoLegendPeak: Int,
)

/**
 * The OpenBridge token set for [theme]. Echo-coherent fields are pulled from [ColorMapper] so the
 * chrome can never drift from what the PPI actually paints.
 *
 * Luminance ordering (day > dusk > night) and red=alarm / non-red accent are enforced by the chosen
 * values and asserted in tests (if/when this layer is promoted to a JVM module).
 */
fun obTokens(theme: ObTheme): ObColorTokens = when (theme) {
    // DAY — ambient 200 cd/m² (Table 1): brightest foreground; radar area still dark (§5.4.1.1 NOTE).
    ObTheme.DAY -> ObColorTokens(
        operationalBackground = 0xFF000A14.toInt(), // dark navy-black  §5.4.1.1; TODO(待标准/视觉参考: OB6 day operational bg)
        chromeBackground = 0xFF1B2733.toInt(),      // OB container, day
        chromeElevated = 0xFF26343F.toInt(),
        chromeBorder = 0xFF3C4A57.toInt(),
        foregroundPrimary = 0xFFE8EEF2.toInt(),     // near-white, lighter-on-dark §4.5.1
        foregroundSecondary = 0xFFA9B6C0.toInt(),
        foregroundDisabled = 0xFF5E6B75.toInt(),
        accent = 0xFF3FA9E0.toInt(),                // OpenBridge active blue; non-red §4.7.1.1
        accentForeground = 0xFF00121E.toInt(),
        alarm = 0xFFFF0000.toInt(),                 // red §4.7.2.1
        warning = 0xFFFF8000.toInt(),               // orange
        caution = 0xFFFFD400.toInt(),               // yellow
        echoLegendPeak = ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, ColorMapper.Palette.DAY),
    )
    // DUSK — ambient 10 cd/m² (Table 1): reduced luminance.
    ObTheme.DUSK -> ObColorTokens(
        operationalBackground = 0xFF00060D.toInt(),
        chromeBackground = 0xFF101820.toInt(),
        chromeElevated = 0xFF182430.toInt(),
        chromeBorder = 0xFF263441.toInt(),
        foregroundPrimary = 0xFFB9C2C9.toInt(),     // dimmer than day
        foregroundSecondary = 0xFF7C8893.toInt(),
        foregroundDisabled = 0xFF44505A.toInt(),
        accent = 0xFF2E7BA6.toInt(),
        accentForeground = 0xFF00121E.toInt(),
        alarm = 0xFFFF0000.toInt(),                 // red §4.7.2.1 — kept fully saturated in every theme
        warning = 0xFFCC6600.toInt(),
        caution = 0xFFCCAA00.toInt(),
        echoLegendPeak = ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, ColorMapper.Palette.DUSK),
    )
    // NIGHT — darkness (Table 1) + preserve dark adaptation (§7.2.1): low-luminance red/black scheme.
    ObTheme.NIGHT -> ObColorTokens(
        operationalBackground = 0xFF030000.toInt(), // red-black; matches ColorMapper night hue
        chromeBackground = 0xFF0A0303.toInt(),
        chromeElevated = 0xFF160606.toInt(),
        chromeBorder = 0xFF2A0E0E.toInt(),
        foregroundPrimary = 0xFFB23A3A.toInt(),     // dim red text — scotopic-safe; TODO(待标准/视觉参考: OB6 night fg)
        foregroundSecondary = 0xFF7A2424.toInt(),
        foregroundDisabled = 0xFF3A1414.toInt(),
        // A blue accent would harm dark adaptation at night; OpenBridge night dims the accent toward
        // amber/red. Kept distinct (brighter, warmer) from foreground red. TODO(待标准/厂商 HMI).
        accent = 0xFFD06A1E.toInt(),
        accentForeground = 0xFF000000.toInt(),
        // Alarm red is the same fully-saturated 0xFFFF0000 in every theme: an alarm must be maximally
        // conspicuous and is the baseline ColorMapper guarantees the echo red sits ≥1:2 below (§5.4.1.1).
        // It stays distinguishable from the desaturated red night foreground by saturation + flashing +
        // dedicated position, not colour alone (§4.7.3.1, §4.7.4.1).
        alarm = 0xFFFF0000.toInt(),
        warning = 0xFF8A4A00.toInt(),
        caution = 0xFF8A7A00.toInt(),
        echoLegendPeak = ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, ColorMapper.Palette.NIGHT),
    )
}
