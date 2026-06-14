package com.shipradar.app.theme

import com.shipradar.app.framework.ObTheme
import com.shipradar.app.framework.obTokens
import com.shipradar.uicore.color.ColorMapper
import kotlin.math.roundToInt

/**
 * W6-B — Day / Dusk / Night radar colour palette + brilliance (back-light) control.
 *
 * **Pure Kotlin, packed ARGB `Int` (0xAARRGGBB) — no Compose / Android types**, so the colour
 * relationships demanded by the certification standards are verifiable by JVM unit test (the Compose
 * `ThemePanel` reads this and converts to `Color` only at the leaf). This is the same pure/Compose
 * split used by [com.shipradar.app.framework.ObTokens] / `OpenBridgeTheme`.
 *
 * ## Single source of truth (no duplicated colour constants)
 *
 * The three ambient palettes are **not redefined here** — they are projected from the already-audited
 * chrome tokens [obTokens] (W4-C) and the PPI echo palettes [ColorMapper] (W5-C). A [ThemeMode] maps
 * 1:1 to [ObTheme] / [ColorMapper.Palette]; this keeps the panel, the chrome and the radar video in
 * lockstep (a cert requirement — one colour table per ambient condition) and means there is no second
 * place a value can drift. We therefore do **not** edit `ObTokens.kt`; we consume it.
 *
 * ## Standards basis (type-cert critical) — IEC 62288 Ed.2 / IMO MSC.191(79)
 *  - **§4.4.1.1 + Table 1** — ambient light day 200 / dusk 10 cd/m² / night darkness ⇒ presentation
 *    luminance decreases DAY → DUSK → NIGHT (inherited from [obTokens]).
 *  - **§4.5.1 (MSC191/5.3.2)** — lighter foreground on a dark non-reflecting background.
 *  - **§5.4.1.1 (MSC191/6.3.1)** — a dark non-reflecting background under *every* ambient condition.
 *  - **§7.2.1 (MSC191/8.1.1)** — a brightness/contrast adjustment whose range "shall be sufficient to
 *    maintain the user's dark adaptation at night". ⇒ the continuous [brillianceFactor] back-light axis,
 *    distinct from the discrete day/dusk/night step.
 *  - **§4.7.2.1** — red = alarm/emergency-alarm; **§4.7.1.1** — all table colours clearly differ.
 *
 * Exact chromaticity is delegated by §4.5.1 to the IHO S-52 Presentation Library (not in the project
 * standards set); the ARGB inherited from [obTokens] are OpenBridge-6.0 visual-reference values, so the
 * relationships below are compliant-by-construction, not S-52 baselines. `TODO(待标准: IHO S-52)`.
 */
data class RadarPalette(
    /** Operational / PPI background. Dark in every mode (§5.4.1.1). */
    val background: Int,
    /** Chrome container surface (bars, panels). */
    val surface: Int,
    /** Raised element / selected row. */
    val surfaceElevated: Int,
    /** Divider / border. */
    val border: Int,
    /** Primary text & icons (lighter on dark, §4.5.1). */
    val textPrimary: Int,
    /** Secondary text / units. */
    val textSecondary: Int,
    /** Disabled text. */
    val textDisabled: Int,
    /** Strongest radar echo swatch — taken straight from [ColorMapper] so a legend matches the PPI. */
    val echoPeak: Int,
    /** Selection / focus accent — non-red so it never reads as alarm (§4.7.1.1). */
    val accent: Int,
    /** Foreground on top of [accent]. */
    val accentText: Int,
    /** Alarm / emergency-alarm coding — red (§4.7.2.1). */
    val alarm: Int,
    /** Warning coding — orange. */
    val warning: Int,
    /** Caution coding — yellow. */
    val caution: Int,
) {
    /**
     * Hue-preserving back-light dim of the whole palette to user [brilliance] (0..1), per §7.2.1.
     * Emulates a hardware back-light: every channel of every colour is scaled by [brillianceFactor],
     * alpha untouched, so colour *coding* (hue) and all luminance *orderings* are preserved while the
     * absolute light output drops. A floor keeps the screen usable at the bottom of the range (the
     * display must remain legible — IEC 62388 controls — while still going low enough for night).
     */
    fun atBrilliance(brilliance: Float): RadarPalette {
        val f = brillianceFactor(brilliance)
        return RadarPalette(
            background = dim(background, f),
            surface = dim(surface, f),
            surfaceElevated = dim(surfaceElevated, f),
            border = dim(border, f),
            textPrimary = dim(textPrimary, f),
            textSecondary = dim(textSecondary, f),
            textDisabled = dim(textDisabled, f),
            echoPeak = dim(echoPeak, f),
            accent = dim(accent, f),
            accentText = dim(accentText, f),
            alarm = dim(alarm, f),
            warning = dim(warning, f),
            caution = dim(caution, f),
        )
    }

    companion object {
        /**
         * Lowest fraction of full back-light reachable at brilliance = 0. Non-zero so the display can
         * never be dimmed to invisibility (legibility / control safety) while still being low enough to
         * protect night dark adaptation (§7.2.1).
         */
        const val MIN_BRILLIANCE_FACTOR: Float = 0.10f

        /** Default brilliance per mode: full by day, reduced at dusk, low at night (§7.2.1, §4.4.1.1). */
        fun defaultBrilliance(mode: ThemeMode): Float = when (mode) {
            ThemeMode.DAY -> 1.0f
            ThemeMode.DUSK -> 0.6f
            ThemeMode.NIGHT -> 0.35f
        }

        /**
         * Map user brilliance [b] (clamped to 0..1) to a render multiplier in
         * `[MIN_BRILLIANCE_FACTOR, 1.0]`. Linear and strictly increasing, so the slider behaves
         * predictably and `b = 1` is full brightness, `b = 0` is the dim floor.
         */
        fun brillianceFactor(b: Float): Float {
            val c = b.coerceIn(0f, 1f)
            return MIN_BRILLIANCE_FACTOR + c * (1f - MIN_BRILLIANCE_FACTOR)
        }

        private fun dim(argb: Int, f: Float): Int {
            val a = (argb ushr 24) and 0xFF
            val r = (((argb ushr 16) and 0xFF) * f).roundToInt().coerceIn(0, 255)
            val g = (((argb ushr 8) and 0xFF) * f).roundToInt().coerceIn(0, 255)
            val b = ((argb and 0xFF) * f).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * The radar palette for [mode], projected from the audited chrome tokens [obTokens] and the PPI echo
 * palette [ColorMapper] (single source of truth — see [RadarPalette] KDoc). Brilliance is applied
 * separately via [RadarPalette.atBrilliance].
 */
fun radarPalette(mode: ThemeMode): RadarPalette = with(obTokens(mode.toObTheme())) {
    RadarPalette(
        background = operationalBackground,
        surface = chromeBackground,
        surfaceElevated = chromeElevated,
        border = chromeBorder,
        textPrimary = foregroundPrimary,
        textSecondary = foregroundSecondary,
        textDisabled = foregroundDisabled,
        echoPeak = echoLegendPeak,
        accent = accent,
        accentText = accentForeground,
        alarm = alarm,
        warning = warning,
        caution = caution,
    )
}
