package com.shipradar.uicore.color

import kotlin.math.roundToInt

/**
 * W5-C — IEC 62288 day → dusk → night luminance-decrease (dimming) mechanism (DISP-03).
 *
 * Standards basis (verified against IEC 62288 Ed.2 FDIS):
 *  - **§4.5.1 (MSC191/5.3.2)** — night viewing shows "lighter foreground information on a dark
 *    non-reflecting background"; the presentation must suit day/dusk/night.
 *  - **§4.4.1.1 + Table 1** — ambient light day 200 / dusk 10 cd/m² / night darkness.
 *  - **§7.2.1 (MSC191/8.1.1)** — the brightness/contrast adjustment range "shall be sufficient to
 *    maintain the user's dark adaptation at night".
 *  ⇒ presentation luminance must **decrease day → dusk → night**.
 *
 * This is the shared, hue-preserving darkening primitive: [dim] scales the RGB channels of any
 * `0xAARRGGBB` colour by a per-palette factor (alpha untouched), so a colour keeps its hue/coding
 * but emits less light at dusk/night. Used by the alarm palette (`AlarmColors`) for its dusk/night
 * tints. [ColorMapper]'s echo palettes also dim, but additionally warm-shift the hue
 * (yellow→orange→red) for night vision, so they are intentionally NOT pure [dim] outputs.
 *
 * The factors realise the **mandated decrease** (asserted day > dusk > night) — their exact
 * magnitude is a design choice: absolute on-screen luminance (cd/m²) is set at runtime by the
 * brightness control (§7.2.1), and exact chromaticity is delegated to IHO S-52 (§4.5.1).
 * TODO(待标准: IHO S-52) — exact per-palette chromaticity / luminance values.
 */
object PaletteDimming {

    /** DAY = reference condition, no reduction. */
    const val DAY_FACTOR: Double = 1.0

    /** DUSK relative luminance vs day (Table 1: dusk 10 cd/m² « day 200 cd/m²). */
    const val DUSK_FACTOR: Double = 0.6

    /** NIGHT relative luminance vs day — deepest reduction to protect dark adaptation (§7.2.1). */
    const val NIGHT_FACTOR: Double = 0.35

    /** Per-palette dimming factor in (0, 1]. */
    fun factor(palette: ColorMapper.Palette): Double = when (palette) {
        ColorMapper.Palette.DAY -> DAY_FACTOR
        ColorMapper.Palette.DUSK -> DUSK_FACTOR
        ColorMapper.Palette.NIGHT -> NIGHT_FACTOR
    }

    /**
     * Hue-preserving dim of [argb] for [palette]: each RGB channel × [factor], alpha unchanged.
     * DAY returns [argb] unchanged.
     */
    fun dim(argb: Int, palette: ColorMapper.Palette): Int {
        val f = factor(palette)
        val a = (argb ushr 24) and 0xFF
        val r = ((((argb ushr 16) and 0xFF) * f).roundToInt()).coerceIn(0, 255)
        val g = ((((argb ushr 8) and 0xFF) * f).roundToInt()).coerceIn(0, 255)
        val b = (((argb and 0xFF) * f).roundToInt()).coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
