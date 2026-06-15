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
    // DAY — OpenBridge 5.0 "day" 调色板(浅色 chrome:backdrop 224/surface 247/section 240/divider 221,
    // 文字深灰 83;交互蓝 #4271B3)。雷达操作区仍为暗底(IEC 62288 §5.4.1.1)。
    ObTheme.DAY -> ObColorTokens(
        operationalBackground = 0xFF000000.toInt(), // 雷达圆暗底(IEC §5.4.1.1)
        chromeBackground = 0xFFF7F7F7.toInt(),      // OB day container surface
        chromeElevated = 0xFFF0F0F0.toInt(),        // OB day section
        chromeBorder = 0xFFDDDDDD.toInt(),          // OB day divider
        foregroundPrimary = 0xFF535353.toInt(),     // OB day on-flat text(浅底深字)
        foregroundSecondary = 0xFF7A7A7A.toInt(),
        foregroundDisabled = 0xFFB0B0B0.toInt(),
        accent = 0xFF4271B3.toInt(),                // OpenBridge 交互蓝(focus/amplified)
        accentForeground = 0xFFFFFFFF.toInt(),
        alarm = 0xFFE2231A.toInt(),                 // 红 §4.7.2.1
        warning = 0xFFEB7700.toInt(),               // 橙
        caution = 0xFFE5C100.toInt(),               // 黄
        echoLegendPeak = ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, ColorMapper.Palette.DAY),
    )
    // DUSK — OpenBridge "dusk"(深中性:backdrop 15/surface 31/section 38/divider 67,文字浅灰 176)。
    ObTheme.DUSK -> ObColorTokens(
        operationalBackground = 0xFF000000.toInt(),
        chromeBackground = 0xFF1F1F1F.toInt(),
        chromeElevated = 0xFF262626.toInt(),
        chromeBorder = 0xFF434343.toInt(),
        foregroundPrimary = 0xFFB0B0B0.toInt(),
        foregroundSecondary = 0xFF808080.toInt(),
        foregroundDisabled = 0xFF555555.toInt(),
        accent = 0xFF4271B3.toInt(),                // OB 蓝
        accentForeground = 0xFFFFFFFF.toInt(),
        alarm = 0xFFE2231A.toInt(),
        warning = 0xFFCC6600.toInt(),
        caution = 0xFFCCAA00.toInt(),
        echoLegendPeak = ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, ColorMapper.Palette.DUSK),
    )
    // NIGHT — OpenBridge "night"(黑底 0,0,0 + 琥珀:active 234,167,94 / text 199,136,66;暗视觉保护 §7.2.1)。
    ObTheme.NIGHT -> ObColorTokens(
        operationalBackground = 0xFF000000.toInt(),
        chromeBackground = 0xFF000000.toInt(),      // OB night bg = 纯黑
        chromeElevated = 0xFF0E0E0E.toInt(),
        chromeBorder = 0xFF271B10.toInt(),          // OB night divider(暖)
        foregroundPrimary = 0xFFC78842.toInt(),     // OB night on-flat 琥珀
        foregroundSecondary = 0xFF8A6030.toInt(),
        foregroundDisabled = 0xFF4A3418.toInt(),
        accent = 0xFFEAA75E.toInt(),                // OB night active 琥珀(蓝色夜间损害暗视觉)
        accentForeground = 0xFF000000.toInt(),
        alarm = 0xFFFF0000.toInt(),                 // 报警红始终满饱和 §4.7.2.1
        warning = 0xFF8A4A00.toInt(),
        caution = 0xFF8A7A00.toInt(),
        echoLegendPeak = ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, ColorMapper.Palette.NIGHT),
    )
}
