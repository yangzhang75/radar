package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-JVM tests for the T2.3r overlay scene builder (projection, symbology, colour, LOD, capacity). */
class OverlayProjectorTest {

    private val center = ScreenPoint(100.0, 100.0)
    private val radius = 100.0
    private val scaleNm = 12.0
    private val tol = 1e-6

    private fun headUp() = PpiProjection.create(center, radius, PpiOrientation.HEAD_UP)
    private fun northUp(h: Double) = PpiProjection.create(center, radius, PpiOrientation.NORTH_UP, headingDeg = h)

    private fun target(
        id: String = "t", source: TargetSource = TargetSource.RADAR_TT, range: Double = 6.0,
        bearing: Double = 0.0, trueBearing: Boolean = true, course: Double? = null, speed: Double? = null,
        status: TargetStatus = TargetStatus.TRACKED, dangerous: Boolean = false,
        cpa: Double? = null, tcpa: Double? = null,
    ) = TrackedTarget(
        id = id, source = source, rangeNm = range, bearingDeg = bearing, trueBearing = trueBearing,
        courseDeg = course, speedKn = speed, status = status, dangerous = dangerous, cpaNm = cpa, tcpaSec = tcpa,
    )

    @Test fun deadAheadTarget_placedUp_headUp() {
        // range 6 / scale 12 -> fraction 0.5 -> 50 px up from centre.
        val scene = OverlayProjector.project(listOf(target()), OwnShipData(headingDeg = 0.0), headUp(), scaleNm)
        val s = scene.symbols.single()
        assertEquals(100.0, s.at.x, tol)
        assertEquals(50.0, s.at.y, tol)
        assertEquals(SymbolShape.CIRCLE, s.shape) // radar TT -> circle
    }

    @Test fun dueNorthTarget_placedUp_northUp() {
        val scene = OverlayProjector.project(listOf(target(bearing = 0.0)), OwnShipData(headingDeg = 90.0), northUp(90.0), scaleNm)
        val s = scene.symbols.single()
        assertEquals(100.0, s.at.x, tol)
        assertEquals(50.0, s.at.y, tol) // true north sits up in north-up regardless of heading
    }

    @Test fun aisIsTriangleOrientedToCourse() {
        // AIS course 090 true, head-up (rotation 0), heading 0 -> orientation screen angle 90 (points right).
        val ais = target(source = TargetSource.AIS_ACTIVE, course = 90.0, speed = 10.0)
        val scene = OverlayProjector.project(listOf(ais), OwnShipData(headingDeg = 0.0), headUp(), scaleNm)
        val s = scene.symbols.single()
        assertEquals(SymbolShape.TRIANGLE, s.shape)
        assertTrue(s.filled) // activated AIS filled
        assertEquals(90.0, s.orientationScreenDeg!!, tol)
    }

    @Test fun dangerousTarget_isRed() {
        val scene = OverlayProjector.project(listOf(target(dangerous = true)), OwnShipData(headingDeg = 0.0), headUp(), scaleNm)
        val s = scene.symbols.single()
        assertTrue(s.dangerous)
        assertEquals(OverlayColors.DANGER, s.argb)
    }

    @Test fun safeTarget_usesPaletteColour_notRed() {
        val scene = OverlayProjector.project(
            listOf(target()), OwnShipData(headingDeg = 0.0), headUp(), scaleNm,
            OverlayConfig(palette = ColorMapper.Palette.NIGHT),
        )
        val s = scene.symbols.single()
        assertEquals(OverlayColors.target(ColorMapper.Palette.NIGHT), s.argb)
        assertFalse(s.argb == OverlayColors.DANGER)
    }

    @Test fun targetBeyondRangeScale_culled() {
        val scene = OverlayProjector.project(listOf(target(range = 20.0)), OwnShipData(headingDeg = 0.0), headUp(), scaleNm)
        assertTrue(scene.symbols.isEmpty())
        assertEquals(1, scene.culledOffArea)
        assertEquals(0, scene.drawnCount)
    }

