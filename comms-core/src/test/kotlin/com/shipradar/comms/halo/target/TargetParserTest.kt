package com.shipradar.comms.halo.target

import com.shipradar.contract.TrackCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the wire-boundary stubs and the track-control intent mapping. The wire decode itself is
 * an unresolved TODO (no documented byte format); these tests pin the *current* documented behavior
 * (stubs are inert, never crash) and the testable contract->SDK command lowering. 合规追溯: HALO.
 */
class TargetParserTest {

    @Test
    fun `parseTargets is inert until wire format known (stub)`() {
        // Documents the stub contract: no fabricated targets, and no crash on arbitrary/truncated input.
        assertTrue(TargetParser.parseTargets(ByteArray(0)).isEmpty())
        assertTrue(TargetParser.parseTargets(byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun `parseTrackStatus is inert until wire format known (stub)`() {
        assertNull(TargetParser.parseTrackStatus(ByteArray(0)))
        assertNull(TargetParser.parseTrackStatus(byteArrayOf(9, 9, 9, 9)))
    }

    @Test
    fun `acquire lowers to SDK intent with unit and bearing conversion`() {
        val cmd = TrackControlEncoder.toSdkCommand(
            TrackCommand.Acquire(rangeNm = 1.0, bearingDeg = 90.4),
            clientId = 5,
        ) as SdkTrackCommand.Acquire
        assertEquals(5L, cmd.id)
        assertEquals(1852L, cmd.rangeM)       // 1 nm
        assertEquals(90, cmd.bearingDeg)      // rounded to whole degrees
        assertEquals(SdkBearingType.ABSOLUTE, cmd.bearingType) // default
    }

    @Test
    fun `acquire bearing normalized into 0 to 359`() {
        val neg = TrackControlEncoder.toSdkCommand(
            TrackCommand.Acquire(rangeNm = 0.5, bearingDeg = -10.0),
        ) as SdkTrackCommand.Acquire
        assertEquals(350, neg.bearingDeg)

        val over = TrackControlEncoder.toSdkCommand(
            TrackCommand.Acquire(rangeNm = 0.5, bearingDeg = 370.0),
        ) as SdkTrackCommand.Acquire
        assertEquals(10, over.bearingDeg)
    }

    @Test
    fun `cancel lowers numeric target id to server id`() {
        val cmd = TrackControlEncoder.toSdkCommand(TrackCommand.Cancel("42")) as SdkTrackCommand.Cancel
        assertEquals(42L, cmd.serverId)
    }

    @Test
    fun `cancel with non-numeric id is unrepresentable`() {
        assertNull(TrackControlEncoder.toSdkCommand(TrackCommand.Cancel("not-a-number")))
    }

    @Test
    fun `encode wire serialization is an explicit TODO`() {
        assertFailsWith<NotImplementedError> {
            TrackControlEncoder.encode(SdkTrackCommand.CancelAll)
        }
    }
}
