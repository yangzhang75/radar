package com.shipradar.comms.sync

/**
 * Pure connection-liveness strategy for the inbound data channels.
 *
 * The Foreground Service owns the real sockets/threads; it drives this supervisor with two pure
 * events — [onPacket] when bytes arrive on a channel, and [onTick] on a timer — and then *executes*
 * the [LinkAction]s the supervisor returns (reconnect a socket, raise/clear the 3002 indication).
 * Keeping the decision here makes the whole interrupt/backoff policy unit-testable against simulated
 * time with no I/O.
 *
 * Per channel the policy is:
 *   • a channel with no packet for longer than its staleness timeout becomes [ChannelLink.LOST];
 *   • a lost channel schedules reconnect attempts on an exponential backoff (capped), each attempt
 *     surfaced as a [LinkAction.Reconnect];
 *   • a packet arriving on a lost channel restores it to [ChannelLink.LIVE] and resets its backoff.
 *
 * The aggregate "any safety-relevant channel lost" condition drives the **3002 communications-lost**
 * alarm ([LinkAction.RaiseCommsAlarm] / [LinkAction.ClearCommsAlarm]), which the Service maps to a
 * [com.shipradar.contract.AlarmEvent] with identifier 3002.
 *
 * Boundary with T1.1a (handshake/watchdog): T1.1a owns the HALO uplink watchdog (A1C1 every ~8 s) and
 * the handshake-level [com.shipradar.contract.LinkState]. This supervisor is *downlink* liveness across
 * all data channels (HALO + 61162-450 + serial) and is transport-agnostic. It does not send A1C1.
 * Where they overlap (HALO link up/down), the Service can feed T1.1a's verdict in as the ECHO/STATUS
 * channel liveness, or run them in parallel; see the delivery report for the agreed contract.
 *
 * All times are caller-supplied Longs (millis). No real clock, no threads, no randomness.
 */
class LinkSupervisor(
    private val configs: Map<DataChannel, ChannelConfig>,
    /** Channels whose loss should raise the 3002 communications-lost alarm. */
    private val commsAlarmChannels: Set<DataChannel> = setOf(DataChannel.ECHO, DataChannel.STATUS),
) {
    private class State(var lastPacket: Long? = null,
                        var link: ChannelLink = ChannelLink.INIT,
                        var attempt: Int = 0,
                        var nextReconnectAt: Long? = null)

    private val states: Map<DataChannel, State> = configs.keys.associateWith { State() }
    private var commsAlarmActive = false

    /** Record a packet arrival on [channel] at local time [now]. Returns any state-change actions. */
    fun onPacket(channel: DataChannel, now: Long): List<LinkAction> {
        val st = states[channel] ?: return emptyList()
        st.lastPacket = now
        val actions = mutableListOf<LinkAction>()
        if (st.link != ChannelLink.LIVE) {
            st.link = ChannelLink.LIVE
            st.attempt = 0
            st.nextReconnectAt = null
            actions += LinkAction.ChannelUp(channel)
        }
        actions += reconcileCommsAlarm(now)
        return actions
    }

    /** Advance simulated time to [now]. Returns staleness/reconnect/alarm actions due at this tick. */
    fun onTick(now: Long): List<LinkAction> {
        val actions = mutableListOf<LinkAction>()
        for ((channel, st) in states) {
            val cfg = configs.getValue(channel)
            val last = st.lastPacket
            val silentFor = if (last == null) null else now - last

            // Detect transition to LOST: either never connected past its initial grace, or went silent.
            val isStale = when {
                last != null -> silentFor!! > cfg.stalenessTimeoutMs
                else -> now >= cfg.initialGraceMs  // never received anything within the startup grace
            }

            if (isStale && st.link != ChannelLink.LOST) {
                st.link = ChannelLink.LOST
                st.attempt = 0
                st.nextReconnectAt = now + cfg.backoff.firstDelayMs
                actions += LinkAction.ChannelDown(channel)
            }

            if (st.link == ChannelLink.LOST) {
                val due = st.nextReconnectAt
                if (due != null && now >= due) {
                    actions += LinkAction.Reconnect(channel, st.attempt)
                    st.attempt++
                    st.nextReconnectAt = now + cfg.backoff.delayForAttempt(st.attempt)
                }
            }
        }
        actions += reconcileCommsAlarm(now)
        return actions
    }

    /** Current link state of a channel (for the data bar / DEGRADED rollup). */
    fun linkOf(channel: DataChannel): ChannelLink = states[channel]?.link ?: ChannelLink.INIT

    fun isCommsAlarmActive(): Boolean = commsAlarmActive

    private fun reconcileCommsAlarm(now: Long): List<LinkAction> {
        val anyLost = commsAlarmChannels.any { states[it]?.link == ChannelLink.LOST }
        return when {
            anyLost && !commsAlarmActive -> {
                commsAlarmActive = true
                listOf(LinkAction.RaiseCommsAlarm(lostChannels(), now))
            }
            !anyLost && commsAlarmActive -> {
                commsAlarmActive = false
                listOf(LinkAction.ClearCommsAlarm(now))
            }
            else -> emptyList()
        }
    }

    private fun lostChannels(): Set<DataChannel> =
        commsAlarmChannels.filterTo(LinkedHashSet()) { states[it]?.link == ChannelLink.LOST }
}

