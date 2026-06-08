package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Radar-TT / AIS association and de-duplication per IEC 62388 §11.8 / IMO MSC.192(79) §5.30.
 * Scenario shapes mirror IEC 62388 §11.8.2 (own ship stationary; targets at 4 NM, brg 340, COG 90, 10 kn).
 */
class FusionTest {

    private val own = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 0.0)

    private fun t(
        id: String, source: TargetSource, range: Double, bearing: Double,
        course: Double? = 90.0, speed: Double? = 10.0,
    ) = TrackedTarget(
        id = id, source = source, rangeNm = range, bearingDeg = bearing, trueBearing = true,
        courseDeg = course, speedKn = speed, status = TargetStatus.TRACKED,
    )

    @Test fun coincidentSameMotion_associates_aisPriorityDefault() {
        val targets = listOf(
            t("R1", TargetSource.RADAR_TT, 4.0, 340.0),
            t("A1", TargetSource.AIS_ACTIVE, 4.0, 340.0),
        )
        val r = TargetFusion.fuse(targets, own)
        assertEquals(1, r.associations.size)
        assertEquals(1, r.fused.size)
        assertEquals(TargetSource.AIS_ACTIVE, r.fused.single().source) // AIS symbol survives (§5.30.1)
        assertEquals("A1", r.fused.single().id)
    }

    @Test fun radarPriority_keepsRadar() {
        val targets = listOf(
            t("R1", TargetSource.RADAR_TT, 4.0, 340.0),
            t("A1", TargetSource.AIS_ACTIVE, 4.0, 340.0),
        )
        val r = TargetFusion.fuse(targets, own, priority = FusionPriority.RADAR_TT)
        assertEquals(1, r.fused.size)
        assertEquals(TargetSource.RADAR_TT, r.fused.single().source) // §5.30.2 operator override
    }

    @Test fun closeButDifferentMotion_doesNotAssociate() {
        // IEC §11.8.2.5 scenario 3: same position, reciprocal course -> two distinct targets.
        val targets = listOf(
            t("R1", TargetSource.RADAR_TT, 4.0, 340.0, course = 90.0),
            t("A1", TargetSource.AIS_ACTIVE, 4.0, 340.0, course = 270.0),
        )
        val r = TargetFusion.fuse(targets, own)
        assertTrue(r.associations.isEmpty())
        assertEquals(2, r.fused.size)
    }

    @Test fun separationBeyondGate_doesNotAssociate() {
        val targets = listOf(
            t("R1", TargetSource.RADAR_TT, 4.0, 340.0),
            t("A1", TargetSource.AIS_ACTIVE, 6.0, 340.0), // 2 NM away radially, gate 0.5 NM
        )
        val r = TargetFusion.fuse(targets, own)
        assertTrue(r.associations.isEmpty())
        assertEquals(2, r.fused.size)
    }

    @Test fun sleepingAis_associates() {
        val targets = listOf(
            t("R1", TargetSource.RADAR_TT, 4.0, 340.0),
            t("A1", TargetSource.AIS_SLEEPING, 4.0, 340.0),
        )
        val r = TargetFusion.fuse(targets, own)
        assertEquals(1, r.associations.size)
        assertEquals(TargetSource.AIS_SLEEPING, r.fused.single().source)
    }

    @Test fun eachAisUsedOnce_greedyNearest() {
        // Two radar TTs, one AIS coincident with R1; R2 is far. Only R1<->A1 should fuse.
        val targets = listOf(
            t("R1", TargetSource.RADAR_TT, 4.0, 340.0),
            t("R2", TargetSource.RADAR_TT, 8.0, 100.0),
            t("A1", TargetSource.AIS_ACTIVE, 4.0, 340.0),
        )
        val r = TargetFusion.fuse(targets, own)
        assertEquals(1, r.associations.size)
        assertEquals("R1", r.associations.single().radarId)
        // R2 (no match) and A1 (kept) survive -> 2 entries.
        assertEquals(setOf("R2", "A1"), r.fused.map { it.id }.toSet())
    }

    @Test fun radarTtMissingCourse_associatesOnPositionAlone() {
        // A radar TT still acquiring (no course yet) coincident with an AIS -> associate on position.
        val targets = listOf(
            t("R1", TargetSource.RADAR_TT, 4.0, 340.0, course = null, speed = null),
            t("A1", TargetSource.AIS_ACTIVE, 4.0, 340.0),
        )
        val r = TargetFusion.fuse(targets, own)
        assertEquals(1, r.associations.size)
    }
}
