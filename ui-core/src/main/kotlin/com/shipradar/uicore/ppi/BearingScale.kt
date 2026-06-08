package com.shipradar.uicore.ppi

import com.shipradar.util.Angles

/** Importance level of a bearing-scale division mark. */
enum class BearingTickLevel {
    /** Numbered division, every 30° (IEC 62388 §9.10.2.1 — "numbered at least every 30 degrees"). */
    MAJOR,
    /** 10° division mark. */
    MEDIUM,
    /** 5° division mark (the minimum mandated spacing). */
    MINOR,
    /** Optional 1° division mark (permitted "where clearly distinguishable"). */
    FINE,
}

/**
 * One tick on the bearing scale around the periphery of the operational display area.
 *
 * @param bearingDeg the bearing value this tick represents, in the orientation's reference frame
 *   (relative-to-bow for HEAD_UP; true for NORTH_UP/COURSE_UP). 0..360.
 * @param screenAngleDeg the tick's screen angle, clockwise from screen-up (place via
 *   [PpiProjection.screenAngleToPoint]).
 * @param level division-mark importance.
 * @param label the numeric label for [BearingTickLevel.MAJOR] ticks (e.g. "030"), else null.
 */
data class BearingTick(
    val bearingDeg: Double,
    val screenAngleDeg: Double,
    val level: BearingTickLevel,
    val label: String?,
)

/**
 * Bearing-scale, heading-line and EBL geometry around the PPI periphery.
 *
 * IEC 62388 §9.10.2.1 (MSC.192/5.13.1, 5.13.2): a bearing scale around the periphery, indicating
 * bearing as seen from the CCRP; numbered at least every 30°; division marks of at least 5°; the
 * 5° and 10° marks clearly distinguishable; 1° marks may be presented. We emit 30° (numbered),
 * 10°, and 5° marks by default, with optional 1° marks.
 *
 * The scale rotates with the display orientation: in NORTH_UP the labels are true bearings with
 * 000 at the top; in HEAD_UP they are relative bearings with 000 at the bow (top); in COURSE_UP
 * the course sits at the top. Pure geometry — no platform types.
 */
object BearingScale {

    /**
     * Generate the bearing-scale ticks for the whole 360° periphery.
     *
     * @param orientation display orientation (drives whether labels are relative or true, and the
     *   screen rotation).
     * @param headingDeg own-ship heading (deg true) — required for NORTH_UP/COURSE_UP.
     * @param courseDeg own-ship course over ground (deg true) — required for COURSE_UP.
     * @param includeFine emit optional 1° ticks ([BearingTickLevel.FINE]) in addition to the 5° set.
     */
    fun bearingScaleTicks(
        orientation: PpiOrientation,
        headingDeg: Double? = null,
        courseDeg: Double? = null,
        includeFine: Boolean = false,
    ): List<BearingTick> {
        val rotation = resolveDisplayRotationDeg(orientation, headingDeg, courseDeg)
        // For stabilised modes the label value is a TRUE bearing whose bow-relative azimuth is
        // (value − heading); for HEAD_UP the value is already bow-relative. In both cases the
        // screen angle is (relativeAzimuth + rotation), which reduces to:
        //   HEAD_UP    → screenAngle = value
        //   NORTH_UP   → screenAngle = value            (north at top)
        //   COURSE_UP  → screenAngle = value − course   (course at top)
        val labelIsTrue = orientation != PpiOrientation.HEAD_UP
        val step = if (includeFine) 1 else 5
        val ticks = ArrayList<BearingTick>(360 / step)
        var v = 0
        while (v < 360) {
            val level = when {
                v % 30 == 0 -> BearingTickLevel.MAJOR
                v % 10 == 0 -> BearingTickLevel.MEDIUM
                v % 5 == 0 -> BearingTickLevel.MINOR
                else -> BearingTickLevel.FINE
            }
            val relativeAzimuth = if (labelIsTrue) v - (headingDeg ?: 0.0) else v.toDouble()
            ticks += BearingTick(
                bearingDeg = v.toDouble(),
                screenAngleDeg = Angles.normalizeDeg(relativeAzimuth + rotation),
                level = level,
                label = if (level == BearingTickLevel.MAJOR) "%03d".format(v) else null,
            )
            v += step
        }
        return ticks
    }

    /**
     * Heading line: a graphic line from the CCRP to the bearing scale indicating own-ship heading
     * (IEC 62388 §8.2.3.1, MSC.192/5.14.1). Points along the bow (bow-relative azimuth 0), so its
     * screen angle equals the display rotation.
     *
     * @return (centre, edge) screen points; the edge is at [PpiProjection.radiusPx]. The render
     *   layer extends/dims it per §8.2.3 (must reach the bearing scale; not variable to extinction).
     */
    fun headingLine(projection: PpiProjection): Pair<ScreenPoint, ScreenPoint> =
        projection.center to projection.screenAngleToPoint(projection.displayRotationDeg, projection.radiusPx)

    /**
     * Electronic bearing line (EBL) — minimal placeholder for T2.1 geometry. Returns the line from
     * the CCRP to the operational-area edge at the requested bearing.
     *
     * @param bearingDeg the EBL bearing.
     * @param relativeToHeading true → [bearingDeg] is relative to own-ship heading; false → true bearing.
     * @param headingDeg own-ship heading (deg true) — required when [relativeToHeading] is false.
     *
     * TODO(T2.x): full EBL per IEC 62388 §9.6 — two independent EBLs, movable origin away from the
     *   CCRP (§9.6.3), origin fixed or moving at own-ship velocity, numeric readout, ±0.5° setting.
     *   This placeholder covers only the centred-origin line geometry.
     */
    fun electronicBearingLine(
        projection: PpiProjection,
        bearingDeg: Double,
        relativeToHeading: Boolean,
        headingDeg: Double? = null,
    ): Pair<ScreenPoint, ScreenPoint> {
        val relativeAzimuth = if (relativeToHeading) {
            bearingDeg
        } else {
            bearingDeg - requireNotNull(headingDeg) { "true-bearing EBL requires a heading" }
        }
        val screenAngle = Angles.normalizeDeg(relativeAzimuth + projection.displayRotationDeg)
        return projection.center to projection.screenAngleToPoint(screenAngle, projection.radiusPx)
    }
}