    @Test fun trueBearingTarget_withoutHeading_unplaceable() {
        val scene = OverlayProjector.project(listOf(target(trueBearing = true)), OwnShipData(headingDeg = null), headUp(), scaleNm)
        assertEquals(1, scene.unplaceable)
        assertTrue(scene.symbols.isEmpty())
    }

    @Test fun relativeBearingTarget_placeableWithoutHeading() {
        val scene = OverlayProjector.project(
            listOf(target(bearing = 0.0, trueBearing = false)), OwnShipData(headingDeg = null), headUp(), scaleNm,
        )
        assertEquals(1, scene.drawnCount)
    }

    @Test fun trueVector_present_andStartsAtSymbol() {
        val t = target(course = 0.0, speed = 30.0) // heading north, 30 kn
        val scene = OverlayProjector.project(listOf(t), OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 0.0), headUp(), scaleNm)
        val v = scene.vectors.single()
        val s = scene.symbols.single()
        assertEquals(s.at.x, v.from.x, 1e-6)
        assertEquals(s.at.y, v.from.y, 1e-6)
        assertTrue(v.trueMode)
        // 30 kn over 6 min = 3 NM toward bow -> vector head is above (smaller y) the symbol.
        assertTrue(v.to.y < v.from.y)
    }

    @Test fun relativeVectorMode_flagged() {
        val t = target(course = 0.0, speed = 30.0)
        val scene = OverlayProjector.project(
            listOf(t), OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 10.0), headUp(), scaleNm,
            OverlayConfig(trueVectors = false),
        )
        assertFalse(scene.vectors.single().trueMode)
    }

    @Test fun trailProjected_whenProvided() {
        val t = target()
        val trails = mapOf("t" to listOf(Vec2(0.0, 4.0), Vec2(0.0, 5.0), Vec2(0.0, 6.0)))
        val scene = OverlayProjector.project(
            listOf(t), OwnShipData(headingDeg = 0.0), headUp(), scaleNm, OverlayConfig(), trails,
        )
        val trail = scene.trails.single()
        assertEquals(3, trail.points.size)
    }

    @Test fun label_forDangerousTarget_hasCpaTcpa() {
        val t = target(dangerous = true, cpa = 0.5, tcpa = 300.0)
        val scene = OverlayProjector.project(listOf(t), OwnShipData(headingDeg = 0.0), headUp(), scaleNm)
        val label = scene.labels.single()
        assertEquals(2, label.lines.size)
        assertTrue(label.lines[0].contains("CPA"))
        assertTrue(label.lines[1].contains("TCPA"))
    }

    @Test fun statusFlags_mapped() {
        val acq = OverlayProjector.project(listOf(target(status = TargetStatus.ACQUIRING)), OwnShipData(headingDeg = 0.0), headUp(), scaleNm).symbols.single()
        assertTrue(acq.acquiring)
        val lost = OverlayProjector.project(listOf(target(status = TargetStatus.LOST)), OwnShipData(headingDeg = 0.0), headUp(), scaleNm).symbols.single()
        assertTrue(lost.lost)
        val trial = OverlayProjector.project(listOf(target(status = TargetStatus.TEST_MANEUVER)), OwnShipData(headingDeg = 0.0), headUp(), scaleNm).symbols.single()
        assertTrue(trial.trialManeuver)
    }

    @Test fun selectedTarget_flaggedAndLabelled() {
        val scene = OverlayProjector.project(
            listOf(target(id = "sel", cpa = 1.0, tcpa = 600.0)), OwnShipData(headingDeg = 0.0), headUp(), scaleNm,
            OverlayConfig(selectedId = "sel"),
        )
        assertTrue(scene.symbols.single().selected)
        assertNotNull(scene.labels.firstOrNull { it.id == "sel" })
    }
}
