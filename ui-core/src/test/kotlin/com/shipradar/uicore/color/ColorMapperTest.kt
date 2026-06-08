package com.shipradar.uicore.color

import com.shipradar.contract.SampleEncoding
import com.shipradar.uicore.color.ColorMapper.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T2.2 ColorMapper tests (DISP-03).
 *
 * Asserted ARGB values track the provisional anchors in [ColorMapper]; they are pinned to
 * IEC 62388 §7.3.1 *named* hues but their exact luminance/ramp is a 62288 gap. When the 62288
 * colour tables land, these literals move with the constants — they are a regression lock, not a
 * type-approval baseline.
 */
class ColorMapperTest {

    // ---- contract: sample 0 = no signal = transparent background (§7.3.1 / Echo.kt) ----

    @Test
    fun `sample 0 is fully transparent for every palette and encoding`() {
        for (p in Palette.entries) {
            assertEquals(0x00000000, ColorMapper.amplitudeColor(0, p), "amplitude $p")
            assertEquals(0x00000000, ColorMapper.dopplerColor(0, SampleEncoding.AMPLITUDE, p), "dop/amp $p")
            assertEquals(0x00000000, ColorMapper.dopplerColor(0, SampleEncoding.DOPPLER, p), "dop $p")
        }
    }

    // ---- Doppler: approaching (15) and receding (14) must be DIFFERENT colours ----

    @Test
    fun `doppler approaching and receding differ for every palette`() {
        for (p in Palette.entries) {
            val approaching = ColorMapper.dopplerColor(ColorMapper.SAMPLE_APPROACHING, SampleEncoding.DOPPLER, p)
            val receding = ColorMapper.dopplerColor(ColorMapper.SAMPLE_RECEDING, SampleEncoding.DOPPLER, p)
            assertNotEquals(approaching, receding, "approach vs recede must differ ($p)")
        }
    }

    @Test
    fun `doppler hues are the named palette anchors (day)`() {
        // §7.3.1 named set: approaching = magenta, receding = green (provisional, see ColorMapper KDoc).
        assertEquals(
            0xFFFF00FF.toInt(),
            ColorMapper.dopplerColor(15, SampleEncoding.DOPPLER, Palette.DAY),
            "approaching = magenta",
        )
        assertEquals(
            0xFF00FF00.toInt(),
            ColorMapper.dopplerColor(14, SampleEncoding.DOPPLER, Palette.DAY),
            "receding = green",
        )
    }

    @Test
    fun `doppler distinguishes 14 and 15 from the amplitude colour they would otherwise take`() {
        // In DOPPLER mode 14/15 are categorical hues, NOT the amplitude ramp value.
        for (p in Palette.entries) {
            assertNotEquals(
                ColorMapper.amplitudeColor(15, p),
                ColorMapper.dopplerColor(15, SampleEncoding.DOPPLER, p),
                "doppler 15 != amplitude 15 ($p)",
            )
            assertNotEquals(
                ColorMapper.amplitudeColor(14, p),
                ColorMapper.dopplerColor(14, SampleEncoding.DOPPLER, p),
                "doppler 14 != amplitude 14 ($p)",
            )
        }
    }

    @Test
    fun `AMPLITUDE encoding ignores doppler semantics - 14 and 15 stay on the ramp`() {
        for (p in Palette.entries) {
            assertEquals(
                ColorMapper.amplitudeColor(15, p),
                ColorMapper.dopplerColor(15, SampleEncoding.AMPLITUDE, p),
            )
            assertEquals(
                ColorMapper.amplitudeColor(14, p),
                ColorMapper.dopplerColor(14, SampleEncoding.AMPLITUDE, p),
            )
        }
    }

    @Test
    fun `non doppler-special samples fall back to amplitude colour in doppler mode`() {
        for (p in Palette.entries) {
            for (s in 0..13) {
                assertEquals(
                    ColorMapper.amplitudeColor(s, p),
                    ColorMapper.dopplerColor(s, SampleEncoding.DOPPLER, p),
                    "sample $s ($p)",
                )
            }
        }
    }

