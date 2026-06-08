package com.shipradar.comms.halo.target

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mapping tests driven by constructed [SdkTrackedTarget] intermediates (NOT raw wire bytes — the wire
 * format is an unresolved TODO). These prove the SDK->contract conversion is correct so that, once a
 * real capture pins the byte layout, only the decoder needs to be written. 合规追溯: HALO.
 */
class TargetMapperTest {

    private fun info(distanceM: Long, bearingDdeg: Long, courseDdeg: Long = 0, speedDmps: Long = 0) =
        SdkTargetInfo(distanceM, bearingDdeg, courseDdeg, speedDmps)

    private fun target(
        valid: Int = 1,
        source: SdkTargetSource = SdkTargetSource.REAL,
        targetId: Long = 7,
        serverId: Int = 42,
        state: SdkTargetState = SdkTargetState.SAFE,
        acquisitionMask: Long = 0,
        relative: SdkTargetInfo = info(1852, 900, 1800, 100),
        absolute: SdkTargetInfo = info(1852, 450, 1800, 100),
        absoluteValid: Int = 0,
        cpaM: Int = 1852,
        tcpaS: Int = 120,
        towardsCpa: Int = 1,
    ) = SdkTrackedTarget(
        targetValid = valid,
        targetSource = source,
        targetType = SdkTargetType.VESSEL,
        targetId = targetId,
        serverTargetId = serverId,
        targetState = state,
        acquisitionMask = acquisitionMask,
        infoRelative = relative,
        infoAbsolute = absolute,
        infoAbsoluteValid = absoluteValid,
        cpaM = cpaM,
        tcpaS = tcpaS,
        towardsCpa = towardsCpa,
    )

    @Test
    fun `unit conversions metres-to-nm, ddeg-to-deg, dmps-to-knots`() {
        // distance 1852 m = 1 nm; bearing 900 ddeg = 90 deg; course 1800 ddeg = 180 deg;
        // speed 100 dmps = 10 m/s = 19.4384... kn; cpa 1852 m = 1 nm; tcpa 120 s.
        val t = target().toContract()!!
        assertEquals(1.0, t.rangeNm, 1e-9)
        assertEquals(90.0, t.bearingDeg, 1e-9)
        assertEquals(180.0, t.courseDeg!!, 1e-9)
        assertEquals(10.0 * (3600.0 / 1852.0), t.speedKn!!, 1e-9)
        assertEquals(1.0, t.cpaNm!!, 1e-9)
        assertEquals(120.0, t.tcpaSec!!, 1e-9)
        assertEquals(TargetSource.RADAR_TT, t.source)
    }

    @Test
    fun `relative info used and trueBearing false when absolute invalid`() {
        val t = target(absoluteValid = 0).toContract()!!
        assertFalse(t.trueBearing)
        assertEquals(90.0, t.bearingDeg, 1e-9) // relative bearing 900 ddeg
    }

    @Test
    fun `absolute info used and trueBearing true when absolute valid`() {
        val t = target(absoluteValid = 1).toContract()!!
        assertTrue(t.trueBearing)
        assertEquals(45.0, t.bearingDeg, 1e-9) // absolute bearing 450 ddeg
    }

    @Test
    fun `id prefers server id, falls back to client id when server invalid`() {
        assertEquals("42", target(serverId = 42, targetId = 7).toContract()!!.id)
        assertEquals("7", target(serverId = -1, targetId = 7).toContract()!!.id)
    }

    @Test
    fun `dangerous when state is dangerous`() {
        val t = target(state = SdkTargetState.DANGEROUS).toContract()!!
        assertTrue(t.dangerous)
        assertEquals(TargetStatus.TRACKED, t.status)
    }

    @Test
    fun `dangerous when acquisition mask MSB set`() {
        val t = target(
            state = SdkTargetState.SAFE,
            acquisitionMask = SdkTrackedTarget.ACQUISITION_MASK_DANGEROUS,
        ).toContract()!!
        assertTrue(t.dangerous)
    }

    @Test
    fun `not dangerous for plain safe target`() {
        assertFalse(target(state = SdkTargetState.SAFE).toContract()!!.dangerous)
    }

    @Test
    fun `state mapping`() {
        assertEquals(TargetStatus.ACQUIRING, SdkTargetState.ACQUIRING.toContractStatusOrNull())
        assertEquals(TargetStatus.TRACKED, SdkTargetState.SAFE.toContractStatusOrNull())
        assertEquals(TargetStatus.TRACKED, SdkTargetState.DANGEROUS.toContractStatusOrNull())
        assertEquals(TargetStatus.LOST, SdkTargetState.LOST.toContractStatusOrNull())
        assertEquals(TargetStatus.LOST, SdkTargetState.OUT_OF_RANGE.toContractStatusOrNull())
        assertEquals(TargetStatus.LOST, SdkTargetState.LOST_OUT_OF_RANGE.toContractStatusOrNull())
        // failure / sentinel states -> not plottable
        assertNull(SdkTargetState.ACQUIRE_FAILURE.toContractStatusOrNull())
        assertNull(SdkTargetState.FAIL_ACQUIRE_MAX.toContractStatusOrNull())
        assertNull(SdkTargetState.FAIL_ACQUIRE_POS.toContractStatusOrNull())
        assertNull(SdkTargetState.BAD_STATE.toContractStatusOrNull())
    }

    @Test
    fun `invalid record dropped`() {
        assertNull(target(valid = 0).toContract())
    }

    @Test
    fun `failure state record dropped`() {
        assertNull(target(state = SdkTargetState.FAIL_ACQUIRE_POS, serverId = -1).toContract())
    }

    @Test
    fun `lat lon null until own-ship fusion`() {
        val t = target().toContract()!!
        assertNull(t.latitude)
        assertNull(t.longitude)
    }

    @Test
    fun `batch mapping drops invalid and keeps valid`() {
        val out = mapTrackedTargets(
            listOf(
                target(serverId = 1, state = SdkTargetState.SAFE),
                target(valid = 0),                                   // dropped: invalid
                target(serverId = 2, state = SdkTargetState.BAD_STATE), // dropped: sentinel
                target(serverId = 3, state = SdkTargetState.ACQUIRING),
            )
        )
        assertEquals(listOf("1", "3"), out.map { it.id })
    }

    @Test
    fun `negative tcpa preserved (cpa already passed)`() {
        assertEquals(-30.0, target(tcpaS = -30).toContract()!!.tcpaSec!!, 1e-9)
    }

    @Test
    fun `enum fromRaw round-trips and rejects unknown`() {
        assertEquals(SdkTargetState.FAIL_ACQUIRE_POS, SdkTargetState.fromRaw(0x11))
        assertEquals(SdkTargetState.BAD_STATE, SdkTargetState.fromRaw(0xBAD15BADL))
        assertNull(SdkTargetState.fromRaw(0x99))
        assertEquals(SdkTargetSource.REAL, SdkTargetSource.fromRaw(1))
        assertNull(SdkTargetSource.fromRaw(9))
        assertEquals(SdkHeadingType.TRUE, SdkHeadingType.fromRaw(2))
    }
}
