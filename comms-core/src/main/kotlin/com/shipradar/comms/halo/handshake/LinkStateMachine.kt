package com.shipradar.comms.halo.handshake

import com.shipradar.contract.LinkState

/**
 * T1.1a — pure functional connection state machine for the HALO link.
 *
 * Flow: DISCONNECTED --(send 01B1)--> NEGOTIATING --(recv 01B2)--> CONNECTED
 *       CONNECTED --(watchdog timeout / loss)--> DEGRADED --(recovered)--> CONNECTED
 * Any state can be torn down to DISCONNECTED. The transition is total: an event with no rule for the
 * current state leaves the state unchanged (callers may treat that as "ignored"). No IO, no clock —
 * the Foreground Service (wave 2) supplies events from socket/handshake/watchdog activity.
 *
 * [LinkState] is the frozen contract enum (com.shipradar.contract).
 */
object LinkStateMachine {

    val INITIAL: LinkState = LinkState.DISCONNECTED

    fun transition(state: LinkState, event: LinkEvent): LinkState = when (event) {
        // Teardown / fatal: always returns to DISCONNECTED.
        LinkEvent.Disconnect -> LinkState.DISCONNECTED

        // We sent 01B1 — begin negotiating (only meaningful when not already connected).
        LinkEvent.RequestSent -> when (state) {
            LinkState.DISCONNECTED, LinkState.NEGOTIATING -> LinkState.NEGOTIATING
            else -> state
        }

        // 01B2 received — link established (also recovers a degraded link via re-handshake).
        LinkEvent.AllowReceived -> LinkState.CONNECTED

        // Negotiation gave up (no 01B2 within the retry window) — drop back to DISCONNECTED.
        LinkEvent.NegotiationTimeout -> when (state) {
            LinkState.NEGOTIATING -> LinkState.DISCONNECTED
            else -> state
        }

        // Watchdog overdue / packet loss while connected — degrade (not a full disconnect).
        LinkEvent.Degraded -> when (state) {
            LinkState.CONNECTED, LinkState.DEGRADED -> LinkState.DEGRADED
            else -> state
        }

        // Healthy traffic resumed — recover from DEGRADED.
        LinkEvent.Recovered -> when (state) {
            LinkState.DEGRADED -> LinkState.CONNECTED
            else -> state
        }

        // Watchdog lapse exceeded the power-off horizon (>1 min) — radar is off; link is dead.
        LinkEvent.Lost -> LinkState.DISCONNECTED
    }

    /** Convenience: fold a sequence of events from an initial state. */
    fun reduce(initial: LinkState, events: Iterable<LinkEvent>): LinkState =
        events.fold(initial, ::transition)
}

/** Inputs that drive the [LinkStateMachine]. */
sealed interface LinkEvent {
    /** 01B1 sent on the negotiation channel. */
    data object RequestSent : LinkEvent
    /** 01B2 parsed successfully. */
    data object AllowReceived : LinkEvent
    /** No 01B2 within the negotiation retry window. */
    data object NegotiationTimeout : LinkEvent
    /** Watchdog overdue (>~30s) or sustained packet loss while connected. */
    data object Degraded : LinkEvent
    /** Healthy traffic / watchdog cadence resumed. */
    data object Recovered : LinkEvent
    /** Watchdog lapse exceeded the power-off horizon (>~1 min); radar powered down. */
    data object Lost : LinkEvent
    /** Explicit teardown (user stop, socket closed). */
    data object Disconnect : LinkEvent
}