    // ---- amplitude: strictly monotonic intensity 1..15 (stronger return -> brighter) ----

    @Test
    fun `amplitude luminance is strictly monotonic increasing for samples 1 to 15`() {
        for (p in Palette.entries) {
            var prev = -1.0
            for (s in 1..15) {
                val lum = luminance(ColorMapper.amplitudeColor(s, p))
                assertTrue(lum > prev, "luminance must strictly increase at sample $s ($p): $lum !> $prev")
                prev = lum
            }
        }
    }

    @Test
    fun `amplitude peaks at the named §7-3-1 hue per palette`() {
        assertEquals(0xFFFFFF00.toInt(), ColorMapper.amplitudeColor(15, Palette.DAY), "day peak = yellow")
        assertEquals(0xFFC86E00.toInt(), ColorMapper.amplitudeColor(15, Palette.DUSK), "dusk peak = orange")
        assertEquals(0xFFB40000.toInt(), ColorMapper.amplitudeColor(15, Palette.NIGHT), "night peak = red")
    }

    @Test
    fun `night palette is a low-brightness red-black scheme`() {
        // Night max luminance must stay well below day max (scotopic vision, IEC 62288 night mode).
        val nightMax = luminance(ColorMapper.amplitudeColor(15, Palette.NIGHT))
        val dayMax = luminance(ColorMapper.amplitudeColor(15, Palette.DAY))
        assertTrue(nightMax < dayMax, "night must be dimmer than day: $nightMax !< $dayMax")
        // Red/black: night peak has no green/blue energy.
        val peak = ColorMapper.amplitudeColor(15, Palette.NIGHT)
        assertEquals(0, (peak ushr 8) and 0xFF, "night green channel must be 0")
        assertEquals(0, peak and 0xFF, "night blue channel must be 0")
    }

    @Test
    fun `amplitude samples 1 to 15 are fully opaque`() {
        for (p in Palette.entries) {
            for (s in 1..15) {
                assertEquals(0xFF, (ColorMapper.amplitudeColor(s, p) ushr 24) and 0xFF, "alpha at $s ($p)")
            }
        }
    }

    // ---- all three palettes covered & produce distinct echo colouring ----

    @Test
    fun `the three palettes produce distinct peak echo colours`() {
        val peaks = Palette.entries.map { ColorMapper.amplitudeColor(15, it) }.toSet()
        assertEquals(3, peaks.size, "day/dusk/night peaks must all differ")
    }

    // ---- lookup table matches the per-sample functions ----

    @Test
    fun `colorTable matches dopplerColor for every sample, encoding and palette`() {
        for (p in Palette.entries) {
            for (enc in SampleEncoding.entries) {
                val table = ColorMapper.colorTable(enc, p)
                assertEquals(ColorMapper.LEVELS, table.size)
                for (s in 0..15) {
                    assertEquals(ColorMapper.dopplerColor(s, enc, p), table[s], "table[$s] enc=$enc $p")
                }
            }
        }
    }

    // ---- robustness: out-of-range samples are coerced, never crash ----

    @Test
    fun `out of range samples are coerced into 0 to 15`() {
        assertEquals(ColorMapper.amplitudeColor(0, Palette.DAY), ColorMapper.amplitudeColor(-5, Palette.DAY))
        assertEquals(ColorMapper.amplitudeColor(15, Palette.DAY), ColorMapper.amplitudeColor(99, Palette.DAY))
    }

    // ---- non-collision with reserved (background) colour, §7.3.1 distinguishability ----

    @Test
    fun `no opaque echo colour collides with the reserved background`() {
        for (p in Palette.entries) {
            for (enc in SampleEncoding.entries) {
                for (c in ColorMapper.colorTable(enc, p)) {
                    val opaque = (c ushr 24) and 0xFF == 0xFF
                    if (opaque) assertNotEquals(ColorMapper.Reserved.BACKGROUND_DAY, c)
                }
            }
        }
    }

    /** Rec.709 relative luminance of an ARGB Int, ignoring alpha. */
    private fun luminance(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
