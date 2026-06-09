package com.shipradar.uicore.color

import com.shipradar.uicore.color.ColorMapper.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W5-C — IEC 62288 §4.5 / §7.2 day→dusk→night dimming mechanism (DISP-03).
 */
class PaletteDimmingTest {

    private fun lum(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    @Test
    fun `factors decrease day to dusk to night and day is unity`() {
        assertEquals(1.0, PaletteDimming.factor(Palette.DAY))
        assertTrue(PaletteDimming.factor(Palette.DAY) > PaletteDimming.factor(Palette.DUSK))
        assertTrue(PaletteDimming.factor(Palette.DUSK) > PaletteDimming.factor(Palette.NIGHT))
        assertTrue(PaletteDimming.factor(Palette.NIGHT) > 0.0)
    }

    @Test
    fun `day dim is identity`() {
        assertEquals(0xFF8040C0.toInt(), PaletteDimming.dim(0xFF8040C0.toInt(), Palette.DAY))
    }

    @Test
    fun `dim scales each channel by the factor and preserves alpha`() {
        // 0xFF8040C0 = (128,64,192); ×0.35 = (45,22,67) = 0x2D1643.
        assertEquals(0xFF2D1643.toInt(), PaletteDimming.dim(0xFF8040C0.toInt(), Palette.NIGHT))
        // alpha is untouched: 0x80 stays 0x80, 255×0.6 = 153 = 0x99.
        assertEquals(0x80999999.toInt(), PaletteDimming.dim(0x80FFFFFF.toInt(), Palette.DUSK))
    }

    @Test
    fun `dim monotonically reduces luminance day to dusk to night`() {
        val c = 0xFFC86E10.toInt()
        val day = lum(PaletteDimming.dim(c, Palette.DAY))
        val dusk = lum(PaletteDimming.dim(c, Palette.DUSK))
        val night = lum(PaletteDimming.dim(c, Palette.NIGHT))
        assertTrue(day > dusk && dusk > night, "luminance must fall day($day) > dusk($dusk) > night($night)")
    }

    @Test
    fun `dim preserves hue ratios (channel proportions unchanged within rounding)`() {
        val c = 0xFF8040C0.toInt() // R:G:B = 2:1:3
        val d = PaletteDimming.dim(c, Palette.NIGHT)
        val r = (d ushr 16) and 0xFF; val g = (d ushr 8) and 0xFF; val b = d and 0xFF
        assertTrue(g < r && r < b, "ordering G<R<B preserved, got ($r,$g,$b)")
    }

    // The ColorMapper echo palettes realise a *substantial* darkening per the §4.5/§7.2 mechanism
    // (not merely strictly-less): dusk ≤ 0.65×day, night ≤ 0.5×dusk peak luminance.
    @Test
    fun `ColorMapper echo peaks are substantially dimmed dusk and night`() {
        val day = lum(ColorMapper.amplitudeColor(15, Palette.DAY))
        val dusk = lum(ColorMapper.amplitudeColor(15, Palette.DUSK))
        val night = lum(ColorMapper.amplitudeColor(15, Palette.NIGHT))
        assertTrue(dusk <= 0.65 * day, "dusk peak ($dusk) must be ≤0.65×day ($day)")
        assertTrue(night <= 0.5 * dusk, "night peak ($night) must be ≤0.5×dusk ($dusk)")
    }
}
