package com.shipradar.uicore.ppi

import com.shipradar.util.Angles
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.atan2

/**
 * Platform-independent PPI (Plan Position Indicator) geometry.
 *
 * Pure math only: every value here is a plain [Double]/[Float] in screen pixels or a normalised
 * fraction. NO Android / Compose / Canvas types. The Android render surface (T2.2 colouring,
 * T2.3 target symbols, and the GL/Canvas shell) calls these functions to place echoes and graphics.
 *
 * Coordinate conventions (frozen — every other file in this package depends on them):
 *  - Azimuth: degrees, **0 = own ship's bow (head)**, increasing **clockwise** (matches
 *    [com.shipradar.contract.EchoSpoke.azimuthDeg] and the HALO spoke encoding).
 *  - Screen: pixel space with **+x right, +y down** (the usual raster convention). The PPI centre
 *    is the CCRP (consistent common reference point, IEC 62388 §3.12). "Up" on screen is −y.
 *  - Screen angle: degrees clockwise from screen-up. 0 = up, 90 = right (+x), 180 = down, 270 = left.
 */

/** A point in screen pixel space (+x right, +y down). */
data class ScreenPoint(val x: Double, val y: Double)

/** A polar position relative to own-ship bow, range as a fraction of the selected range scale. */
data class PolarPosition(val azimuthDeg: Double, val rangeFraction: Double)

/**
 * PPI display orientation (azimuth reference that points to screen-up).
 *
 * Definitions per IEC 62388 §3 (IMO MSC.192(79) App.2):
 *  - [HEAD_UP]   §3.32: own ship's heading oriented "up"; bearing scale top shows relative 000°.
 *  - [NORTH_UP]  §3.44: north fixed vertically above the CCRP; azimuth-stabilised.
 *  - [COURSE_UP] §3.17: own ship's course (COG) vertically above the CCRP; azimuth-stabilised.
 *
 * Availability: IEC 62388 §10.4.4.1 (MSC.192/5.20.2) — north-up and course-up SHALL be provided;
 * head-up SHALL be provided both as a selectable mode and **as the fallback when heading-sensor
 * data becomes unavailable**. NORTH_UP and COURSE_UP are azimuth-stabilised and therefore require a
 * valid heading (and, for COURSE_UP, a course); on heading loss the render layer falls back to
 * HEAD_UP (unstabilised) — see [resolveDisplayRotationDeg] and `PpiConfig.effectiveOrientation`.
 * (§10.4.4.1 / MSC.192/5.20.3 also requires a *permanent* indication of the mode in use — owned by
 * the data bar / framework layer, not this geometry.)
 */
enum class PpiOrientation { HEAD_UP, NORTH_UP, COURSE_UP }

/**
 * The angular offset (degrees) added to a bow-relative azimuth to obtain the screen angle
 * (clockwise from up) for a given orientation.
 *
 *  - HEAD_UP:   0 — bow-relative azimuth maps directly to screen angle (bow at top).
 *  - NORTH_UP:  +heading — a target at bow-relative azimuth θ sits at true bearing (heading+θ),
 *               which is its screen angle when north is up.
 *  - COURSE_UP: +(heading − course) — same, but the course (not north) is at the top.
 *
 * @param headingDeg own-ship heading, degrees true. Required (non-null) for NORTH_UP/COURSE_UP.
 * @param courseDeg own-ship course over ground, degrees true. Required (non-null) for COURSE_UP.
 * @throws IllegalArgumentException if a stabilised mode is requested without the data it needs.
 *   The render layer is expected to catch this (or pre-check sensor validity) and fall back to
 *   HEAD_UP, which is the correct unstabilised behaviour on gyro/COG failure.
 */
fun resolveDisplayRotationDeg(
    orientation: PpiOrientation,
    headingDeg: Double? = null,
    courseDeg: Double? = null,
): Double = when (orientation) {
    PpiOrientation.HEAD_UP -> 0.0
    PpiOrientation.NORTH_UP -> {
        requireNotNull(headingDeg) { "NORTH_UP is azimuth-stabilised and requires a valid heading" }
        Angles.normalizeDeg(headingDeg)
    }
    PpiOrientation.COURSE_UP -> {
        requireNotNull(headingDeg) { "COURSE_UP is azimuth-stabilised and requires a valid heading" }
        requireNotNull(courseDeg) { "COURSE_UP requires a valid course over ground" }
        Angles.normalizeDeg(headingDeg - courseDeg)
    }
}

