package com.shipradar.uicore.target

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget

/**
 * Turns an AIS position report (geographic) into a unified [TrackedTarget] with own-ship-relative
 * range/true-bearing, so AIS contacts join the same target list as radar TTs and can be fused/de-duped
 * (IEC 62388 §11.5/§5.30). Primitive inputs (not the parser type) keep this in the pure `:ui-core` layer.
 */
object AisTargetBuilder {

    /**
     * Build an AIS target, or null if it can't be georeferenced (missing own/target position). [active]
     * selects the activated vs sleeping symbol class. Range/bearing via [Geometry.geoToRangeBearing].
     */
    fun build(
        mmsi: Long,
        targetLat: Double?,
        targetLon: Double?,
        cogDeg: Double?,
        sogKn: Double?,
        ownLat: Double?,
        ownLon: Double?,
        active: Boolean = true,
    ): TrackedTarget? {
        val (rangeNm, bearing) = Geometry.geoToRangeBearing(ownLat, ownLon, targetLat, targetLon) ?: return null
        return TrackedTarget(
            id = "AIS-$mmsi",
            source = if (active) TargetSource.AIS_ACTIVE else TargetSource.AIS_SLEEPING,
            rangeNm = rangeNm,
            bearingDeg = bearing,
            trueBearing = true,
            latitude = targetLat,
            longitude = targetLon,
            courseDeg = cogDeg,
            speedKn = sogKn,
            status = TargetStatus.TRACKED,
        )
    }
}
