package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure-JVM planar geometry for ARPA target processing (CPA/TCPA, vectors, fusion).
 *
 * Coordinate frame: a local tangent ("flat-earth") plane centred on own ship, in **nautical miles**.
 *   x = East (+), y = North (+). Compass bearing/course is measured clockwise from North (0..360),
 *   so a point at range R and true bearing B is `(R*sin B, R*cos B)`.
 * Speeds are **knots** (NM per hour); times are **seconds**. Over the ranges an ARPA tracks
 * (CAT 1 worst case a few tens of NM) the planar approximation is well within the A.823 §3.8 accuracy
 * budget; meridian convergence is therefore ignored. Per A.823 Appendix 1 Note, all range/bearing/CPA/TCPA
 * are referenced to the radar antenna (own-ship origin).
 */
internal const val PKG = "ui-core.target"

/** 2-D vector in the local North-East plane. Units are NM for positions, knots for velocities. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    /** Dot product. */
    infix fun dot(o: Vec2): Double = x * o.x + y * o.y
    /** Euclidean magnitude. */
    fun norm(): Double = hypot(x, y)

    companion object {
        /** Vector from a compass bearing/course (deg, clockwise from North) and a magnitude. */
        fun ofBearing(bearingDeg: Double, magnitude: Double): Vec2 {
            val r = Math.toRadians(bearingDeg)
            return Vec2(magnitude * sin(r), magnitude * cos(r))
        }
    }
}

/** Geometry conversions between the [TrackedTarget]/[OwnShipData] contract and the planar frame. */
object Geometry {

    /** Wrap any angle into [0, 360). */
    fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Compass bearing/course (deg clockwise from North) of a planar vector; 0 for the zero vector. */
    fun bearingOf(v: Vec2): Double = normalizeDeg(Math.toDegrees(atan2(v.x, v.y)))

    /**
     * True bearing of a target in degrees. [TrackedTarget.bearingDeg] may be relative to the bow
     * (`trueBearing == false`), in which case own-ship heading is added (A.823 Appendix 1 §8/§9:
     * true vs relative bearing).
     */
    fun trueBearingDeg(target: TrackedTarget, ownHeadingDeg: Double?): Double? {
        return if (target.trueBearing) {
            normalizeDeg(target.bearingDeg)
        } else {
            val h = ownHeadingDeg ?: return null
            normalizeDeg(target.bearingDeg + h)
        }
    }

    /**
     * Convert a geographic target position to own-ship-relative **range (NM) + true bearing (deg)** using
     * the equirectangular (flat-earth) approximation valid at radar ranges (same model as the NE plane
     * note above; A.823 App.1 Note). Returns null if any coordinate is missing.
     *   north(NM) = Δlat·60 ; east(NM) = Δlon·60·cos(ownLat) ; range = hypot ; bearing = atan2(east,north).
     */
    fun geoToRangeBearing(ownLat: Double?, ownLon: Double?, tgtLat: Double?, tgtLon: Double?): Pair<Double, Double>? {
        if (ownLat == null || ownLon == null || tgtLat == null || tgtLon == null) return null
        val north = (tgtLat - ownLat) * 60.0
        val east = (tgtLon - ownLon) * 60.0 * cos(Math.toRadians(ownLat))
        val range = hypot(east, north)
        val bearing = normalizeDeg(Math.toDegrees(atan2(east, north)))
        return range to bearing
    }

    /** Target position relative to own ship, in the NE plane (NM). Returns null if bearing can't be resolved. */
    fun relativePosition(target: TrackedTarget, ownShip: OwnShipData): Vec2? {
        val tb = trueBearingDeg(target, ownShip.headingDeg) ?: return null
        return Vec2.ofBearing(tb, target.rangeNm)
    }

    /** Own-ship velocity over ground (knots, NE plane) from COG/SOG. Null if either is missing. */
    fun ownVelocity(ownShip: OwnShipData): Vec2? {
        val cog = ownShip.cogDeg ?: return null
        val sog = ownShip.sogKn ?: return null
        return Vec2.ofBearing(cog, sog)
    }

    /** Target true velocity (knots, NE plane) from its true course/speed. Null if either is missing. */
    fun targetVelocity(target: TrackedTarget): Vec2? {
        val course = target.courseDeg ?: return null
        val speed = target.speedKn ?: return null
        return Vec2.ofBearing(course, speed)
    }
}
