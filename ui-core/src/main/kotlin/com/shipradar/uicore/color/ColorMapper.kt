package com.shipradar.uicore.color

import com.shipradar.contract.SampleEncoding

/**
 * T2.2 — Echo colouring (DISP-03). Pure, platform-independent mapping
 * `(sample 0..15, encoding, palette) -> packed ARGB Int (0xAARRGGBB)`.
 *
 * No Android / Compose types: the :app renderer (T2.1) takes these Ints to Canvas/GL.
 *
 * ## Standards basis (type-cert critical — read before changing a value)
 *
 * The governing clause is **IEC 62288 Ed.2 §5.4.1.1 (MSC191/6.3.1) "Radar video images"**, which
 * fixes the *method* for colouring echoes (the numbers below implement it):
 *  - echoes are drawn with **"a basic colour that provides optimum contrast"** — one basic colour
 *    per palette, here a member of the IEC 62388 §7.3.1 named set (yellow / orange / red);
 *  - **"the relative strength of radar echoes may be differentiated by tones of the same basic
 *    colour"** — this *pins* the single-hue brightness ramp ([ramp]); it is no longer provisional;
 *  - **"the colours may be different for operation under different ambient light conditions
 *    (day, dusk and night)"** — this *pins* the per-palette distinct basic colour ([Palette]);
 *  - **"for radar displays a dark non-reflecting background shall be used"** — background is dark
 *    for every palette (see [Reserved]); echo sample 0 is transparent so it shows through;
 *  - **"if the colour red is used for the radar video image, then it shall be distinguishable from
 *    other uses of the colour red, for example, alarms including dangerous targets"** — the NIGHT
 *    basic colour (red) is therefore a *dark* red, ≥1:2 dimmer than a saturated alert red
 *    (§4.5.1 NOTE 5 defines "visually distinguishable" as ≥1:2 luminance ratio);
 *  - **"additional processed radar information ... may be differentiated ... by tones of other
 *    basic colours"** — this is the clause that authorises the Doppler dual-colour scheme
 *    ([approachingPeak] / [recedingPeak]).
 *
 * Supporting clauses: §4.5.1 (MSC191/5.3.2 — "lighter foreground information on a dark non-reflecting
 * background"; NOTE 5 — distinguishable ≥1:2 luminance); §4.7.1.1 (MSC191/5.5.1 — all colours in a
 * table shall clearly differ); §4.7.2.1 (MSC191/5.5.2) and §A.5 (SN243/1 — red reserved for alarm /
 * dangerous-target coding); Table 1 + §6.2.3 + §7.2.1 (day 200 / dusk 10 / night-darkness cd/m²,
 * brightness adjustable, dark adaptation maintained at night ⇒ peak luminance day > dusk > night).
 *
 * ## What IEC 62288 still does NOT pin (remaining `TODO(待标准)` — see delivery report)
 *
 *  - **Exact chromaticity / ARGB** of the radar-echo basic colour. §5.4.1.1 says only "a basic colour
 *    that provides optimum contrast"; §4.5.1 / §4.6.2 delegate exact colour-table chromaticity to the
 *    **IHO S-52 Presentation Library** (or an equivalent set), which is *not* in the project standards
 *    library. The per-palette ARGB below remain a manufacturer / S-52 choice — standard-*compliant*
 *    (named set, dark bg, ≥1:2 distinguishable, day>dusk>night), not standard-*numeric*.
 *  - **Doppler approaching/receding hues.** 62288 has no Doppler clause at all; §5.4.1.1 authorises
 *    the *mechanism* ("tones of other basic colours") but not the magenta/green choice → vendor HMI.
 *  - **Exact per-level luminance steps.** §5.4.1.1 pins "tones of the same basic colour" but gives no
 *    step table; the linear ramp is a design choice (monotonic, as required for discrimination).
 */
object ColorMapper {

    /**
     * Ambient HMI palette. Day/dusk/night switching is required by IEC 62288 §4.5.1 (MSC191/5.3.2)
     * and Table 1 (ambient light: day 200 cd/m² / dusk 10 cd/m² / night darkness). All three use a
     * dark non-reflecting background with a lighter foreground (§5.4.1.1, §4.5.1); peak luminance
     * decreases day → dusk → night so as not to degrade dark adaptation at night (§7.2.1).
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
     * No-signal / background. Sample 0 is fully transparent so the dark PPI background (owned by
     * T2.1, required dark by IEC 62288 §5.4.1.1 "a dark non-reflecting background shall be used")
     * shows through and echoes never paint over it. ARGB alpha = 0.
     *
     * Source: contract `Echo.kt` ("0 = no signal"); IEC 62288 §5.4.1.1 (radar background).
     */
    const val TRANSPARENT: Int = 0x00000000