/** Per-channel liveness state. */
enum class ChannelLink {
    /** No packet seen yet and still inside the startup grace window. */
    INIT,
    /** Receiving packets within the staleness timeout. */
    LIVE,
    /** Silent past the staleness timeout — interrupted; reconnect attempts are scheduled. */
    LOST,
}

/**
 * Exponential backoff schedule for reconnect attempts. Deterministic (no jitter) so reconnect timing
 * is reproducible in tests and predictable on the low-bandwidth link.
 *
 * Attempt 0's delay is [firstDelayMs]; attempt n's delay is firstDelayMs * multiplier^n, capped at
 * [maxDelayMs]. `delayForAttempt(n)` is the wait *before* the (n+1)-th attempt.
 */
data class Backoff(
    val firstDelayMs: Long = 1_000,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 30_000,
) {
    init {
        require(firstDelayMs > 0) { "firstDelayMs must be > 0" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(maxDelayMs >= firstDelayMs) { "maxDelayMs must be >= firstDelayMs" }
    }

    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return firstDelayMs
        var d = firstDelayMs.toDouble()
        repeat(attempt) {
            d *= multiplier
            if (d >= maxDelayMs) return maxDelayMs
        }
        return d.toLong().coerceAtMost(maxDelayMs)
    }
}

/**
 * Per-channel timing config.
 * @param stalenessTimeoutMs silence longer than this marks the channel LOST. Tune per channel: echo
 *   spokes stream continuously so a short timeout is fine; status messages are periodic (~2 s) so it
 *   must exceed the message period plus VPN jitter.
 * @param initialGraceMs how long after startup to wait before declaring a never-seen channel LOST.
 */
data class ChannelConfig(
    val stalenessTimeoutMs: Long,
    val initialGraceMs: Long = stalenessTimeoutMs,
    val backoff: Backoff = Backoff(),
)

/** Side effects the supervisor asks the Service to perform. Pure data — the Service executes them. */
sealed interface LinkAction {
    data class ChannelUp(val channel: DataChannel) : LinkAction
    data class ChannelDown(val channel: DataChannel) : LinkAction
    /** Attempt to (re)establish [channel]; [attempt] is the 0-based attempt index for logging. */
    data class Reconnect(val channel: DataChannel, val attempt: Int) : LinkAction
    /** Raise BAM 3002 communications-lost; [channels] are the currently-lost contributors. */
    data class RaiseCommsAlarm(val channels: Set<DataChannel>, val atMillis: Long) : LinkAction
    data class ClearCommsAlarm(val atMillis: Long) : LinkAction
}
