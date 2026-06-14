package com.shipradar.sim.ais

/**
 * W8-B — a simulated AIS target: settable static + dynamic information, from which [AisEncoder]
 * produces `!AIVDM` sentences. Plain immutable data so a GUI can add/remove targets and nudge
 * position/speed with `copy(...)`.
 *
 * Field semantics & ranges follow **ITU-R M.1371-5 §3.3** (Messages 1/3 position, 5 static & voyage,
 * 24 Class B static). Out-of-range values are clamped to the field sentinel at encode time, never
 * fabricated.
 */
data class AisTarget(
    /** 9-digit ship-station MMSI (the AIS unique identifier; first 3 digits = MID → [country]). */
    val mmsi: Int,

    // ---- static / voyage (Message 5, 24) ----
    /** Ship name, ≤20 six-bit chars (Message 5/24A). */
    val name: String = "",
    /** Radio call sign, ≤7 six-bit chars (Message 5/24B). */
    val callsign: String = "",
    /** IMO number (Message 5); 0 = not available. */
    val imo: Int = 0,
    /** Ship & cargo type code 0..99 per ITU-R M.1371-5 Table 53 (Message 5/24B). */
    val shipType: Int = 0,
    /** Reference-point dimensions in metres (antenna to bow/stern/port/starboard) (Message 5/24B). */
    val dimToBow: Int = 0,
    val dimToStern: Int = 0,
    val dimToPort: Int = 0,
    val dimToStarboard: Int = 0,
    /** Voyage destination, ≤20 six-bit chars (Message 5). */
    val destination: String = "",
    /** Static draught in metres (Message 5); 0 = not available. */
    val draughtMeters: Double = 0.0,

    // ---- dynamic (Message 1/3) ----
    /** Latitude in degrees, +N / −S (Message 1/3); ±91 sentinel applied when out of ±90. */
    val latitude: Double = 0.0,
    /** Longitude in degrees, +E / −W (Message 1/3); ±181 sentinel applied when out of ±180. */
    val longitude: Double = 0.0,
    /** Speed over ground, knots, 0..102.2 (Message 1/3). */
    val sogKn: Double = 0.0,
    /** Course over ground, degrees 0..359.9 (Message 1/3). */
    val cogDeg: Double = 0.0,
    /** True heading, degrees 0..359; null = not available (511) (Message 1/3). */
    val headingDeg: Int? = null,
    /** Rate of turn, °/min, +starboard; 0 = not turning (Message 1/3). */
    val rotDegMin: Double = 0.0,
    /** Navigational status 0..15 (Message 1/3, Table 45); 15 = not defined (default). */
    val navStatus: Int = NavStatus.UNDEFINED,
) {
    init {
        require(mmsi in 0..999_999_999) { "MMSI must be ≤9 digits, was $mmsi" }
    }

    /** Flag state derived from the MMSI's MID, or null if the MID is not in the table. */
    val country: String? get() = countryOf(mmsi)
}

/** Navigational-status codes (ITU-R M.1371-5 §3.3, Message 1/3 Table 45) — the commonly used ones. */
object NavStatus {
    const val UNDER_WAY_ENGINE = 0
    const val AT_ANCHOR = 1
    const val NOT_UNDER_COMMAND = 2
    const val RESTRICTED_MANOEUVRABILITY = 3
    const val CONSTRAINED_BY_DRAUGHT = 4
    const val MOORED = 5
    const val AGROUND = 6
    const val ENGAGED_IN_FISHING = 7
    const val UNDER_WAY_SAILING = 8
    const val UNDEFINED = 15
}
