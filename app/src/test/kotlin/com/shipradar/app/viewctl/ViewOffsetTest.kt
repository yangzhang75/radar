package com.shipradar.app.viewctl

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W7-B — off-centre / look-ahead offset logic (IEC 62388 §10.4). Pure JVM.
 */
class ViewOffsetTest {

    private val eps = 1e-5f

    @Test fun within_limit_offsets_are_unchanged() {
        val o = ViewOffset.CENTERED.offsetBy(0.2f, -0.1f)
        assertEquals(0.2f, o.x, eps)
        assertEquals(-0.1f, o.y, eps)
        assertTrue(o.isOffCenter)
    }

    @Test fun magnitude_is_clamped_to_max_preserving_direction() {
        // push way past the 0.66 limit along +x; should clamp to exactly 0.66 in the same direction
        val o = ViewOffset.CENTERED.offsetBy(5f, 0f)
        assertEquals(ViewOffset.MAX_OFFSET_FRACTION, o.magnitude, eps)
        assertEquals(ViewOffset.MAX_OFFSET_FRACTION, o.x, eps)
        assertEquals(0f, o.y, eps)
    }

    @Test fun diagonal_overflow_clamps_to_limit_circle_keeping_angle() {
        val o = ViewOffset.CENTERED.offsetBy(1f, 1f) // 45°, magnitude √2 >> 0.66
        assertEquals(ViewOffset.MAX_OFFSET_FRACTION, o.magnitude, eps)
        assertEquals(o.x, o.y, eps) // still on the 45° diagonal
    }

    @Test fun limit_is_within_iec_50_to_75_percent_band() {
        // IEC 62388 §10.4.2.1 (MSC.192/5.21.2): between 50% and 75% of the radius.
        assertTrue(ViewOffset.MAX_OFFSET_FRACTION in 0.50f..0.75f)
    }

    @Test fun reset_returns_to_centre() {
        val o = ViewOffset(0.4f, 0.4f)
        assertTrue(o.isOffCenter)
        val r = o.reset()
        assertEquals(0f, r.x, eps)
        assertEquals(0f, r.y, eps)
        assertFalse(r.isOffCenter)
        assertEquals(ViewOffset.CENTERED, r)
    }

    @Test fun magnitude_matches_hypot() {
        val o = ViewOffset(0.3f, 0.4f)
        assertEquals(hypot(0.3f, 0.4f), o.magnitude, eps) // = 0.5
    }

    @Test fun tm_look_ahead_reset_default_pushes_own_ship_down() {
        // ahead = screen-up (0°) ⇒ CCRP moves to +y so the area ahead fills the screen
        val o = ViewOffset.tmLookAheadReset()
        assertEquals(0f, o.x, eps)
        assertEquals(ViewOffset.LOOK_AHEAD_FRACTION, o.y, eps)
        assertTrue(o.magnitude <= ViewOffset.MAX_OFFSET_FRACTION + eps)
    }

    @Test fun tm_look_ahead_reset_rotates_with_ahead_direction() {
        // ahead = screen-right (90°) ⇒ CCRP moves to -x
        val o = ViewOffset.tmLookAheadReset(90f)
        assertEquals(-ViewOffset.LOOK_AHEAD_FRACTION, o.x, eps)
        assertEquals(0f, o.y, eps)
    }
}
