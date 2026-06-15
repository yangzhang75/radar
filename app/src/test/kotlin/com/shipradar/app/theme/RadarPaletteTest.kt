package com.shipradar.app.theme

import com.shipradar.app.framework.ObTheme
import com.shipradar.uicore.color.ColorMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * W6-B — IEC 62288 Ed.2 / MSC.191(79) colour & brilliance invariants for the day/dusk/night radar
 * palette ([RadarPalette], pure Kotlin so it is JVM-unit-testable; the Compose `ThemePanel` is the leaf).
 *
 * These lock the standard-pinned *relationships* (62288 fixes the method, not the ARGB — exact
 * chromaticity is delegated to IHO S-52, §4.5.1). They mirror the chrome-token audit ([com.shipradar
 * .app.framework.ObTokensTest]) at the radar-palette boundary, and additionally cover the §7.2.1
 * brilliance back-light axis introduced by this task.
 */
class RadarPaletteTest {

    private fun lum(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    private fun red(argb: Int) = (argb ushr 16) and 0xFF
    private fun green(argb: Int) = (argb ushr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    // --- mode mapping (one colour table per ambient condition) ---

    @Test
    fun `ThemeMode maps onto ObTheme and ColorMapper Palette`() {
        assertEquals(ObTheme.DAY, ThemeMode.DAY.toObTheme())
        assertEquals(ObTheme.DUSK, ThemeMode.DUSK.toObTheme())
        assertEquals(ObTheme.NIGHT, ThemeMode.NIGHT.toObTheme())
        assertEquals(ColorMapper.Palette.DAY, ThemeMode.DAY.toEchoPalette())
        assertEquals(ColorMapper.Palette.NIGHT, ThemeMode.NIGHT.toEchoPalette())
        // 阴天(OVERCAST)= 白天色板,靠默认亮度区分(非新增色板),故映射到 DAY 的 chrome/echo。
        assertEquals(ObTheme.DAY, ThemeMode.OVERCAST.toObTheme())
        assertEquals(ColorMapper.Palette.DAY, ThemeMode.OVERCAST.toEchoPalette())
        // 三套基础色板仍齐全且各自被某模式使用。
        assertEquals(3, ObTheme.entries.size)
        assertEquals(3, ColorMapper.Palette.entries.size)
        assertEquals(4, ThemeMode.entries.size) // DAY/OVERCAST/DUSK/NIGHT
    }

    @Test
    fun `radarPalette echo peak matches ColorMapper for the matching palette`() {
        for (m in ThemeMode.entries) {
            assertEquals(
                ColorMapper.amplitudeColor(ColorMapper.SAMPLE_APPROACHING, m.toEchoPalette()),
                radarPalette(m).echoPeak,
                "$m echo peak must equal ColorMapper peak",
            )
        }
    }

    // --- §7.2.1 dark adaptation: with the real OpenBridge 5.0 palettes day chrome is LIGHT, so raw text
    // luminance is not monotone day→dusk→night. The cert-meaningful step is that the night chrome surface
    // is the darkest (the absolute light step down to night is also enforced by defaultBrilliance below). ---

    @Test
    fun `night chrome surface is darkest for dark adaptation`() {
        val day = lum(radarPalette(ThemeMode.DAY).surface)
        val dusk = lum(radarPalette(ThemeMode.DUSK).surface)
        val night = lum(radarPalette(ThemeMode.NIGHT).surface)
        assertTrue(night <= dusk, "night($night) ≤ dusk($dusk)")
        assertTrue(dusk < day, "dusk($dusk) < day($day) — day is the light OpenBridge chrome")
        assertTrue(night < 16.0, "night chrome surface must be near-black (lum=$night)")
    }

    // --- §5.4.1.1 dark background + §4.5.1 lighter foreground (≥1:2, NOTE 5) ---

    @Test
    fun `background is dark and text is at least 1 to 2 lighter in every mode`() {
        for (m in ThemeMode.entries) {
            val p = radarPalette(m)
            val bg = lum(p.background)
            assertTrue(bg < 16.0, "$m background must be dark (lum=$bg)")
            assertTrue(lum(p.textPrimary) >= 2.0 * bg, "$m text must be ≥1:2 lighter than background")
        }
    }

    // --- §4.7.2.1 red = alarm; §4.7.1.1 accent non-red & distinct ---

    @Test
    fun `alarm is dominant red and accent is non-red in every mode`() {
        for (m in ThemeMode.entries) {
            val p = radarPalette(m)
            assertTrue(
                red(p.alarm) >= 200 && red(p.alarm) > 3 * green(p.alarm) && red(p.alarm) > 3 * blue(p.alarm),
                "$m alarm must be dominant red",
            )
            assertTrue(green(p.accent) > 0 || blue(p.accent) > 0, "$m accent must be non-red")
            assertNotEquals(p.alarm, p.accent, "$m accent must differ from alarm")
        }
    }

    // --- §7.2.1 brilliance back-light axis ---

    @Test
    fun `brillianceFactor is bounded, increasing, full at 1 and floored at 0`() {
        assertEquals(1.0f, RadarPalette.brillianceFactor(1.0f))
        assertEquals(RadarPalette.MIN_BRILLIANCE_FACTOR, RadarPalette.brillianceFactor(0.0f))
        // strictly increasing across the range
        var prev = RadarPalette.brillianceFactor(0.0f)
        var x = 0.1f
        while (x <= 1.0f + 1e-4f) {
            val cur = RadarPalette.brillianceFactor(x)
            assertTrue(cur > prev, "factor must increase: f($x)=$cur > $prev")
            prev = cur
            x += 0.1f
        }
        // floor is positive (never fully dark) and below full
        assertTrue(RadarPalette.MIN_BRILLIANCE_FACTOR in 0.0f..1.0f && RadarPalette.MIN_BRILLIANCE_FACTOR > 0f)
    }

    @Test
    fun `brillianceFactor clamps out of range input`() {
        assertEquals(RadarPalette.MIN_BRILLIANCE_FACTOR, RadarPalette.brillianceFactor(-5f))
        assertEquals(1.0f, RadarPalette.brillianceFactor(5f))
    }

    @Test
    fun `atBrilliance at full is identity`() {
        for (m in ThemeMode.entries) {
            assertEquals(radarPalette(m), radarPalette(m).atBrilliance(1.0f), "$m full brilliance = identity")
        }
    }

    @Test
    fun `atBrilliance dims luminance while preserving alpha and hue ordering`() {
        val p = radarPalette(ThemeMode.DAY)
        val dim = p.atBrilliance(0.2f)
        // every lit colour gets darker (or stays, if already 0); none gets brighter
        assertTrue(lum(dim.textPrimary) < lum(p.textPrimary), "text dims")
        assertTrue(lum(dim.surface) <= lum(p.surface), "surface dims")
        // alpha untouched (a back-light scales emitted light, not opacity)
        assertEquals(alpha(p.textPrimary), alpha(dim.textPrimary), "alpha preserved")
        assertEquals(alpha(p.alarm), alpha(dim.alarm), "alarm alpha preserved")
        // hue (channel ratios → coding) preserved: alarm stays dominant red after dimming
        assertTrue(
            red(dim.alarm) > 0 && red(dim.alarm) > 3 * green(dim.alarm) && red(dim.alarm) > 3 * blue(dim.alarm),
            "alarm stays dominant red",
        )
    }

    @Test
    fun `lower brilliance is never brighter than higher brilliance`() {
        val p = radarPalette(ThemeMode.DUSK)
        val low = p.atBrilliance(0.1f)
        val high = p.atBrilliance(0.9f)
        assertTrue(lum(low.textPrimary) <= lum(high.textPrimary), "lower brilliance ≤ higher")
        assertTrue(lum(low.echoPeak) <= lum(high.echoPeak), "echo follows brilliance")
    }

    @Test
    fun `defaultBrilliance protects dark adaptation - night lower than day`() {
        assertEquals(1.0f, RadarPalette.defaultBrilliance(ThemeMode.DAY))
        assertTrue(
            RadarPalette.defaultBrilliance(ThemeMode.NIGHT) < RadarPalette.defaultBrilliance(ThemeMode.DAY),
            "night default brilliance must be lower than day (§7.2.1)",
        )
    }
}
