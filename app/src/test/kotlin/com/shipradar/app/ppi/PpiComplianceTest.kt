package com.shipradar.app.ppi

import com.shipradar.constants.MANDATORY_RANGE_SCALES_NM
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.RangeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4-B — IEC 62388 Ed.2 display-compliance verification for the render surface (`app/ppi`).
 *
 * The geometry/range/ring/scale math is verified in `:ui-core` (PpiProjectionTest, RangeModelTest,
 * RangeRingsTest, BearingScaleTest, DisplaySizeTest). These tests cover the compliance behaviour
 * that lives in this Android module instead: the configuration the render surface acts on. Pure
 * JVM (no Android graphics), so they run as ordinary unit tests.
 */
class PpiComplianceTest {

    // ---- §10.4.4.1 (MSC.192/5.20.2): three orientation modes + head-up fallback on heading loss --

    @Test fun head_up_always_available() {
        assertEquals(
            PpiOrientation.HEAD_UP,
            PpiConfig(orientation = PpiOrientation.HEAD_UP, headingDeg = null).effectiveOrientation,
        )
    }

    @Test fun north_up_falls_back_to_head_up_without_heading() {
        // azimuth-stabilised mode requires heading; gyro loss ⇒ mandated head-up fallback (§10.4.4.1)
        assertEquals(
            PpiOrientation.HEAD_UP,
            PpiConfig(orientation = PpiOrientation.NORTH_UP, headingDeg = null).effectiveOrientation,
        )
        assertEquals(
            PpiOrientation.NORTH_UP,
            PpiConfig(orientation = PpiOrientation.NORTH_UP, headingDeg = 123.0).effectiveOrientation,
        )
    }

    @Test fun course_up_needs_heading_and_course_else_head_up() {
        assertEquals(
            PpiOrientation.HEAD_UP,
            PpiConfig(orientation = PpiOrientation.COURSE_UP, headingDeg = 90.0, courseDeg = null).effectiveOrientation,
        )
        assertEquals(
            PpiOrientation.HEAD_UP,
            PpiConfig(orientation = PpiOrientation.COURSE_UP, headingDeg = null, courseDeg = 90.0).effectiveOrientation,
        )
        assertEquals(
            PpiOrientation.COURSE_UP,
            PpiConfig(orientation = PpiOrientation.COURSE_UP, headingDeg = 90.0, courseDeg = 45.0).effectiveOrientation,
        )
    }

    // ---- §9.4.1.1: the configured range scale is one of the mandatory scales ---------------------

    @Test fun default_range_scale_is_mandatory() {
        assertTrue(RangeModel.isMandatoryScale(PpiConfig().rangeScaleNm))
    }

    @Test fun every_mandatory_scale_is_a_valid_config() {
        for (scale in MANDATORY_RANGE_SCALES_NM) {
            assertTrue(RangeModel.isMandatoryScale(PpiConfig(rangeScaleNm = scale).rangeScaleNm))
        }
    }

    // ---- spoke tiling: default beam width divides 360° exactly ⇒ no radial gaps ------------------

    @Test fun default_spoke_width_tiles_full_revolution() {
        val spokesPerRev = 360f / PpiConfig().spokeWidthDeg
        assertEquals(2048f, spokesPerRev, 1e-2f)
        assertEquals(0f, 360f % PpiConfig().spokeWidthDeg, 1e-3f)
    }

    // ---- IEC 62288 §5.4.1.1: dark non-reflecting PPI background under every ambient condition ----

    @Test fun background_is_dark_and_opaque_for_every_palette() {
        for (palette in ColorMapper.Palette.entries) {
            val argb = PpiView.backgroundColorFor(palette)
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            assertEquals(0xFF, a, "background must be opaque ($palette)")
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            assertTrue(luma < 40.0, "background must be dark ($palette luma=$luma)")
        }
    }

    @Test fun night_background_is_pure_black_for_dark_adaptation() {
        assertEquals(0xFF000000.toInt(), PpiView.backgroundColorFor(ColorMapper.Palette.NIGHT))
    }
}
