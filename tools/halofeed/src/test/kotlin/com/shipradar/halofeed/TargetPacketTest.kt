package com.shipradar.halofeed

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetPacketTest {

    private val targets = listOf(
        TrackedTarget("T01", TargetSource.RADAR_TT, rangeNm = 2.5, bearingDeg = 47.5, trueBearing = false,
            courseDeg = 227.5, speedKn = 8.0, cpaNm = 0.5, tcpaSec = 320.0,
            status = TargetStatus.TRACKED, dangerous = true),
        TrackedTarget("T02", TargetSource.AIS_ACTIVE, rangeNm = 6.0, bearingDeg = 312.0, trueBearing = true,
            courseDeg = null, speedKn = null, cpaNm = null, tcpaSec = null,
            status = TargetStatus.ACQUIRING, dangerous = false),
    )

    @Test fun magicTaggedSoRealParserRejects() {
        val p = TargetPacket.build(targets)
        assertContentEquals("FAKETGT".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01), p.copyOfRange(0, 8))
    }

    @Test fun sizeMatchesLayout() {
        assertEquals(10 + 2 * TargetPacket.TARGET_BYTES, TargetPacket.build(targets).size)
    }

    @Test fun roundTripsTargets() {
        val back = TargetPacket.parse(TargetPacket.build(targets))
        assertEquals(2, back.size)

        assertEquals("T01", back[0].id)
        assertEquals(TargetSource.RADAR_TT, back[0].source)
        assertEquals(TargetStatus.TRACKED, back[0].status)
        assertEquals(2.5, back[0].rangeNm, 1e-3)
        assertEquals(47.5, back[0].bearingDeg, 1e-3)
        assertEquals(227.5, back[0].courseDeg!!, 1e-3)
        assertEquals(8.0, back[0].speedKn!!, 1e-2)
        assertEquals(0.5, back[0].cpaNm!!, 1e-3)
        assertEquals(320.0, back[0].tcpaSec!!, 1e-1)
        assertTrue(back[0].dangerous)
        assertEquals(false, back[0].trueBearing)
    }

    @Test fun nullKinematicsRoundTripAsNull() {
        val back = TargetPacket.parse(TargetPacket.build(targets))
        assertNull(back[1].courseDeg)
        assertNull(back[1].speedKn)
        assertNull(back[1].cpaNm)
        assertNull(back[1].tcpaSec)
        assertEquals(true, back[1].trueBearing)
    }

    @Test fun emptyListIsValid() {
        assertEquals(0, TargetPacket.parse(TargetPacket.build(emptyList())).size)
    }
}