/**
 * An immutable per-frame PPI projection. Build once with [create] from the current viewport and
 * own-ship orientation, then call [polarToScreen] for every echo spoke / sample (cheap, no
 * trig setup state). The [displayRotationDeg] is baked in so per-sample plotting never re-resolves
 * the orientation.
 *
 * @param centerX CCRP screen x (px).
 * @param centerY CCRP screen y (px).
 * @param radiusPx pixel radius of the operational display area (the selected range scale maps to
 *   range fraction 1.0 at this radius — over-scan extends beyond, see [RangeModel]).
 * @param displayRotationDeg offset from bow-relative azimuth to screen angle (see
 *   [resolveDisplayRotationDeg]).
 */
data class PpiProjection(
    val centerX: Double,
    val centerY: Double,
    val radiusPx: Double,
    val displayRotationDeg: Double,
) {
    val center: ScreenPoint get() = ScreenPoint(centerX, centerY)

    /**
     * Map a bow-relative polar position to a screen pixel.
     *
     * @param azimuthDeg bow-relative azimuth, 0 = bow, clockwise positive.
     * @param rangeFraction range as a fraction of the selected range scale; 1.0 = outer ring
     *   ([radiusPx]). May exceed 1.0 within the over-scan region (the render layer clips).
     */
    fun polarToScreen(azimuthDeg: Double, rangeFraction: Double): ScreenPoint =
        screenAngleToPoint(Angles.normalizeDeg(azimuthDeg + displayRotationDeg), rangeFraction * radiusPx)

    /**
     * Place a point at a raw screen angle (clockwise from up) and a pixel radius. Used for graphics
     * that are already expressed in screen-angle terms (bearing-scale ticks, heading line, EBL).
     */
    fun screenAngleToPoint(screenAngleDeg: Double, rPx: Double): ScreenPoint {
        val phi = Math.toRadians(screenAngleDeg)
        return ScreenPoint(centerX + rPx * sin(phi), centerY - rPx * cos(phi))
    }

    /** Inverse of [polarToScreen]: a screen pixel → bow-relative polar position. */
    fun screenToPolar(point: ScreenPoint): PolarPosition {
        val dx = point.x - centerX
        val dy = point.y - centerY
        val r = hypot(dx, dy)
        // dx = r·sinφ, dy = −r·cosφ  ⇒  φ = atan2(dx, −dy)
        val screenAngle = Angles.normalizeDeg(Math.toDegrees(atan2(dx, -dy)))
        return PolarPosition(
            azimuthDeg = Angles.normalizeDeg(screenAngle - displayRotationDeg),
            rangeFraction = if (radiusPx == 0.0) 0.0 else r / radiusPx,
        )
    }

    companion object {
        /**
         * Build a projection for the current frame.
         *
         * @see resolveDisplayRotationDeg for the heading/course requirements of stabilised modes.
         */
        fun create(
            center: ScreenPoint,
            radiusPx: Double,
            orientation: PpiOrientation,
            headingDeg: Double? = null,
            courseDeg: Double? = null,
        ): PpiProjection = PpiProjection(
            centerX = center.x,
            centerY = center.y,
            radiusPx = radiusPx,
            displayRotationDeg = resolveDisplayRotationDeg(orientation, headingDeg, courseDeg),
        )
    }
}

/**
 * Convenience one-shot form matching the task contract signature
 * `polarToScreen(azimuthDeg, rangeFraction, center, radiusPx, orientation)`.
 *
 * Builds a throwaway [PpiProjection] each call — fine for ad-hoc graphics, but to plot a whole
 * sweep of echoes build a [PpiProjection] once with [PpiProjection.create] and reuse it.
 */
fun polarToScreen(
    azimuthDeg: Double,
    rangeFraction: Double,
    center: ScreenPoint,
    radiusPx: Double,
    orientation: PpiOrientation,
    headingDeg: Double? = null,
    courseDeg: Double? = null,
): ScreenPoint =
    PpiProjection.create(center, radiusPx, orientation, headingDeg, courseDeg)
        .polarToScreen(azimuthDeg, rangeFraction)
