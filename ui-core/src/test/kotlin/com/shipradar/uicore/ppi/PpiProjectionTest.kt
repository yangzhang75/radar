package com.shipradar.uicore.ppi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PpiProjectionTest {

    private val center = ScreenPoint(100.0, 100.0)
    private val radius = 100.0
    private val eps = 1e-9

    private fun assertPoint(expectedX: Double, expectedY: Double, p: ScreenPoint) {
        assertEquals(expectedX, p.x, 1e-7)
        assertEquals(expectedY, p.y, 1e-7)
    }

    // ---- HEAD_UP: azimuth 0 = bow = up, clockwise ----

    @Test fun head_up_cardinal_points() {
        val proj = PpiProjection.create(center, radius, PpiOrientation.HEAD_UP)
        assertPoint(100.0, 0.0, proj.polarToScreen(0.0, 1.0))    // bow -> up
        assertPoint(200.0, 100.0, proj.polarToScreen(90.0, 1.0)) // starboard -> right
        assertPoint(100.0, 200.0, proj.polarToScreen(180.0, 1.0))// astern -> down
        assertPoint(0.0, 100.0, proj.polarToScreen(270.0, 1.0))  // port -> left
    }

    @Test fun head_up_range_fraction_scales_radius() {
        val proj = PpiProjection.create(center, radius, PpiOrientation.HEAD_UP)
        assertPoint(150.0, 100.0, proj.polarToScreen(90.0, 0.5))
        assertPoint(100.0, 100.0, proj.polarToScreen(123.0, 0.0)) // centre regardless of azimuth
    }

    // ---- NORTH_UP: north fixed up, azimuth-stabilised by heading ----

    @Test fun north_up_bow_points_to_heading() {
        val proj = PpiProjection.create(center, radius, PpiOrientation.NORTH_UP, headingDeg = 90.0)
        // ship heading east: bow (relative az 0) draws to the right
        assertPoint(200.0, 100.0, proj.polarToScreen(0.0, 1.0))
        // a target due true-north sits at relative azimuth 270 when heading is 090 -> up on screen
        assertPoint(100.0, 0.0, proj.polarToScreen(270.0, 1.0))
    }

    // ---- COURSE_UP: course (COG) fixed up ----

    @Test fun course_up_puts_course_at_top() {
        val proj = PpiProjection.create(center, radius, PpiOrientation.COURSE_UP, headingDeg = 90.0, courseDeg = 45.0)
        // a target on the true course (045) -> relative az 315 -> up
        assertPoint(100.0, 0.0, proj.polarToScreen(315.0, 1.0))
    }

    @Test fun stabilised_modes_require_their_inputs() {
        assertFailsWith<IllegalArgumentException> {
            PpiProjection.create(center, radius, PpiOrientation.NORTH_UP)
        }
        assertFailsWith<IllegalArgumentException> {
            PpiProjection.create(center, radius, PpiOrientation.COURSE_UP, headingDeg = 10.0)
        }
    }

    // ---- inverse ----

    @Test fun screen_to_polar_inverts_polar_to_screen() {
        val proj = PpiProjection.create(center, radius, PpiOrientation.NORTH_UP, headingDeg = 33.0)
        for (az in listOf(0.0, 17.0, 90.0, 213.7, 359.0)) {
            for (frac in listOf(0.1, 0.5, 1.0)) {
                val back = proj.screenToPolar(proj.polarToScreen(az, frac))
                // compare modulo 360 so the 0°≡360° boundary doesn't spuriously fail
                val diff = ((back.azimuthDeg - az + 540.0) % 360.0) - 180.0
                assertTrue(kotlin.math.abs(diff) < 1e-6, "az=$az back=${back.azimuthDeg}")
                assertEquals(frac, back.rangeFraction, 1e-9)
            }
        }
    }

    @Test fun rotation_resolution_matches_definitions() {
        assertEquals(0.0, resolveDisplayRotationDeg(PpiOrientation.HEAD_UP), eps)
        assertEquals(90.0, resolveDisplayRotationDeg(PpiOrientation.NORTH_UP, headingDeg = 90.0), eps)
        assertEquals(45.0, resolveDisplayRotationDeg(PpiOrientation.COURSE_UP, headingDeg = 90.0, courseDeg = 45.0), eps)
        // negative wraps into [0,360)
        assertEquals(315.0, resolveDisplayRotationDeg(PpiOrientation.COURSE_UP, headingDeg = 0.0, courseDeg = 45.0), eps)
    }

    @Test fun standalone_helper_matches_projection() {
        val a = polarToScreen(57.0, 0.8, center, radius, PpiOrientation.HEAD_UP)
        val b = PpiProjection.create(center, radius, PpiOrientation.HEAD_UP).polarToScreen(57.0, 0.8)
        assertTrue(kotlin.math.abs(a.x - b.x) < eps && kotlin.math.abs(a.y - b.y) < eps)
    }
}
