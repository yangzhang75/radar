package com.shipradar.contract

/** Own-ship navigation state, fused from 61162 sentences (HDT/THS/HDG, GGA/RMC/GLL, VTG, ROT...). */
data class OwnShipData(
    val utcMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val headingDeg: Double? = null,
    /** true: heading is true; false: magnetic. */
    val headingTrue: Boolean = true,
    val cogDeg: Double? = null,
    val sogKn: Double? = null,
    val rotDegMin: Double? = null,
    /** Per-sensor validity -> drives the permanent sensor-failure indication (§3.7). */
    val sourceValidity: Map<SensorKind, Boolean> = emptyMap(),
)

enum class SensorKind { HEADING, POSITION, COG_SOG, SPEED_LOG, AIS, RADAR_LINK }
