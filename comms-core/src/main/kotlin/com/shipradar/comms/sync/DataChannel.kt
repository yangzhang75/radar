package com.shipradar.comms.sync

/**
 * The logical inbound data streams this module multiplexes. These are independent of the wire
 * (HALO multicast group, 61162-450 LAN group, or 61162-1/2 serial) — the Foreground Service maps
 * each socket/port to one of these channels before feeding [LinkSupervisor.onPacket] /
 * [SyncEngine.onPacket]. Keeping the channel set transport-agnostic is what lets the same staleness/
 * backpressure logic serve HALO over the 蒲公英 VPN *and* the local serial GPS without special-casing.
 *
 * Compliance: channel liveness feeds the 3002 "communications lost" indication (serves SENS-01).
 */
enum class DataChannel {
    /** HALO echo spokes (236.6.7.8:6678). Highest volume; loss-tolerant (old spokes are droppable). */
    ECHO,

    /** Tracked targets — radar TT + AIS (236.6.7.18:6688 and 61162 VDM/TTM). Conflated to latest list. */
    TARGET,

    /** Own-ship navigation, fused from 61162-1/2 (HDT/GGA/RMC/VTG/ROT...). Highest update rate of the nav set. */
    OWN_SHIP,

    /** Radar status (01C4/02C4/08C4...). Conflated to latest; must not be silently dropped. */
    STATUS,

    /** BAM alarm events. Never dropped — alarms are safety-critical. */
    ALARM,
}
