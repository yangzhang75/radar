package com.shipradar.app.alarm

import com.shipradar.contract.AlarmPriority
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.color.ColorMapper.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W5-C — day/dusk/night alarm palette per IEC 62288 §4.5/§7.2 (DISP-03).
 */
class AlarmColorsTest {

    private fun lum(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    private fun green(argb: Int) = (argb ushr 8) and 0xFF

    // §4.7.2.1 red = alarm; held constant so an active alarm stays conspicuous at night (class KDoc).
    @Test
    fun `alarm and emergency red is constant across palettes`() {
        for (p in Palette.entries) {
            assertEquals(AlarmColors.ALARM_RED, AlarmColors.colorFor(AlarmPriority.ALARM, p), "alarm $p")
            assertEquals(AlarmColors.ALARM_RED, AlarmColors.colorFor(AlarmPriority.EMERGENCY_ALARM, p), "emergency $p")
        }
    }

    // §4.5/§7.2 dimming: warning & caution must dim day → dusk → night.
    @Test
    fun `warning and caution dim day to dusk to night`() {
        for (pr in listOf(AlarmPriority.WARNING, AlarmPriority.CAUTION)) {
            val day = lum(AlarmColors.colorFor(pr, Palette.DAY))
            val dusk = lum(AlarmColors.colorFor(pr, Palette.DUSK))
            val night = lum(AlarmColors.colorFor(pr, Palette.NIGHT))
            assertTrue(day > dusk && dusk > night, "$pr must dim: day($day) > dusk($dusk) > night($night)")
        }
    }

    // §4.7.2.1 alarm is red — high red channel, low green/blue, in every palette.
    @Test
    fun `alarm coding is red in every palette`() {
        for (p in Palette.entries) {
            val a = AlarmColors.colorFor(AlarmPriority.ALARM, p)
            val r = (a ushr 16) and 0xFF; val g = (a ushr 8) and 0xFF; val b = a and 0xFF
            assertTrue(r > 200 && g <= 40 && b <= 40 && r >= 3 * maxOf(g, b), "must be red, got ($r,$g,$b) in $p")
        }
    }

    // §5.4.1.1 / §4.4.1.1 NOTE 5: at night the alarm red must stay ≥1:2 brighter than the NIGHT echo
    // red of ColorMapper, so an alarm is never confused with a strong echo. (Why alarm is not dimmed.)
    @Test
    fun `night alarm red is at least 1 to 2 brighter than night echo red`() {
        val alarm = lum(AlarmColors.colorFor(AlarmPriority.ALARM, Palette.NIGHT))
        // strongest amplitude echo (level LEVELS-1); amplitudeColor is amplitude-only (not Doppler).
        val echo = lum(ColorMapper.amplitudeColor(ColorMapper.LEVELS - 1, Palette.NIGHT))
        assertTrue(alarm >= 2.0 * echo, "night alarm($alarm) must be ≥1:2 brighter than night echo($echo)")
    }

    // §4.7.1.1 all colours in the table clearly differ; hue runs red < orange < yellow by green channel.
    @Test
    fun `alert trio is distinguishable by hue ordering in every palette`() {
        for (p in Palette.entries) {
            val al = AlarmColors.colorFor(AlarmPriority.ALARM, p)
            val wa = AlarmColors.colorFor(AlarmPriority.WARNING, p)
            val ca = AlarmColors.colorFor(AlarmPriority.CAUTION, p)
            assertTrue(green(al) < green(wa), "$p alarm green < warning green")
            assertTrue(green(wa) < green(ca), "$p warning green < caution green")
            assertEquals(3, setOf(al, wa, ca).size, "$p alarm/warning/caution all differ")
        }
    }

    // §4.5.1 contrast: foreground is dark on a bright fill, light on a dark/dimmed fill.
    @Test
    fun `on-priority foreground contrasts the fill`() {
        assertEquals(AlarmColors.ON_PRIORITY_DARK, AlarmColors.onColorFor(AlarmPriority.CAUTION, Palette.DAY))
        assertEquals(AlarmColors.ON_PRIORITY_LIGHT, AlarmColors.onColorFor(AlarmPriority.WARNING, Palette.NIGHT))
        assertEquals(AlarmColors.ON_PRIORITY_LIGHT, AlarmColors.onColorFor(AlarmPriority.ALARM, Palette.NIGHT))
    }

    @Test
    fun `acknowledged dim darkens at night and default arg is day`() {
        assertTrue(lum(AlarmColors.acknowledgedDim(Palette.NIGHT)) < lum(AlarmColors.acknowledgedDim(Palette.DAY)))
        assertEquals(AlarmColors.colorFor(AlarmPriority.WARNING), AlarmColors.colorFor(AlarmPriority.WARNING, Palette.DAY))
    }
}
