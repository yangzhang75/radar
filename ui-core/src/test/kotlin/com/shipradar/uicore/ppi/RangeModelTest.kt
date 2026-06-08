package com.shipradar.uicore.ppi

import com.shipradar.constants.MANDATORY_RANGE_SCALES_NM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeModelTest {

    private val eps = 1e-9

    // ---- over-scan / sample mapping ----

    @Test fun sample_fraction_spans_overscan() {
        assertEquals(0.0, RangeModel.sampleIndexToRangeFraction(0, 1024), eps)
        assertEquals(0.9, RangeModel.sampleIndexToRangeFraction(512, 1024), eps) // 1.8 * 512/1024
        assertEquals(1.8, RangeModel.sampleIndexToRangeFraction(1024, 1024), eps)
    }

    @Test fun in_range_sample_count_matches_doc_example() {
        // doc §3.2: over-scan 1.8, samples ~0..569 in range for a 1024-sample spoke
        assertEquals(569, RangeModel.inRangeSampleCount(1024))
        assertTrue(RangeModel.isSampleInRange(568, 1024))
        assertFalse(RangeModel.isSampleInRange(569, 1024))
        assertFalse(RangeModel.isSampleInRange(900, 1024))
    }

    @Test fun max_displayable_index_honours_plus8pct_cardinal_rule() {
        // IEC 62388 §9.4.1.2(e): displayed range <= +8% -> fraction 1.08
        assertEquals(614, RangeModel.maxDisplayableSampleIndex(1024)) // floor(1.08*1024/1.8)
    }

    // ---- NM <-> fraction <-> px ----

    @Test fun nm_fraction_px_conversions_roundtrip() {
        assertEquals(0.5, RangeModel.rangeNmToFraction(3.0, 6.0), eps)
        assertEquals(3.0, RangeModel.fractionToRangeNm(0.5, 6.0), eps)
        assertEquals(50.0, RangeModel.rangeNmToPx(3.0, 6.0, 100.0), eps)
        assertEquals(3.0, RangeModel.pxToRangeNm(50.0, 6.0, 100.0), eps)
        assertEquals(1852.0, RangeModel.nmToMeters(1.0), eps)
        assertEquals(1.0, RangeModel.metersToNm(1852.0), eps)
    }

    // ---- mandatory range scales (IEC 62388 §9.4.1.1) ----

    @Test fun mandatory_scales_are_the_eight_required() {
        assertEquals(listOf(0.25, 0.5, 0.75, 1.5, 3.0, 6.0, 12.0, 24.0), RangeModel.mandatoryScalesNm)
        assertEquals(MANDATORY_RANGE_SCALES_NM, RangeModel.mandatoryScalesNm)
        assertTrue(RangeModel.isMandatoryScale(6.0))
        assertFalse(RangeModel.isMandatoryScale(5.0))
    }

    @Test fun range_stepping_up_and_down() {
        assertEquals(0.5, RangeModel.nextRangeScale(0.25), eps)
        assertEquals(12.0, RangeModel.nextRangeScale(6.0), eps)
        assertEquals(24.0, RangeModel.nextRangeScale(24.0), eps)  // clamps at top
        assertEquals(3.0, RangeModel.previousRangeScale(6.0), eps)
        assertEquals(0.25, RangeModel.previousRangeScale(0.25), eps) // clamps at bottom
        // non-mandatory current value snaps to nearest mandatory neighbour
        assertEquals(6.0, RangeModel.nextRangeScale(5.0), eps)
        assertEquals(3.0, RangeModel.previousRangeScale(5.0), eps)
    }

    // ---- DISP-02 blanking budget (IEC 62388 §9.4.1.2(d)) ----

    @Test fun blanking_budget_is_one_scan() {
        assertEquals(1, RangeModel.MAX_BLANKING_SCANS)
        assertEquals(2500.0, RangeModel.scanPeriodMs(24.0), eps) // 24 rpm -> 2.5 s
        assertEquals(2500.0, RangeModel.maxBlankingDurationMs(24.0), eps)
    }
}
