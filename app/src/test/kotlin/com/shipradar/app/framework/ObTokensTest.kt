package com.shipradar.app.framework

import com.shipradar.uicore.color.ColorMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * W4-C certification audit — IEC 62288 Ed.2 colour/luminance invariants for the OpenBridge chrome
 * tokens ([ObTokens], pure Kotlin so it is JVM-unit-testable even though the rest of `:app` is Compose).
 *
 * These lock the *standard-pinned relationships* (the only thing 62288 fixes — it gives no numeric ARGB;
 * §4.5.2(e) / §5.4.1.2 make colour conformance an observation + IHO-S-52 analytical check). The exact
 * ARGB values are OpenBridge-6.0 visual-reference placeholders and are NOT asserted as cert baselines.
 */
class ObTokensTest {

    private fun lum(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    private fun green(argb: Int) = (argb ushr 8) and 0xFF
    private fun red(argb: Int) = (argb ushr 16) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    // §7.2.1 (maintain dark adaptation) — with the real OpenBridge 5.0 palettes the *day* theme is a
    // LIGHT chrome (dark text on light surface) while dusk/night are dark chrome, so raw foreground
    // luminance is NOT monotone day→dusk→night. The cert-meaningful invariant is that night chrome is
    // the darkest and near-black, so the bridge stays dark-adapted at night.
    @Test
    fun `night chrome is darkest and near-black for dark adaptation`() {
        val day = lum(obTokens(ObTheme.DAY).chromeBackground)
        val dusk = lum(obTokens(ObTheme.DUSK).chromeBackground)
        val night = lum(obTokens(ObTheme.NIGHT).chromeBackground)
        assertTrue(night <= dusk, "night($night) ≤ dusk($dusk)")
        assertTrue(dusk < day, "dusk($dusk) < day($day) — day is the light OpenBridge chrome")
        assertTrue(night < 16.0, "night chrome must be near-black (lum=$night)")
    }

    // §5.4.1.1 "a dark non-reflecting background shall be used" + §4.5.1 "lighter foreground on a dark
    // background" + §4.4.1.1 NOTE 5 (visually distinguishable = luminance ratio ≥ 1:2).
    @Test
    fun `operational background is dark and foreground is at least 1 to 2 lighter`() {
        for (t in ObTheme.entries) {
            val tk = obTokens(t)
            val bg = lum(tk.operationalBackground)
            val fg = lum(tk.foregroundPrimary)
            assertTrue(bg < 16.0, "$t operational background must be dark (lum=$bg)")
            assertTrue(fg >= 2.0 * bg, "$t foreground($fg) must be ≥1:2 lighter than background($bg)")
        }
    }

    // §4.7.2.1 (MSC191/5.5.2) the colour red shall be used for alarm/emergency-alarm coding. The OB 5.0
    // day/dusk alarm is #E2231A and night is pure red — both must read as unmistakably red (red channel
    // dominant: high R, and R well clear of G/B), the relationship 62288 actually pins (it gives no ARGB).
    @Test
    fun `alarm is unmistakably red in every theme`() {
        for (t in ObTheme.entries) {
            val a = obTokens(t).alarm
            assertTrue(
                red(a) >= 200 && red(a) > 3 * green(a) && red(a) > 3 * blue(a),
                "$t alarm must be dominant red, was ${a.toUInt().toString(16)}",
            )
        }
    }

    // §4.7.1.1 (MSC191/5.5.1) all colours in a table shall clearly differ; the selection accent must
    // not be confusable with the alarm red.
    @Test
    fun `accent is non-red and differs from alarm`() {
        for (t in ObTheme.entries) {
            val tk = obTokens(t)
            assertTrue(green(tk.accent) > 0 || blue(tk.accent) > 0, "$t accent must be non-red")
            assertNotEquals(tk.alarm, tk.accent, "$t accent must differ from alarm")
        }
    }

    // §4.7.1.1 alert-priority colours mutually distinguishable. Hue runs red → orange → yellow, so the
    // green channel strictly increases alarm < warning < caution (a robust distinguishability proxy).
    @Test
    fun `alert trio is mutually distinguishable by hue ordering`() {
        for (t in ObTheme.entries) {
            val tk = obTokens(t)
            assertTrue(green(tk.alarm) < green(tk.warning), "$t alarm green < warning green")
            assertTrue(green(tk.warning) < green(tk.caution), "$t warning green < caution green")
            assertEquals(3, setOf(tk.alarm, tk.warning, tk.caution).size, "$t alarm/warning/caution all differ")
        }
    }

    // Chrome ≡ PPI: the echo-legend swatch must be exactly what ColorMapper paints (no drift).
    @Test
    fun `echo legend peak matches ColorMapper strongest echo for the matching palette`() {
        for (t in ObTheme.entries) {
            assertEquals(
                ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, t.echoPalette),
                obTokens(t).echoLegendPeak,
                "$t echo legend must equal ColorMapper peak",
            )
        }
    }

    // §5.4.1.1 "if red is used for the radar video image it shall be distinguishable from other uses of
    // red (alarms)". Night echo red must be ≥1:2 dimmer than the alarm red (ties chrome to ColorMapper).
    @Test
    fun `night echo red is at least 1 to 2 dimmer than alarm red`() {
        val tk = obTokens(ObTheme.NIGHT)
        val echo = lum(tk.echoLegendPeak)
        val alarm = lum(tk.alarm)
        assertTrue(alarm >= 2.0 * echo, "alarm red($alarm) must be ≥1:2 brighter than night echo red($echo)")
    }

    // Foreground hierarchy must stay discriminable: primary most prominent, disabled least. Measured as
    // contrast (luminance distance) against the theme's own chrome background, so it holds for both the
    // light day chrome (dark text) and the dark night chrome (amber text).
    @Test
    fun `foreground prominence primary over secondary over disabled`() {
        for (t in ObTheme.entries) {
            val tk = obTokens(t)
            val bg = lum(tk.chromeBackground)
            fun contrast(x: Int) = kotlin.math.abs(lum(x) - bg)
            assertTrue(contrast(tk.foregroundPrimary) > contrast(tk.foregroundSecondary), "$t primary more prominent than secondary")
            assertTrue(contrast(tk.foregroundSecondary) > contrast(tk.foregroundDisabled), "$t secondary more prominent than disabled")
        }
    }

    // The chrome theme maps 1:1 to the echo palette so day/dusk/night stay in lockstep with the PPI.
    @Test
    fun `ObTheme maps one to one onto ColorMapper Palette`() {
        assertEquals(ColorMapper.Palette.DAY, ObTheme.DAY.echoPalette)
        assertEquals(ColorMapper.Palette.DUSK, ObTheme.DUSK.echoPalette)
        assertEquals(ColorMapper.Palette.NIGHT, ObTheme.NIGHT.echoPalette)
        assertEquals(ColorMapper.Palette.entries.size, ObTheme.entries.size)
    }
}
