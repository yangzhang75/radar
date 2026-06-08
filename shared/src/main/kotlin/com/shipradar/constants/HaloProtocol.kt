package com.shipradar.constants

/**
 * HALO default multicast endpoints (single-radar / non-multi-radar network).
 * In a multi-radar network these are negotiated dynamically via the handshake (01B1 -> 01B2),
 * range 236.6.7.32:6000 .. 236.6.9.255:7535. Source: 雷达天线端协议文档-HALO.docx §协议.
 */
data class Endpoint(val address: String, val port: Int)

object HaloEndpoints {
    val NEGOTIATION = Endpoint("236.6.7.4", 6768)
    val SERVICE     = Endpoint("236.6.7.5", 6878)
    val IMAGE       = Endpoint("236.6.7.8", 6678)   // radar -> sw : echo spokes
    val STATUS      = Endpoint("236.6.7.9", 6679)   // radar -> sw : mode/setup
    val CONTROL     = Endpoint("236.6.7.10", 6680)  // sw -> radar : control commands
    val TARGET      = Endpoint("236.6.7.18", 6688)  // radar -> sw : tracked targets
    val TRACK_STATUS = Endpoint("236.6.7.19", 6689)
    val TRACK_CONTROL = Endpoint("236.6.7.20", 6690) // sw -> radar : tracking control
    // Radar B (second range stream / dual-range)
    val B_IMAGE   = Endpoint("236.6.7.13", 6656)
    val B_CONTROL = Endpoint("236.6.7.14", 6657)
    val B_STATUS  = Endpoint("236.6.7.15", 6658)
}

/**
 * HALO control opcodes (little-endian on the wire). 2-byte primary opcode; some carry a 4-byte
 * sub-command (90C1 guard-zone family, 05CB/00CB advanced). Source: §雷达控制 / §雷达高级控制.
 * Values are the opcode bytes in document order (e.g. 0x00C1 -> bytes 00 C1).
 */
object HaloOpcodes {
    const val POWER = 0x00C1
    const val TRANSMIT = 0x01C1
    const val ROTATE = 0x02C1
    const val TIMED_TRANSMIT = 0x0CC1
    const val TIMED_TRANSMIT_SETUP = 0xB0C1
    const val SET_RANGE = 0x03C1
    const val WATCHDOG = 0xA1C1
    const val GAIN_SEA_SIDELOBE = 0x06C1   // type 0 gain / 2 sea / 4 rain / 5 sidelobe
    const val SEA_STATE = 0x0BC1
    const val FAST_SCAN = 0x0FC1
    const val FTC = 0x07C1
    const val INTERFERENCE_REJECTION = 0x08C1
    const val LOCAL_INTERFERENCE_REJECTION = 0x0EC1
    const val NOISE_REJECTION = 0x21C1
    const val TARGET_SEPARATION = 0x22C1
    const val TARGET_EXPANSION = 0x09C1
    const val TARGET_BOOST = 0x0AC1
    const val PLACEMENT_ANGLE = 0x40C1
    const val RANGE_CORRECTION = 0x04C1
    const val BEARING_CORRECTION = 0x05C1
    const val ANTENNA_HEIGHT = 0x30C1
    const val FACTORY_RESET = 0x04C3
    const val GUARD_ZONE = 0x90C1          // sub: 0100 enable / 0200 setup / 0300 sensitivity / 0400 alarm-mode
    const val ADVANCED_05CB = 0x05CB       // sub 0300 0000 -> rpm
    const val ADVANCED_00CB = 0x00CB       // sub Fx00 0000 -> float Q12 dB params

    // Queries
    const val QUERY_ALL = 0x01C2
    const val QUERY_MODE = 0x02C2
    const val QUERY_SETUP = 0x03C2
    const val QUERY_PERFORMANCE = 0x04C2
    const val QUERY_CONFIG = 0x05C2
    const val QUERY_ADVANCED_SETUP = 0x08C2

    // Handshake
    const val LINK_REQUEST = 0x01B1
    const val LINK_ALLOW = 0x01B2

    // gain/sea/sidelobe/rain "type" selector for 06C1
    const val TYPE_GAIN = 0
    const val TYPE_SEA = 2
    const val TYPE_RAIN = 4
    const val TYPE_SIDELOBE = 5
}

/** Watchdog cadence: client must send A1C1 every ~8 s; >30 s -> radar to standby, >1 min -> power off. */
const val HALO_WATCHDOG_PERIOD_MS = 8_000L
