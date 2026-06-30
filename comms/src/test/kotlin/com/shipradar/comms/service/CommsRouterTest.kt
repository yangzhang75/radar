package com.shipradar.comms.service

import com.shipradar.comms.halo.handshake.LinkEvent
import com.shipradar.comms.iec450.Iec450Group
import com.shipradar.comms.sync.DataChannel
import com.shipradar.comms.sync.LinkAction
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmState
import com.shipradar.contract.LinkState
import com.shipradar.contract.TargetSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommsRouterTest {

    private val cfg = CommsConfig()

    @Test
    fun `link state starts disconnected and follows handshake events`() {
        val r = CommsRouter(cfg)
        assertEquals(LinkState.DISCONNECTED, r.linkState.value)
        r.applyLinkEvent(LinkEvent.RequestSent)
        assertEquals(LinkState.NEGOTIATING, r.linkState.value)
        r.applyLinkEvent(LinkEvent.AllowReceived)
        assertEquals(LinkState.CONNECTED, r.linkState.value)
    }

    @Test
    fun `comms alarm raise degrades link and emits 3002, clear recovers`() = runTest {
        val r = CommsRouter(cfg)
        val events = mutableListOf<AlarmEvent>()
        backgroundScope.launch { r.alarms.collect { events += it } }
        runCurrent()

        // Must be connected before a downlink loss can degrade it.
        r.applyLinkEvent(LinkEvent.RequestSent)
        r.applyLinkEvent(LinkEvent.AllowReceived)

        r.raiseCommsAlarm(setOf(DataChannel.ECHO, DataChannel.STATUS), atMillis = 1_000)
        runCurrent()
        assertEquals(LinkState.DEGRADED, r.linkState.value)
        assertEquals(1, events.size)
        assertEquals(3002, events[0].identifier)
        assertEquals(AlarmState.ACTIVE_UNACK, events[0].state)

        r.clearCommsAlarm(atMillis = 2_000)
        runCurrent()
        assertEquals(LinkState.CONNECTED, r.linkState.value)
        assertEquals(2, events.size)
        assertEquals(AlarmState.NORMAL, events[1].state)
    }

    @Test
    fun `tick with no packets marks echo and status lost and raises comms alarm`() {
        val r = CommsRouter(cfg) // ECHO grace 10s, STATUS grace 12s; both in comms-alarm set
        assertTrue(r.onTick(5_000).none { it is LinkAction.RaiseCommsAlarm }, "too early to alarm")
        val actions = r.onTick(12_000)
        assertTrue(actions.any { it is LinkAction.ChannelDown && it.channel == DataChannel.ECHO })
        assertTrue(
            actions.any { it is LinkAction.RaiseCommsAlarm },
            "ECHO+STATUS silent past grace must raise 3002: $actions",
        )
    }

    @Test
    fun `inbound ACN command acknowledges an alarm through the BAM state machine`() = runTest {
        val r = CommsRouter(cfg)
        val events = mutableListOf<AlarmEvent>()
        backgroundScope.launch { r.alarms.collect { events += it } }
        runCurrent()

        // Raise alert 3044 (CPA/TCPA) via an ALR sentence in a sourced 61162-450 datagram.
        r.on450(Iec450Group.BAM1, frame450("RAALR,160012,3044,A,V,CPA danger"), now = 1_000)
        runCurrent()
        assertEquals(AlarmState.ACTIVE_UNACK, events.last { it.identifier == 3044 }.state, "ALR should raise 3044 unacknowledged")

        // Inbound ACN (acknowledge) must drive the state machine to ACTIVE_ACK and re-emit on the bus.
        r.on450(Iec450Group.BAM1, frame450("RAACN,123519,RA,3044,1,A,C"), now = 2_000)
        runCurrent()
        assertEquals(AlarmState.ACTIVE_ACK, events.last { it.identifier == 3044 }.state, "inbound ACN must acknowledge 3044")
    }

    /** Wrap a 61162-1 sentence BODY (no `$`/`*hh`) in a sourced (`s:`) UdPbC 61162-450 datagram. */
    private fun frame450(body: String): ByteArray {
        fun xor(s: String) = s.fold(0) { a, c -> a xor c.code } and 0xFF
        val src = "s:RA0001" // conformant 61162-450 source id (talker + 4 digits)
        val line = "\\$src*${"%02X".format(xor(src))}\\" + "\$$body*${"%02X".format(xor(body))}" + "\r\n"
        return "UdPbC".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + line.toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `published targets are enriched with CPA-TCPA and a head-on closing target is flagged dangerous`() {
        val r = CommsRouter(cfg)
        // own ship steaming due north at 10 kn
        r.on450(Iec450Group.NAVD, frame450("GPRMC,123519,A,3017.76,N,12210.08,E,10.0,000.0,300625,,"), now = 1_000)
        // a tracked target 1 NM dead ahead (true bearing 000) closing head-on (course 180, 10 kn);
        // the TTM carries NO CPA/TCPA field — the router must compute them from the geometry.
        r.on450(Iec450Group.TGTD, frame450("RATTM,01,1.0,000.0,T,10.0,180.0,T,,,N,,T,,"), now = 2_000)

        val t = r.targets.value.firstOrNull { it.source == TargetSource.RADAR_TT }
        assertNotNull(t, "radar TT must be published")
        assertNotNull(t.cpaNm, "router must enrich the target with a computed CPA (absent in the sentence)")
        assertTrue(t.dangerous, "a 1 NM head-on closing target must be classified dangerous (drives 3044)")
    }

    /** Minimal HALO image datagram: 8-byte preamble + one 24-byte spoke header + 4 zero samples. */
    private fun imagePacket(rangeCellSize: Int, cellsDiv2: Int): ByteArray {
        fun le(v: Long) = ByteArray(4) { ((v ushr (it * 8)) and 0xFF).toByte() }
        val pre = byteArrayOf(0x01, 0, 0, 0, 0, 0x20, 0, 0x02)
        val n = 4
        val data = ByteArray(n / 2) // 4 samples, 4-bit packed → 2 bytes, all zero
        val w0 = (24 + data.size).toLong() or (1L shl 16)                 // spokeLength | seq=1
        val w1 = n.toLong() or (4L shl 12) or (rangeCellSize.toLong() shl 16) // nSamples | 4bit | rangeCellSize
        val w3 = cellsDiv2.toLong()
        return pre + le(w0) + le(w1) + le(0) + le(w3) + le(0) + le(0) + data
    }

    @Test
    fun `live range-unit config scales echo full range (mm default vs dm)`() = runTest {
        // default mm: rangeCellSize 128 → tiny full range (~78 m)
        val rMm = CommsRouter(cfg)
        val mm = mutableListOf<com.shipradar.contract.EchoSpoke>()
        backgroundScope.launch { rMm.echoSpokes.collect { mm += it } }
        runCurrent()
        rMm.onHaloImage(imagePacket(rangeCellSize = 128, cellsDiv2 = 304), now = 1_000)
        runCurrent()
        assertTrue(mm.isNotEmpty() && mm.first().rangeMetersFull < 100.0, "mm: ${mm.firstOrNull()?.rangeMetersFull}")

        // dm config: ×100 → realistic ~7.8 km full range
        val rDm = CommsRouter(cfg.copy(rangeUnitToMm = com.shipradar.comms.halo.image.SpokeParser.RANGE_UNIT_DM))
        val dm = mutableListOf<com.shipradar.contract.EchoSpoke>()
        backgroundScope.launch { rDm.echoSpokes.collect { dm += it } }
        runCurrent()
        rDm.onHaloImage(imagePacket(rangeCellSize = 128, cellsDiv2 = 304), now = 1_000)
        runCurrent()
        assertTrue(dm.isNotEmpty() && dm.first().rangeMetersFull in 7000.0..8500.0, "dm: ${dm.firstOrNull()?.rangeMetersFull}")
    }

    @Test
    fun `a newly tracked radar target raises a 3048 new-target alarm`() = runTest {
        val r = CommsRouter(cfg)
        val events = mutableListOf<AlarmEvent>()
        backgroundScope.launch { r.alarms.collect { events += it } }
        runCurrent()
        // a TTM tracked target (status T) → confirmed radar-TT appears → 3048 new-target.
        r.on450(Iec450Group.TGTD, frame450("RATTM,01,1.0,000.0,T,10.0,180.0,T,,,N,,T,,"), now = 1_000)
        runCurrent()
        assertTrue(events.any { it.identifier == 3048 }, "expected a 3048 new-target alarm, got $events")
    }

    @Test
    fun `exceeding CAT1 tracked-target capacity raises a 3042 alarm`() = runTest {
        val r = CommsRouter(cfg)
        val events = mutableListOf<AlarmEvent>()
        backgroundScope.launch { r.alarms.collect { events += it } }
        runCurrent()
        // CAT 1 radar-TT minimum capacity is 40; feed 41 distinct tracked targets → over-limit (3042).
        for (n in 1..41) {
            r.on450(Iec450Group.TGTD, frame450("RATTM,$n,1.0,090.0,T,5.0,270.0,T,,,N,,T,,"), now = 1_000L + n)
            runCurrent()
        }
        assertTrue(events.any { it.identifier == 3042 }, "expected 3042 capacity-exceeded, got ${events.map { it.identifier }.distinct()}")
    }

    @Test
    fun `tick schedules reconnect for a lost channel`() {
        val r = CommsRouter(cfg)
        r.onTick(12_000) // ECHO/STATUS go LOST, first reconnect scheduled ~1s later
        val later = r.onTick(13_500)
        assertTrue(
            later.any { it is LinkAction.Reconnect && it.channel == DataChannel.ECHO },
            "expected an ECHO reconnect action: $later",
        )
    }
}
