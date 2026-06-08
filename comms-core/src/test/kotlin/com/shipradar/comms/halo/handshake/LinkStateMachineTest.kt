package com.shipradar.comms.halo.handshake

import com.shipradar.contract.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkStateMachineTest {

    private fun t(state: LinkState, event: LinkEvent) = LinkStateMachine.transition(state, event)

    @Test
    fun `initial state is disconnected`() {
        assertEquals(LinkState.DISCONNECTED, LinkStateMachine.INITIAL)
    }

    @Test
    fun `happy path disconnected to connected`() {
        val s1 = t(LinkState.DISCONNECTED, LinkEvent.RequestSent)
        assertEquals(LinkState.NEGOTIATING, s1)
        val s2 = t(s1, LinkEvent.AllowReceived)
        assertEquals(LinkState.CONNECTED, s2)
    }

    @Test
    fun `reduce folds the full happy path`() {
        val end = LinkStateMachine.reduce(
            LinkState.DISCONNECTED,
            listOf(LinkEvent.RequestSent, LinkEvent.AllowReceived),
        )
        assertEquals(LinkState.CONNECTED, end)
    }

    @Test
    fun `negotiation timeout returns to disconnected`() {
        assertEquals(LinkState.DISCONNECTED, t(LinkState.NEGOTIATING, LinkEvent.NegotiationTimeout))
    }

    @Test
    fun `negotiation timeout ignored when not negotiating`() {
        assertEquals(LinkState.CONNECTED, t(LinkState.CONNECTED, LinkEvent.NegotiationTimeout))
    }

    @Test
    fun `connected degrades on watchdog timeout then recovers`() {
        val degraded = t(LinkState.CONNECTED, LinkEvent.Degraded)
        assertEquals(LinkState.DEGRADED, degraded)
        assertEquals(LinkState.CONNECTED, t(degraded, LinkEvent.Recovered))
    }

    @Test
    fun `degraded re-handshake via allow received reconnects`() {
        assertEquals(LinkState.CONNECTED, t(LinkState.DEGRADED, LinkEvent.AllowReceived))
    }

    @Test
    fun `degraded stays degraded on repeated degrade`() {
        assertEquals(LinkState.DEGRADED, t(LinkState.DEGRADED, LinkEvent.Degraded))
    }

    @Test
    fun `lost from degraded drops the link`() {
        assertEquals(LinkState.DISCONNECTED, t(LinkState.DEGRADED, LinkEvent.Lost))
    }

    @Test
    fun `degrade ignored before connection`() {
        assertEquals(LinkState.DISCONNECTED, t(LinkState.DISCONNECTED, LinkEvent.Degraded))
        assertEquals(LinkState.NEGOTIATING, t(LinkState.NEGOTIATING, LinkEvent.Degraded))
    }

    @Test
    fun `recovered ignored unless degraded`() {
        assertEquals(LinkState.CONNECTED, t(LinkState.CONNECTED, LinkEvent.Recovered))
        assertEquals(LinkState.NEGOTIATING, t(LinkState.NEGOTIATING, LinkEvent.Recovered))
    }

    @Test
    fun `disconnect is total from every state`() {
        for (s in LinkState.entries) {
            assertEquals(LinkState.DISCONNECTED, t(s, LinkEvent.Disconnect), "from $s")
        }
    }

    @Test
    fun `request sent ignored when already connected`() {
        assertEquals(LinkState.CONNECTED, t(LinkState.CONNECTED, LinkEvent.RequestSent))
        assertEquals(LinkState.DEGRADED, t(LinkState.DEGRADED, LinkEvent.RequestSent))
    }

    @Test
    fun `degrade then lost then reconnect`() {
        val end = LinkStateMachine.reduce(
            LinkState.CONNECTED,
            listOf(
                LinkEvent.Degraded, LinkEvent.Lost,        // -> DEGRADED -> DISCONNECTED
                LinkEvent.RequestSent, LinkEvent.AllowReceived, // -> NEGOTIATING -> CONNECTED
            ),
        )
        assertEquals(LinkState.CONNECTED, end)
    }
}
