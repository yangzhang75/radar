package com.shipradar.contract

/**
 * Conning / engine-room read-outs fused from 61162 sentences (RSA rudder, RPM shaft/engine, DPT/DBT
 * depth). Drives the OpenBridge conning instruments (rudder / RPM / depth) in the right-hand column,
 * mirroring the JRC RADAR reference. All fields nullable — absent until the matching sentence arrives.
 */
data class ConningData(
    /** Main / starboard rudder angle (deg); + = starboard, − = port (IEC 61162-1 §8.3.86). */
    val rudderAngleDeg: Double? = null,
    /** Port rudder angle (deg) for twin-rudder vessels; null on single-rudder ships. */
    val portRudderAngleDeg: Double? = null,
    /** Starboard / main shaft or engine speed (rev/min, §8.3.84). */
    val rpmStbd: Double? = null,
    /** Port shaft or engine speed (rev/min). */
    val rpmPort: Double? = null,
    /** Water depth below the waterline (m), from DPT/DBT (§8.3.28/25). */
    val depthM: Double? = null,
) {
    /** Field-wise overlay: take each non-null field from [other], keep the current value otherwise. */
    fun mergedWith(other: ConningData) = ConningData(
        rudderAngleDeg = other.rudderAngleDeg ?: rudderAngleDeg,
        portRudderAngleDeg = other.portRudderAngleDeg ?: portRudderAngleDeg,
        rpmStbd = other.rpmStbd ?: rpmStbd,
        rpmPort = other.rpmPort ?: rpmPort,
        depthM = other.depthM ?: depthM,
    )
}
