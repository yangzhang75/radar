package com.shipradar.comms.service

import com.shipradar.comms.halo.control.HaloControlEncoder
import com.shipradar.comms.halo.handshake.HaloHandshake
import com.shipradar.constants.Endpoint
import com.shipradar.constants.HaloEndpoints
import com.shipradar.contract.LinkState
import com.shipradar.contract.RadarCommand
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadarCommsEngineTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Documented §握手协议 允许链接 payload, prefixed with the on-wire 01B2 opcode. */
    private val sample01b2 = hex("01B2") + hex(
        "31333039333032353135000000000000" + "C0A80064" + "01010600" +
            "FDFF20010200" + "10000000C0A80064" + "176011000000EC0607161A26" +
            "1F0020010200" + "10000000EC0607171A1C" + "11000000EC0607181A1D" +
            "100020010300" + "10000000EC0607081A16" + "11000000EC06070A1A18" + "12000000EC0607091A17" +
            "100020020300" + "10000000EC06070D1A01" + "11000000EC06070E1A02" + "12000000EC06070F1A03" +
            "120020010300" + "10000000EC0607121A20" + "11000000EC0607141A22" + "12000000EC0607131A21" +
            "120020020300" + "10000000EC06070C1A04" + "11000000EC06070D1A05" + "12000000EC06070E1A06",
    )

    private val imageEp = Endpoint("236.6.7.8", 6678)
    private val statusEp = Endpoint("236.6.7.9", 6679)
    private val targetEp = Endpoint("236.6.7.18", 6688)
    private val controlEp = Endpoint("236.6.7.10", 6680)

    @Test
    fun `handshake connects, sends 01B1, binds negotiated endpoints`() = runTest {
        val fake = FakeMulticastTransport()
        fake.emit(HaloHandshake.NEGOTIATION_ENDPOINT, sample01b2)
        val engine = RadarCommsEngine(fake, CommsConfig(), backgroundScope) { testScheduler.currentTime }

        engine.start()
        runCurrent()

        assertEquals(LinkState.CONNECTED, engine.linkState.value)
        assertEquals(1, fake.lockAcquireCount)
        // 01B1 went to the negotiation channel.
        assertTrue(
            fake.sentTo(HaloHandshake.NEGOTIATION_ENDPOINT).any { it.contentEquals(HaloHandshake.buildLinkRequest()) },
            "01B1 request not sent",
        )
        // Bound the negotiated data endpoints from the 01B2 reply.
        assertTrue(imageEp in fake.subscribed, "image channel not joined")
        assertTrue(statusEp in fake.subscribed, "status channel not joined")
        assertTrue(targetEp in fake.subscribed, "target channel not joined")
    }

    @Test
    fun `watchdog is sent to the control endpoint on cadence`() = runTest {
        val fake = FakeMulticastTransport()
        fake.emit(HaloHandshake.NEGOTIATION_ENDPOINT, sample01b2)
        val engine = RadarCommsEngine(fake, CommsConfig(), backgroundScope) { testScheduler.currentTime }

        engine.start()
        runCurrent()

        val watchdogBytes = HaloControlEncoder.encode(RadarCommand.Watchdog)
        fun watchdogCount() = fake.sentTo(controlEp).count { it.contentEquals(watchdogBytes) }

        val first = watchdogCount()
        assertTrue(first >= 1, "expected at least one watchdog right after connect, got $first")

        advanceTimeBy(25_000) // ~3 more 8 s periods
        runCurrent()
        assertTrue(watchdogCount() > first, "watchdog cadence did not continue: ${watchdogCount()} <= $first")
    }

    @Test
    fun `manual fallback after handshake timeout still connects with default endpoints`() = runTest {
        val fake = FakeMulticastTransport() // no 01B2 emitted -> handshake times out
        val config = CommsConfig(manualRadarIp = "10.0.0.5", handshakeTimeoutMs = 5_000)
        val engine = RadarCommsEngine(fake, config, backgroundScope) { testScheduler.currentTime }

        engine.start()
        advanceTimeBy(5_001) // let the handshake timeout fire
        runCurrent()

        assertEquals(LinkState.CONNECTED, engine.linkState.value)
        // Falls back to the default single-radar HALO endpoints.
        assertTrue(HaloEndpoints.IMAGE in fake.subscribed, "manual fallback did not bind default image endpoint")
    }

    @Test
    fun `skipHandshake connects immediately without sending 01B1`() = runTest {
        val fake = FakeMulticastTransport()
        val config = CommsConfig(manualRadarIp = "10.0.0.5", skipHandshake = true)
        val engine = RadarCommsEngine(fake, config, backgroundScope) { testScheduler.currentTime }

        engine.start()
        runCurrent()

        assertEquals(LinkState.CONNECTED, engine.linkState.value)
        assertTrue(fake.sentTo(HaloHandshake.NEGOTIATION_ENDPOINT).isEmpty(), "01B1 must not be sent when skipping")
        assertTrue(HaloEndpoints.IMAGE in fake.subscribed)
    }

    @Test
    fun `controller send encodes the command to the negotiated control endpoint`() = runTest {
        val fake = FakeMulticastTransport()
        fake.emit(HaloHandshake.NEGOTIATION_ENDPOINT, sample01b2)
        val engine = RadarCommsEngine(fake, CommsConfig(), backgroundScope) { testScheduler.currentTime }
        engine.start()
        runCurrent()

        engine.send(RadarCommand.Power(on = true))
        runCurrent()

        val expected = HaloControlEncoder.encode(RadarCommand.Power(on = true))
        assertTrue(
            fake.sentTo(controlEp).any { it.contentEquals(expected) },
            "Power command not encoded to the control endpoint",
        )
    }
}
