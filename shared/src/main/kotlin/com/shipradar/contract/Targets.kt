package com.shipradar.contract

/**
 * A tracked target — radar TT/ARPA or AIS — in a unified view. Source distinguishes origin so the UI
 * can fuse radar + AIS (IEC 62388 CAT 1: >=40 tracked radar, >=40 active AIS, >=200 sleeping AIS).
 */
data class TrackedTarget(
    val id: String,
    val source: TargetSource,
    val rangeNm: Double,
    val bearingDeg: Double,
    /** true: bearing referenced to true north; false: relative to bow. */
    val trueBearing: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val courseDeg: Double? = null,
    val speedKn: Double? = null,
    /** Closest Point of Approach, nautical miles. null until computed. */
    val cpaNm: Double? = null,
    /** Time to CPA, seconds. Negative = CPA already passed. */
    val tcpaSec: Double? = null,
    val status: TargetStatus,
    /** Dangerous per CPA/TCPA thresholds -> drives alarm 3044 + red symbol. */
    val dangerous: Boolean = false,
    /** AIS static/voyage label — ship name (Msg 5/24); null until static data received. */
    val name: String? = null,
    /** AIS radio call sign (Msg 5/24). */
    val callsign: String? = null,
    /** AIS ship & cargo type code 0..99 (ITU-R M.1371-5 Table 53). */
    val shipType: Int? = null,
)

enum class TargetSource { RADAR_TT, AIS_ACTIVE, AIS_SLEEPING }

enum class TargetStatus { ACQUIRING, TRACKED, LOST, TEST_MANEUVER }
