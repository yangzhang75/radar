package com.shipradar.uicore.ppi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplaySizeTest {

    private val eps = 1e-6

    @Test fun cat1_minimum_is_320mm() {
        assertEquals(320.0, DisplaySize.MIN_OPERATIONAL_DISPLAY_DIAMETER_MM, eps)
    }

    @Test fun px_mm_conversions_exact() {
        // 160 dpi: 320 mm -> 320/25.4*160 px
        val px320 = 320.0 / 25.4 * 160.0
        assertEquals(px320, DisplaySize.mmToPx(320.0, 160.0), eps)
        assertEquals(320.0, DisplaySize.pxToMm(px320, 160.0), eps)
        assertEquals(px320, DisplaySize.minDiameterPx(160.0), eps)
        assertEquals(px320 / 2.0, DisplaySize.minRadiusPx(160.0), eps)
    }

    @Test fun compliance_check_at_320mm_boundary() {
        val px320 = DisplaySize.minDiameterPx(160.0)
        assertEquals(320.0, DisplaySize.effectiveDisplayDiameterMm(px320, 160.0), eps)
        assertTrue(DisplaySize.meetsMinimumDisplayArea(px320, 160.0))
        // 2000 px at 160 dpi = 317.5 mm -> fails
        assertFalse(DisplaySize.meetsMinimumDisplayArea(2000.0, 160.0))
        assertEquals(317.5, DisplaySize.effectiveDisplayDiameterMm(2000.0, 160.0), eps)
    }

    @Test fun panel_must_physically_host_320mm() {
        assertFalse(DisplaySize.panelCanHostMinimumArea(panelWidthMm = 300.0, panelHeightMm = 400.0))
        assertTrue(DisplaySize.panelCanHostMinimumArea(panelWidthMm = 350.0, panelHeightMm = 400.0))
    }

    @Test fun fitted_diameter_is_inscribed_circle() {
        assertEquals(1080.0, DisplaySize.fittedDiameterPx(1920.0, 1080.0), eps)
        // worked multi-resolution example: a 1080-px-tall area at 86 dpi
        val mm = DisplaySize.effectiveDisplayDiameterMm(1080.0, 86.0)
        assertEquals(1080.0 / 86.0 * 25.4, mm, eps)
        // 1080 px @ 86 dpi ~= 318.9 mm -> just fails 320 mm, needs lower dpi or more px
        assertFalse(DisplaySize.meetsMinimumDisplayArea(1080.0, 86.0))
    }
}