    /**
     * Peak (sample 15) echo basic colour per palette. IEC 62288 §5.4.1.1 (MSC191/6.3.1) requires
     * "a basic colour that provides optimum contrast" and permits the colour to "be different for
     * operation under different ambient light conditions (day, dusk and night)". Each hue below is a
     * member of the IEC 62388 §7.3.1 named set; peak luminance decreases day → dusk → night to
     * preserve dark adaptation (Table 1; §7.2.1).
     *
     * - DAY  : yellow  — high-contrast basic colour on a dark radar background.
     * - DUSK : orange  — reduced luminance; distinct from the alert red of §4.7.2.1.
     * - NIGHT: dark red — low luminance for scotopic viewing (§4.5.1 lighter-foreground-on-dark).
     *   Per §5.4.1.1 a red radar image must be distinguishable from alert/dangerous-target red;
     *   0x780000 (R=120) is ≥1:2 dimmer than a saturated alert red 0xFF0000 (§4.5.1 NOTE 5).
     *
     * TODO(待标准: IHO S-52) — exact chromaticity is delegated by §4.5.1/§4.6.2 to the IHO S-52
     * Presentation Library (not yet in the standards library); these ARGB are compliant placeholders.
     */
    private val amplitudePeak: Map<Palette, Int> = mapOf(
        // 0xAARRGGBB, alpha = full at peak.
        Palette.DAY to argb(0xFF, 0xFF, 0xFF, 0x00),   // yellow   (255,255,0)  §5.4.1.1 basic colour
        Palette.DUSK to argb(0xFF, 0xC8, 0x6E, 0x00),  // orange   (200,110,0)  §5.4.1.1; dimmer than day
        Palette.NIGHT to argb(0xFF, 0x78, 0x00, 0x00), // dark red (120,0,0)    §5.4.1.1 distinguishable
    )

    /**
     * Doppler APPROACHING (sample 15) basic colour per palette — `magenta` (IEC 62388 §7.3.1 named
     * set), chosen distinct from the amplitude basic colour and from the receding colour.
     *
     * Authorised by IEC 62288 §5.4.1.1: "additional processed radar information ... may be
     * differentiated ... by tones of other basic colours". The *mechanism* is standard-pinned;
     * peak luminance decreases day → dusk → night (Table 1; §7.2.1).
     *
     * TODO(待标准: 厂商 HMI) — 62288 has NO Doppler clause; the specific magenta hue and its
     * night-mode treatment (a non-red bright hue vs dark-adaptation) are a vendor HMI decision.
     */
    private val approachingPeak: Map<Palette, Int> = mapOf(
        Palette.DAY to argb(0xFF, 0xFF, 0x00, 0xFF),   // magenta (255,0,255)  §5.4.1.1 other basic colour
        Palette.DUSK to argb(0xFF, 0xC8, 0x00, 0xC8),  // magenta, mid         §5.4.1.1; dimmer than day
        Palette.NIGHT to argb(0xFF, 0xA0, 0x00, 0xA0), // magenta, low         §5.4.1.1; dimmest
    )

    /**
     * Doppler RECEDING (sample 14) basic colour per palette — `green` (IEC 62388 §7.3.1 named set),
     * distinct from approaching and from the amplitude basic colour.
     *
     * TODO(待标准: 厂商 HMI) — see [approachingPeak]; mechanism §5.4.1.1, specific hue vendor.
     */
    private val recedingPeak: Map<Palette, Int> = mapOf(
        Palette.DAY to argb(0xFF, 0x00, 0xFF, 0x00),   // green (0,255,0)      §5.4.1.1 other basic colour
        Palette.DUSK to argb(0xFF, 0x00, 0xC8, 0x00),  // green, mid           §5.4.1.1; dimmer than day
        Palette.NIGHT to argb(0xFF, 0x00, 0xA0, 0x00), // green, low           §5.4.1.1; dimmest
    )

    /**
     * Amplitude colour: 4-bit [sample] → ARGB for the given [palette].
     *
     * `0` → [TRANSPARENT]; `1..15` → a strictly monotonically brightening single-hue ramp toward
     * [amplitudePeak]. Strength order (0 weakest … 15 strongest) is pinned by contract `Echo.kt`;
     * differentiating strength by "tones of the same basic colour" is pinned by IEC 62288 §5.4.1.1
     * (a brighter pixel for a stronger return — also §4.7.1.1 discrimination).
     *
     * Ramp is linear in the hue's RGB channels: `level i → peakChannel * i / 15`, alpha full for 1..15.
     * TODO(待标准: IHO S-52) — §5.4.1.1 gives no per-level step table; linear is a design choice.
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
     *   [amplitudeColor]. This is the "additional processed radar information ... tones of other
     *   basic colours" path of IEC 62288 §5.4.1.1.
     * - [encoding] == AMPLITUDE: identical to [amplitudeColor] (renderer can always call this with
     *   the spoke's encoding).
     *
     * The specific Doppler hues are NOT 62288-pinned (no Doppler clause) — see [approachingPeak].
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
     * non-collision (IEC 62288 §4.7.1.1 / §4.5.1: colours must be visually distinguishable). These are
     * NOT defined by this task — do not treat as authoritative. T2.1 owns background/range-ring; T2.3
     * owns target symbols. TODO(待标准/T2.1/T2.3) — replace with the real shared constants once published.
     */
    object Reserved {
        /**
         * Radar background base (T2.1). IEC 62288 §5.4.1.1 requires a dark non-reflecting background
         * for radar displays under *every* ambient condition (including day — see §5.4.1.1 NOTE).
         * Echo sample 0 is transparent over this, never equal to it as opaque.
         */
        const val BACKGROUND_DAY: Int = 0xFF000000.toInt()  // placeholder black; TODO(T2.1)
    }

    /** Linear single-hue brightness ramp (§5.4.1.1 "tones of the same basic colour"): peak's RGB scaled by [level]/15, alpha preserved. */
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
