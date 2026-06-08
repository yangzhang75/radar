package com.shipradar.uicore.color

import com.shipradar.contract.SampleEncoding

/**
 * T2.2 — Echo colouring (DISP-03). Pure, platform-independent mapping
 * `(sample 0..15, encoding, palette) -> packed ARGB Int (0xAARRGGBB)`.
 *
 * No Android / Compose types: the :app renderer (T2.1) takes these Ints to Canvas/GL.
 *
 * ## Standards basis and the 62288 gap (type-cert critical — read before changing a value)
 *
 * IEC 62388/FDIS does **not** itself give numeric colour values. It does two things:
 *  - **§7.3.1** fixes the *palette set* echoes may be drawn from when the display does not show
 *    charted information: `white, grey, black, blue, magenta, green, yellow, orange, red`
 *    (or a visually-distinguishable subset; see also ISO 9241-8). Every anchor hue below is a
 *    member of that set — that part is pinned and cited.
 *  - **§7.1.1 (MSC.191(79))** and **§7.3.2** delegate the *exact* chromaticity/luminance values,
 *    the day/dusk/night HMI palettes, and colour-discrimination test method to **IEC 62288**.
 *
 * IEC 62288 is **absent from the project standards library** (see delivery report). Therefore the
 * precise per-level luminance ramp, the day/dusk/night brightness scaling, and any Doppler hue
 * assignment are **not standard-pinned**. They are marked `TODO(待标准: 62288 §…)` below and carry
 * provisional values so the renderer and tests are functional. These are explicitly flagged
 * approximations-pending-standard, NOT silently-chosen final values: once the 62288 colour tables
 * are obtained, replace the flagged anchors/ramp and the asserted ARGB in the tests will move with
 * them. Do not treat current ARGB as type-approved.
 *
 * Doppler echo colouring is a solid-state (Navico HALO) feature carried by
 * [com.shipradar.contract.EchoSpoke.encoding]; it has **no clause** in 62388 (clause 11 routes all
 * target/symbol colour to 62288). Approaching/receding therefore use two distinct §7.3.1-palette
 * hues as a placeholder pending 62288 / vendor HMI guidance.
 */
object ColorMapper {

    /**
     * Ambient HMI palette. Day/dusk/night switching is a mandatory marine-HMI requirement
     * (IEC 62288 / MSC.191(79), referenced by 62388 §7.1.1) — night must be a low-brightness
     * red/black scheme to preserve scotopic (dark-adapted) vision on the bridge.
     */
    enum class Palette { DAY, DUSK, NIGHT }

    /** Number of distinct sample levels carried by [com.shipradar.contract.EchoSpoke.samples] (4-bit). */
    const val LEVELS: Int = 16

    /** Highest amplitude sample. */
    private const val MAX_SAMPLE = 15

    /** DOPPLER sample meaning approaching, per contract `Echo.kt` (`sampleEncoding`, HALO §辐条). */
    const val SAMPLE_APPROACHING: Int = 15

    /** DOPPLER sample meaning receding, per contract `Echo.kt`. */
    const val SAMPLE_RECEDING: Int = 14

    /**
     * No-signal / background. Sample 0 is fully transparent so the PPI background (owned by T2.1)
     * shows through and echoes never paint over it. ARGB alpha = 0.
     *
     * Source: contract `Echo.kt` ("0 = no signal"); IEC 62388 §7.3.1 (echo must not obscure
     * background/chart layer).
     */
    const val TRANSPARENT: Int = 0x00000000

    /**
     * Peak (sample 15) echo hue for the amplitude ramp, per palette. Each is a member of the
     * IEC 62388 §7.3.1 named set. Brightness across day/dusk/night and the per-level ramp toward
     * these peaks are NOT pinned by 62388.
     *
     * - DAY  : yellow  — §7.3.1 named colour. Canonical sRGB yellow primary.
     * - DUSK : orange  — §7.3.1 named colour, reduced brightness.
     * - NIGHT: red     — §7.3.1 named colour, low brightness (scotopic-safe red/black scheme).
     *
     * TODO(待标准: 62288 §colour-tables) — exact chromaticity + peak luminance per palette.
     */
    private val amplitudePeak: Map<Palette, Int> = mapOf(
        // 0xAARRGGBB, alpha = full at peak.
        Palette.DAY to argb(0xFF, 0xFF, 0xFF, 0x00),   // yellow  (255,255,0)   §7.3.1
        Palette.DUSK to argb(0xFF, 0xC8, 0x6E, 0x00),  // orange  (200,110,0)   §7.3.1; TODO(待标准: 62288) dusk luminance
        Palette.NIGHT to argb(0xFF, 0xB4, 0x00, 0x00), // red     (180,0,0)     §7.3.1; TODO(待标准: 62288) night peak luminance
    )

