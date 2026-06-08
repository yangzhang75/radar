package com.shipradar.comms.halo.handshake

import com.shipradar.constants.HALO_WATCHDOG_PERIOD_MS

/**
 * T1.1a — pure HALO watchdog scheduling policy. No threads, no clock: the Foreground Service
 * (wave 2) owns the timer/coroutine and calls these functions with injected timestamps, recording
 * `lastSentAt` whenever it actually sends the watchdog command.
 *
 * Cadence + thresholds per §雷达控制 看门狗（A1C1）: send every ~8s; if the radar receives nothing for
 * >~30s it drops to standby; >~1min it powers off. (§消息发送规则 conflictingly lists A0C1 every 3s —
 * see report / [[halo-protocol-doc-ambiguities]]. We follow the §雷达控制 A1C1 + 8s convention,
 * matching HaloOpcodes.WATCHDOG and HALO_WATCHDOG_PERIOD_MS.)
 *
 * Boundary: this decides *when* to send. Encoding the A1C1 bytes is T1.3
 * (HaloControlEncoder.encode(RadarCommand.Watchdog)); the actual multicast send is the Service.
 */
class WatchdogPolicy(
    /** Send period, ms. Default = shared HALO_WATCHDOG_PERIOD_MS (~8s). */
    val periodMs: Long = HALO_WATCHDOG_PERIOD_MS,
    /** Radar drops to standby after this much silence (~30s). */
    val standbyAfterMs: Long = 30_000L,
    /** Radar powers off after this much silence (~1 min). */
    val powerOffAfterMs: Long = 60_000L,
) {
    init {
        require(periodMs > 0) { "watchdog period must be > 0" }
        require(standbyAfterMs in (periodMs + 1)..(powerOffAfterMs - 1)) {
            "thresholds must satisfy period < standby < powerOff (period=$periodMs, " +
                "standby=$standbyAfterMs, powerOff=$powerOffAfterMs)"
        }
    }

    /** Absolute time the next watchdog is due, given the last send. */
    fun nextDueAt(lastSentAt: Long): Long = lastSentAt + periodMs

    /** True if a watchdog should be sent now (period elapsed since [lastSentAt]). */
    fun isDue(lastSentAt: Long, now: Long): Boolean = now >= nextDueAt(lastSentAt)

    /** Milliseconds until the next send is due; 0 if already due (never negative). */
    fun millisUntilDue(lastSentAt: Long, now: Long): Long =
        (nextDueAt(lastSentAt) - now).coerceAtLeast(0L)

    /**
     * Classify the radar's likely reaction to our send cadence, given silence since [lastSentAt].
     * OK below the standby threshold, STANDBY once the radar would idle, POWER_OFF past the off
     * horizon. Drives [LinkEvent.Degraded] (>= standby) / [LinkEvent.Lost] (>= powerOff).
     */
    fun health(lastSentAt: Long, now: Long): WatchdogHealth {
        val silent = now - lastSentAt
        return when {
            silent >= powerOffAfterMs -> WatchdogHealth.POWER_OFF
            silent >= standbyAfterMs -> WatchdogHealth.STANDBY
            else -> WatchdogHealth.OK
        }
    }

    /** Map a [health] reading to the state-machine event it should raise (null = no transition). */
    fun eventFor(health: WatchdogHealth): LinkEvent? = when (health) {
        WatchdogHealth.OK -> null
        WatchdogHealth.STANDBY -> LinkEvent.Degraded
        WatchdogHealth.POWER_OFF -> LinkEvent.Lost
    }
}

/** Radar's expected reaction to watchdog silence (see §雷达控制 看门狗). */
enum class WatchdogHealth { OK, STANDBY, POWER_OFF }
