package com.shipradar.app.input

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.ppi.PpiProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EBL/VRM snap-to-nearest-target measurement aid (#3). */
class TargetSnapTest {

    private fun tgt(id: String, brgTrue: Double, rangeNm: Double, trueBearing: Boolean = true) = TrackedTarget(
        id = id, source = TargetSource.RADAR_TT, rangeNm = rangeNm, bearingDeg = brgTrue,
        trueBearing = trueBearing, status = TargetStatus.TRACKED,
    )

    private val targets = listOf(tgt("T1", 90.0, 3.0), tgt("T2", 270.0, 5.0))

    @Test
    fun `nearestTo returns the closest target within the gate`() {
        val hit = TargetSnap.nearestTo(targets, refTrueBearingDeg = 92.0, refRangeNm = 3.1, gateNm = 1.0)
        assertEquals("T1", hit?.id)
    }

    @Test
    fun `nearestTo returns null when nothing is within the gate`() {
        assertNull(TargetSnap.nearestTo(targets, refTrueBearingDeg = 180.0, refRangeNm = 3.0, gateNm = 0.5))
    }

    @Test
    fun `nearestTo ignores relative-bearing targets`() {
        val rel = listOf(tgt("R1", 90.0, 3.0, trueBearing = false))
        assertNull(TargetSnap.nearestTo(rel, 90.0, 3.0, gateNm = 1.0))
    }

    @Test
    fun `controller snaps EBL and VRM onto the nearest target`() {
        // EBL#0 relative bearing 88, VRM#0 range 3, own heading 0 → ref true ≈ (88, 3 NM), near T1 (90, 3).
        val model = InteractionModel(
            ebls = listOf(Ebl(enabled = true, bearingDeg = 88.0, reference = BearingReference.RELATIVE), Ebl()),
            vrms = listOf(Vrm(enabled = true, rangeNm = 3.0), Vrm()),
        )
        val ctx = InteractionContext(
            projection = PpiProjection(0.0, 0.0, 100.0, 0.0),
            rangeScaleNm = 6.0,
            ownHeadingDeg = 0.0,
            targets = targets,
        )
        val out = RadarInteractionController.snapEblVrm(model, ctx, InputClass.KEYBOARD, gateNm = 1.0)
        assertEquals(90.0, out.model.ebls[0].bearingDeg, 1e-6) // relative ref, heading 0 → 90
        assertEquals(3.0, out.model.vrms[0].rangeNm, 1e-6)
        assertTrue(out.model.ebls[0].enabled && out.model.vrms[0].enabled)
    }

    @Test
    fun `controller snap is a no-op when no target is near`() {
        val model = InteractionModel(
            ebls = listOf(Ebl(enabled = true, bearingDeg = 10.0, reference = BearingReference.TRUE), Ebl()),
            vrms = listOf(Vrm(enabled = true, rangeNm = 8.0), Vrm()),
        )
        val ctx = InteractionContext(
            projection = PpiProjection(0.0, 0.0, 100.0, 0.0),
            rangeScaleNm = 12.0, ownHeadingDeg = 0.0, targets = targets,
        )
        val out = RadarInteractionController.snapEblVrm(model, ctx, InputClass.KEYBOARD, gateNm = 1.0)
        assertEquals(model, out.model, "no target near the EBL/VRM → model unchanged")
    }
}
