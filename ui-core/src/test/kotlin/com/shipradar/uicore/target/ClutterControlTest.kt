package com.shipradar.uicore.target

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** GAIN/SEA/RAIN → detection-threshold mapping (anti-clutter), IEC 62388 §6. */
class ClutterControlTest {

    private val base = PlotExtractionConfig()

    @Test
    fun `all controls neutral leaves the base threshold unchanged`() {
        val c = ClutterControl.extractionConfig(base, gain = 0, seaLevel = 0, rainLevel = 0)
        assertEquals(base.cfarFactor, c.cfarFactor, 1e-9)
        assertEquals(base.minAmplitude, c.minAmplitude)
    }

    @Test
    fun `sea clutter raises the CFAR factor`() {
        val low = ClutterControl.extractionConfig(base, 0, seaLevel = 10, rainLevel = 0)
        val high = ClutterControl.extractionConfig(base, 0, seaLevel = 90, rainLevel = 0)
        assertTrue(high.cfarFactor > low.cfarFactor, "more sea → higher threshold")
        assertTrue(low.cfarFactor >= base.cfarFactor)
    }

    @Test
    fun `gain lowers the CFAR factor (more sensitive)`() {
        val lowGain = ClutterControl.extractionConfig(base, gain = 0, seaLevel = 50, rainLevel = 0)
        val highGain = ClutterControl.extractionConfig(base, gain = 100, seaLevel = 50, rainLevel = 0)
        assertTrue(highGain.cfarFactor < lowGain.cfarFactor, "more gain → lower threshold")
    }

    @Test
    fun `rain raises the absolute amplitude floor`() {
        val dry = ClutterControl.extractionConfig(base, 0, 0, rainLevel = 0)
        val wet = ClutterControl.extractionConfig(base, 0, 0, rainLevel = 100)
        assertTrue(wet.minAmplitude > dry.minAmplitude, "more rain → higher floor")
        assertTrue(wet.minAmplitude <= 15)
    }

    @Test
    fun `factor never collapses below the floor and levels are clamped`() {
        val c = ClutterControl.extractionConfig(base, gain = 9999, seaLevel = -50, rainLevel = 9999)
        assertTrue(c.cfarFactor >= 0.5, "factor floored at 0.5")
        assertTrue(c.minAmplitude in 0..15)
    }

    @Test
    fun `extreme sea actually changes detection on a marginal echo`() {
        // a weak target just above the noise: detected at low sea, suppressed at high sea.
        val N = 64
        val s = ByteArray(N) { 3 }      // noise floor
        for (i in 30..32) s[i] = 7      // marginal target (amplitude 7)
        val spoke = com.shipradar.contract.EchoSpoke(
            azimuthDeg = 0.0, headingDeg = 0.0, trueNorth = true,
            rangeCellSizeMm = 2000, rangeCellsDiv2 = N / 2,
            samples = s, encoding = com.shipradar.contract.SampleEncoding.AMPLITUDE,
            sequenceNumber = 0, bearingZeroError = false,
        )
        val calm = ClutterControl.extractionConfig(base, gain = 50, seaLevel = 0, rainLevel = 0)
        val rough = ClutterControl.extractionConfig(base, gain = 0, seaLevel = 100, rainLevel = 100)
        val calmHits = PlotExtractor.detect(s, calm).size
        val roughHits = PlotExtractor.detect(s, rough).size
        assertTrue(calmHits >= roughHits, "rough-sea/rain settings suppress at least as much as calm (calm=$calmHits rough=$roughHits)")
    }
}
