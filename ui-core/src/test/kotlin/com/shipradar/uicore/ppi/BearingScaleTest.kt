package com.shipradar.uicore.ppi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BearingScaleTest {

    private val eps = 1e-7
    private val center = ScreenPoint(100.0, 100.0)
    private val radius = 100.0

    @Test fun head_up_ticks_every_five_degrees_numbered_every_thirty() {
        val ticks = BearingScale.bearingScaleTicks(PpiOrientation.HEAD_UP)
        assertEquals(72, ticks.size) // 360/5
        assertEquals(12, ticks.count { it.level == BearingTickLevel.MAJOR })
        // 5° and 10° marks are distinct levels (IEC 62388 §9.10.2.1)
        assertTrue(ticks.any { it.level == BearingTickLevel.MEDIUM })
        assertTrue(ticks.any { it.level == BearingTickLevel.MINOR })

        val zero = ticks.first { it.bearingDeg == 0.0 }
        assertEquals(BearingTickLevel.MAJOR, zero.level)
        assertEquals("000", zero.label)
        assertEquals(0.0, zero.screenAngleDeg, eps) // 000 at top in head-up

        val thirty = ticks.first { it.bearingDeg == 30.0 }
        assertEquals("030", thirty.label)
        assertEquals(30.0, thirty.screenAngleDeg, eps)

        assertNull(ticks.first { it.bearingDeg == 10.0 }.label)
    }

    @Test fun north_up_puts_000_true_at_top() {
        val ticks = BearingScale.bearingScaleTicks(PpiOrientation.NORTH_UP, headingDeg = 90.0)
        val north = ticks.first { it.bearingDeg == 0.0 }
        assertEquals("000", north.label)
        assertEquals(0.0, north.screenAngleDeg, eps) // true north at the top regardless of heading
        // 090 true sits to the right (screen angle 90)
        assertEquals(90.0, ticks.first { it.bearingDeg == 90.0 }.screenAngleDeg, eps)
    }

    @Test fun course_up_puts_course_at_top() {
        val ticks = BearingScale.bearingScaleTicks(PpiOrientation.COURSE_UP, headingDeg = 90.0, courseDeg = 45.0)
        // bearing 045 true -> top
        assertEquals(0.0, ticks.first { it.bearingDeg == 45.0 }.screenAngleDeg, eps)
    }

    @Test fun fine_ticks_optional() {
        val ticks = BearingScale.bearingScaleTicks(PpiOrientation.HEAD_UP, includeFine = true)
        assertEquals(360, ticks.size)
        assertEquals(BearingTickLevel.FINE, ticks.first { it.bearingDeg == 1.0 }.level)
    }

    @Test fun heading_line_runs_from_ccrp_to_edge() {
        val headUp = PpiProjection.create(center, radius, PpiOrientation.HEAD_UP)
        val (c, edge) = BearingScale.headingLine(headUp)
        assertEquals(center, c)
        assertEquals(100.0, edge.x, eps)
        assertEquals(0.0, edge.y, eps) // straight up in head-up

        val northUp = PpiProjection.create(center, radius, PpiOrientation.NORTH_UP, headingDeg = 90.0)
        val (_, edge2) = BearingScale.headingLine(northUp)
        assertEquals(200.0, edge2.x, eps) // heading east -> to the right
        assertEquals(100.0, edge2.y, eps)
    }

    @Test fun ebl_relative_and_true() {
        val proj = PpiProjection.create(center, radius, PpiOrientation.HEAD_UP)
        val (_, relEnd) = BearingScale.electronicBearingLine(proj, bearingDeg = 90.0, relativeToHeading = true)
        assertEquals(200.0, relEnd.x, eps)
        assertEquals(100.0, relEnd.y, eps)

        val northUp = PpiProjection.create(center, radius, PpiOrientation.NORTH_UP, headingDeg = 90.0)
        val (_, trueEnd) = BearingScale.electronicBearingLine(northUp, bearingDeg = 0.0, relativeToHeading = false, headingDeg = 90.0)
        assertEquals(100.0, trueEnd.x, eps) // true north -> up
        assertEquals(0.0, trueEnd.y, eps)
    }
}
