package com.shipradar.comms.service

import com.shipradar.comms.halo.handshake.LinkEvent
import com.shipradar.comms.sync.DataChannel
import com.shipradar.comms.sync.LinkAction
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmState
import com.shipradar.contract.LinkState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
