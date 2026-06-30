package com.shipradar.uicore.target

import com.shipradar.contract.TargetSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Geo→relative AIS target construction (#4). */
class AisTargetBuilderTest {

    @Test
    fun `geoToRangeBearing computes range and true bearing`() {
        // 1 arc-minute of latitude north = 1 NM, true bearing 000.
        val (r, b) = Geometry.geoToRangeBearing(30.0, 122.0, 30.0 + 1.0 / 60.0, 122.0)!!
        assertTrue(abs(r - 1.0) < 1e-3, "range $r ≈ 1.0 NM")
        assertTrue(b < 0.5 || b > 359.5, "bearing $b ≈ 000")
    }

    @Test
    fun `geoToRangeBearing east of own ship reads ~090`() {
        val (_, b) = Geometry.geoToRangeBearing(0.0, 0.0, 0.0, 1.0 / 60.0)!! // due east at the equator
        assertTrue(abs(b - 90.0) < 0.5, "bearing $b ≈ 090")
    }

    @Test
    fun `build returns a georeferenced AIS target`() {
        val t = AisTargetBuilder.build(
            mmsi = 412345678, targetLat = 30.0 + 2.0 / 60.0, targetLon = 122.0,
            cogDeg = 180.0, sogKn = 8.0, ownLat = 30.0, ownLon = 122.0,
        )!!
        assertEquals("AIS-412345678", t.id)
        assertEquals(TargetSource.AIS_ACTIVE, t.source)
        assertTrue(t.trueBearing && abs(t.rangeNm - 2.0) < 1e-2, "≈2 NM due north")
        assertEquals(180.0, t.courseDeg)
        assertEquals(8.0, t.speedKn)
    }

    @Test
    fun `build returns null without own position`() {
        assertNull(AisTargetBuilder.build(1, 30.0, 122.0, null, null, ownLat = null, ownLon = null))
    }
}