    /**
     * Doppler APPROACHING (sample 15) peak hue per palette — §7.3.1 named colour `magenta`,
     * chosen distinct from the amplitude hue and from the receding hue.
     *
     * TODO(待标准: 62288 / 厂商 HMI) — 62388 has no Doppler colour clause; hue assignment and the
     * night-mode treatment (a non-red hue conflicts with the red/black night scheme) are unresolved.
     */
    private val approachingPeak: Map<Palette, Int> = mapOf(
        Palette.DAY to argb(0xFF, 0xFF, 0x00, 0xFF),   // magenta (255,0,255)  §7.3.1
        Palette.DUSK to argb(0xFF, 0xC8, 0x00, 0xC8),  // magenta, mid         §7.3.1; TODO(待标准: 62288)
        Palette.NIGHT to argb(0xFF, 0xA0, 0x00, 0xA0), // magenta, low         §7.3.1; TODO(待标准: 62288)
    )

    /**
     * Doppler RECEDING (sample 14) peak hue per palette — §7.3.1 named colour `green`,
     * distinct from approaching and from the amplitude hue.
     *
     * TODO(待标准: 62288 / 厂商 HMI) — see [approachingPeak].
     */
    private val recedingPeak: Map<Palette, Int> = mapOf(
        Palette.DAY to argb(0xFF, 0x00, 0xFF, 0x00),   // green (0,255,0)      §7.3.1
        Palette.DUSK to argb(0xFF, 0x00, 0xC8, 0x00),  // green, mid           §7.3.1; TODO(待标准: 62288)
        Palette.NIGHT to argb(0xFF, 0x00, 0xA0, 0x00), // green, low           §7.3.1; TODO(待标准: 62288)
    )

    /**
     * Amplitude colour: 4-bit [sample] → ARGB for the given [palette].
     *
     * `0` → [TRANSPARENT]; `1..15` → a strictly monotonically brightening single-hue ramp toward
     * [amplitudePeak]. Strength order (0 weakest … 15 strongest) is pinned by contract `Echo.kt`;
     * a brighter pixel for a stronger return is required for discrimination (§7.3.1, ISO 9241-8).
     *
     * Ramp is linear in the hue's RGB channels: `level i → peakChannel * i / 15`, alpha full for 1..15.
     * TODO(待标准: 62288 §colour-tables) — exact per-level luminance steps (linear here is provisional).
     *
     * Out-of-range [sample] is coerced into `0..15`.
     */
    fun amplitudeColor(sample: Int, palette: Palette = Palette.DAY): Int {
        val s = sample.coerceIn(0, MAX_SAMPLE)
        if (s == 0) return TRANSPARENT
        return ramp(amplitudePeak.getValue(palette), s)
    }

    /**
     * Doppler colour: distinguishes approaching from receding, else falls back to amplitude.
     *
     * - [encoding] == DOPPLER: [SAMPLE_APPROACHING] (15) → [approachingPeak]; [SAMPLE_RECEDING] (14)
     *   → [recedingPeak] (both full-intensity categorical hues, not a ramp); all other samples →
     *   [amplitudeColor].
     * - [encoding] == AMPLITUDE: identical to [amplitudeColor] (renderer can always call this with
     *   the spoke's encoding).
     *
     * Doppler hues are NOT 62388-pinned — see [approachingPeak] / class KDoc.
     * Out-of-range [sample] is coerced into `0..15`.
     */
    fun dopplerColor(sample: Int, encoding: SampleEncoding, palette: Palette = Palette.DAY): Int {
        val s = sample.coerceIn(0, MAX_SAMPLE)
        if (encoding == SampleEncoding.DOPPLER) {
            when (s) {
                SAMPLE_APPROACHING -> return approachingPeak.getValue(palette)
                SAMPLE_RECEDING -> return recedingPeak.getValue(palette)
            }
        }
        return amplitudeColor(s, palette)
    }

    /**
     * Pre-computed lookup table `sample 0..15 → ARGB` for the renderer to index per pixel,
     * avoiding a branch per sample. Index = sample value; length = [LEVELS].
     */
    fun colorTable(encoding: SampleEncoding, palette: Palette = Palette.DAY): IntArray =
        IntArray(LEVELS) { sample -> dopplerColor(sample, encoding, palette) }

    /**
     * Reference colours owned by sibling tasks, declared here ONLY so echo hues can be checked for
     * non-collision (62388 §7.3.1: colours must be visually distinguishable). These are NOT defined
     * by this task — do not treat as authoritative. T2.1 owns background/range-ring; T2.3 owns target
     * symbols. TODO(待标准/T2.1/T2.3) — replace with the real shared constants once published.
     */
    object Reserved {
        /** Background base (T2.1). Echo sample 0 is transparent over this, never equal to it as opaque. */
        const val BACKGROUND_DAY: Int = 0xFF000000.toInt()  // placeholder black; TODO(T2.1)
    }

    /** Linear single-hue brightness ramp: peak's RGB scaled by [level]/15, alpha preserved. */
    private fun ramp(peak: Int, level: Int): Int {
        val a = (peak ushr 24) and 0xFF
        val r = (peak ushr 16) and 0xFF
        val g = (peak ushr 8) and 0xFF
        val b = peak and 0xFF
        return argb(
            a,
            (r * level) / MAX_SAMPLE,
            (g * level) / MAX_SAMPLE,
            (b * level) / MAX_SAMPLE,
        )
    }

    /** Pack 8-bit channels into 0xAARRGGBB. */
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
