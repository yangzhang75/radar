package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ≥240-target capacity + level-of-detail behaviour for the overlay (CAP-01; smooth dense scenes). */
class OverlayCapacityLodTest {

    private val proj = PpiProjection.create(ScreenPoint(500.0, 500.0), 500.0, PpiOrientation.HEAD_UP)
    private val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 5.0)
    private val scaleNm = 24.0

    private fun gen(source: TargetSource, n: Int, base: String, course: Double? = 45.0, speed: Double? = 8.0) =
        (0 until n).map {
            // spread bearings around the clock, ranges well inside the 24 NM scale
            TrackedTarget(
                id = "$base$it", source = source, rangeNm = 2.0 + (it % 18), bearingDeg = (it * 37 % 360).toDouble(),
                trueBearing = true, courseDeg = course, speedKn = speed, status = TargetStatus.TRACKED,
            )
        }

    /** Full CAT 1 load: 40 radar + 40 activated AIS + 200 sleeping AIS = 280 objects (240 AIS). */
    private fun fullLoad() =
        gen(TargetSource.RADAR_TT, 40, "r") +
            gen(TargetSource.AIS_ACTIVE, 40, "a") +
            gen(TargetSource.AIS_SLEEPING, 200, "s")

    @Test fun handles280Targets_allPlaced() {
        val scene = OverlayProjector.project(fullLoad(), own, proj, scaleNm)
        assertEquals(280, scene.drawnCount) // every target inside the ring is placed
        assertEquals(280, scene.symbols.size)
    }

    @Test fun capacityReportReflectsCat1Load() {
        val scene = OverlayProjector.project(fullLoad(), own, proj, scaleNm)
        assertEquals(40, scene.capacity.radarTracked.count)
        assertEquals(240, scene.capacity.aisTotal.count)
        assertTrue(scene.capacity.anyNearLimit()) // at the CAT 1 minimums -> caution band (3043)
    }

    @Test fun sleepingAisNeverGetVectors_activatedAndRadarDo() {
        // Per MSC.191/IEC 62288, only *activated* AIS (and radar TT) carry a vector; sleeping AIS never do.
        val scene = OverlayProjector.project(fullLoad(), own, proj, scaleNm)
        assertEquals(0, scene.vectors.count { it.id.startsWith("s") })
        assertTrue(scene.vectors.any { it.id.startsWith("r") })
        assertTrue(scene.vectors.any { it.id.startsWith("a") })
    }

    @Test fun sleepingAisTrailsDropped_inDenseScene() {
        // Trails recorded for one sleeping and one radar target; dense scene -> only the radar trail draws.
        val trail = listOf(Vec2(0.0, 3.0), Vec2(0.0, 4.0), Vec2(0.0, 5.0))
        val trails = mapOf("s0" to trail, "r0" to trail)
        val scene = OverlayProjector.project(fullLoad(), own, proj, scaleNm, OverlayConfig(), trails)
        assertEquals(0, scene.trails.count { it.id == "s0" }) // sleeping AIS trail dropped (LOD)
        assertTrue(scene.trails.any { it.id == "r0" })        // radar TT trail kept
    }

    @Test fun sparseScene_sleepingAisKeepsTrail() {
        // Below the detail threshold, a sleeping AIS still renders its past-position trail (full detail).
        val few = gen(TargetSource.AIS_SLEEPING, 3, "s")
        val trails = mapOf("s0" to listOf(Vec2(0.0, 3.0), Vec2(0.0, 4.0), Vec2(0.0, 5.0)))
        val scene = OverlayProjector.project(few, own, proj, scaleNm, OverlayConfig(sleepingDetailThreshold = 80), trails)
        assertTrue(scene.trails.any { it.id == "s0" })
    }
}
