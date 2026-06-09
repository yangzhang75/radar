package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Past-position accumulation (A.823 §3.3.5 / IEC 62288 def 3.33). */
class TargetTrailStoreTest {

    private val own = OwnShipData(headingDeg = 0.0)

    private fun t(id: String, range: Double, bearing: Double = 0.0) = TrackedTarget(
        id = id, source = TargetSource.RADAR_TT, rangeNm = range, bearingDeg = bearing, trueBearing = true,
        status = TargetStatus.TRACKED,
    )

    @Test fun accumulatesOldestToNewest() {
        val store = TargetTrailStore(maxPoints = 4)
        store.record(listOf(t("a", 6.0)), own)
        store.record(listOf(t("a", 5.0)), own)
        store.record(listOf(t("a", 4.0)), own)
        val trail = store.trailOf("a")
        assertEquals(3, trail.size)
        // dead ahead -> NE (0, range); oldest first.
        assertEquals(6.0, trail.first().y, 1e-9)
        assertEquals(4.0, trail.last().y, 1e-9)
    }

    @Test fun capsAtMaxPoints() {
        val store = TargetTrailStore(maxPoints = 4)
        repeat(10) { store.record(listOf(t("a", (10 - it).toDouble())), own) }
        assertEquals(4, store.trailOf("a").size)
        assertEquals(1.0, store.trailOf("a").last().y, 1e-9) // newest = last recorded (range 1)
    }

    @Test fun forgetsDisappearedTargets() {
        val store = TargetTrailStore()
        store.record(listOf(t("a", 5.0), t("b", 5.0)), own)
        store.record(listOf(t("a", 4.0)), own) // b gone
        assertTrue(store.trailOf("a").isNotEmpty())
        assertTrue(store.trailOf("b").isEmpty())
        assertFalse(store.snapshot().containsKey("b"))
    }

    @Test fun snapshotCoversAllPresent() {
        val store = TargetTrailStore()
        store.record(listOf(t("a", 5.0), t("b", 5.0)), own)
        assertEquals(setOf("a", "b"), store.snapshot().keys)
    }
}
