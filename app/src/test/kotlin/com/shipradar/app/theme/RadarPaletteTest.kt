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
    fun `ThemeMode maps one to one onto ObTheme and ColorMapper Palette`() {
        assertEquals(ObTheme.DAY, ThemeMode.DAY.toObTheme())
        assertEquals(ObTheme.DUSK, ThemeMode.DUSK.toObTheme())
        assertEquals(ObTheme.NIGHT, ThemeMode.NIGHT.toObTheme())
        assertEquals(ColorMapper.Palette.DAY, ThemeMode.DAY.toEchoPalette())
        assertEquals(ColorMapper.Palette.NIGHT, ThemeMode.NIGHT.toEchoPalette())
        assertEquals(ThemeMode.entries.size, ObTheme.entries.size)
        assertEquals(ThemeMode.entries.size, ColorMapper.Palette.entries.size)
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

    // --- §4.4.1.1 Table 1 + §7.2.1: presentation luminance decreases day → dusk → night ---

    @Test
    fun `text luminance decreases day to dusk to night`() {
        val day = lum(radarPalette(ThemeMode.DAY).textPrimary)
        val dusk = lum(radarPalette(ThemeMode.DUSK).textPrimary)
        val night = lum(radarPalette(ThemeMode.NIGHT).textPrimary)
        assertTrue(day > dusk && dusk > night, "day($day) > dusk($dusk) > night($night)")
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
    fun `alarm is pure red and accent is non-red in every mode`() {
        for (m in ThemeMode.entries) {
            val p = radarPalette(m)
            assertTrue(red(p.alarm) > 0 && green(p.alarm) == 0 && blue(p.alarm) == 0, "$m alarm pure red")
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
        // hue (channel ratios → coding) preserved: alarm stays pure red after dimming
        assertTrue(red(dim.alarm) > 0 && green(dim.alarm) == 0 && blue(dim.alarm) == 0, "alarm stays pure red")
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
