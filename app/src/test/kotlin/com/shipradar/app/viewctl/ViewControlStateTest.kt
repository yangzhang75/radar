package com.shipradar.app.viewctl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W7-B — hoistable view-control state. Compose snapshot state works off-composition in plain JVM
 * tests (no Robolectric needed).
 */
class ViewControlStateTest {

    private val eps = 1e-5f

    @Test fun nudge_is_noop_while_off_center_disabled() {
        val s = ViewControlState(offCenterEnabled = false)
        s.nudge(0.3f, 0.3f)
        assertEquals(ViewOffset.CENTERED, s.offset)
        assertFalse(s.isOffCenter)
    }

    @Test fun nudge_applies_and_clamps_when_enabled() {
        val s = ViewControlState(offCenterEnabled = true)
        s.nudge(5f, 0f) // beyond limit
        assertEquals(ViewOffset.MAX_OFFSET_FRACTION, s.offset.magnitude, eps)
        assertTrue(s.isOffCenter)
    }

    @Test fun effective_offset_is_zero_when_disabled_but_stored_offset_kept() {
        val s = ViewControlState(offCenterEnabled = true, offset = ViewOffset(0.2f, 0.1f))
        s.setOffCenter(false)
        assertEquals(ViewOffset.CENTERED, s.effectiveOffset) // not applied
        assertEquals(0.2f, s.offset.x, eps)                  // but remembered
        s.setOffCenter(true)
        assertEquals(0.2f, s.effectiveOffset.x, eps)         // restored
    }

    @Test fun reset_recenters() {
        val s = ViewControlState(offCenterEnabled = true, offset = ViewOffset(0.4f, 0.4f))
        s.reset()
        assertEquals(ViewOffset.CENTERED, s.offset)
        assertFalse(s.isOffCenter)
    }

    @Test fun tm_look_ahead_reset_enables_and_positions() {
        val s = ViewControlState(offCenterEnabled = false)
        s.tmLookAheadReset()
        assertTrue(s.offCenterEnabled)
        assertEquals(ViewOffset.LOOK_AHEAD_FRACTION, s.effectiveOffset.y, eps)
    }

    @Test fun constructor_clamps_initial_offset() {
        val s = ViewControlState(offCenterEnabled = true, offset = ViewOffset(2f, 0f))
        assertEquals(ViewOffset.MAX_OFFSET_FRACTION, s.offset.magnitude, eps)
    }
}
