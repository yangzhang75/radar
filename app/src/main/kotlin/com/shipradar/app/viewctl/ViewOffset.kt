package com.shipradar.app.viewctl

import androidx.compose.runtime.Immutable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Off-centre (look-ahead) view offset for the PPI — **pure data + logic, no Android graphics**.
 *
 * The offset is stored as **normalised components relative to the operational radius**, in screen
 * space (**+x right, +y down**). It locates the antenna / CCRP (own-ship) position away from the
 * centre of the operational display area to enlarge the view in one direction (typically ahead).
 *
 * ## How the orchestrator applies it to the PPI (do NOT change the PPI here)
 * Given the centred view centre `base` (px) and the operational radius `radiusPx` (px), the
 * off-centred PPI centre (where the CCRP / own-ship is drawn) is:
 *
 * ```
 * center.x = base.x + offset.x * radiusPx
 * center.y = base.y + offset.y * radiusPx
 * ```
 *
 * i.e. feed `PpiProjection.create(center = base + offset*radiusPx, ...)`. Because the magnitude is
 * clamped to [MAX_OFFSET_FRACTION] (≤ 0.66 of the radius), the CCRP stays inside the operational
 * area. The off-centre limit applies to the antenna/CCRP position, not the bearing scale
 * (IEC 62388 §10.4.2.1, MSC.192/5.9.3).
 *
 * ## Standards basis
 *  - IEC 62388 §10.4.2.1 (MSC.192/5.21.1): manual off-centring SHALL reach at least 50 % of the
 *    radius from the centre.
 *  - IEC 62388 §10.4.2.1 (MSC.192/5.21.2): on selection of an off-centred display (fixed or True
 *    Motion) the antenna position SHALL be locatable up to **at least 50 % and not more than 75 %**
 *    of the radius. [MAX_OFFSET_FRACTION] = 0.66 (≈ 2/3) sits inside that [0.50, 0.75] band — the
 *    classic "look-ahead ≤ 2/3 radius" convention — and so satisfies both the minimum reach and the
 *    maximum limit.
 *  - IEC 62388 §10.4.3.1 (MSC.192/5.21.3): in True Motion the antenna position automatically resets
 *    to 50–75 % of the radius giving the maximum view ahead — see [tmLookAheadReset]. The manual
 *    recenter (back to the bearing-scale centre) is [reset].
 */
@Immutable
data class ViewOffset(val x: Float = 0f, val y: Float = 0f) {

    /** Offset magnitude as a fraction of the operational radius (0 = centred, ≤ [MAX_OFFSET_FRACTION]). */
    val magnitude: Float get() = hypot(x, y)

    /** True when the picture is off-centred (own-ship displaced from the bearing-scale centre). */
    val isOffCenter: Boolean get() = magnitude > CENTER_EPS

    /**
     * Return this offset nudged by (`dx`,`dy`) normalised units, **clamped** so the resulting
     * magnitude never exceeds [MAX_OFFSET_FRACTION] (direction preserved when clamped).
     */
    fun offsetBy(dx: Float, dy: Float): ViewOffset = ViewOffset(x + dx, y + dy).clampedToLimit()

    /** Clamp the magnitude to [MAX_OFFSET_FRACTION], preserving direction (IEC 62388 §10.4.2.1). */
    fun clampedToLimit(): ViewOffset {
        val m = magnitude
        if (m <= MAX_OFFSET_FRACTION || m == 0f) return this
        val scale = MAX_OFFSET_FRACTION / m
        return ViewOffset(x * scale, y * scale)
    }

    /** Manual recenter (回中): own-ship back to the centre of the bearing scale. */
    fun reset(): ViewOffset = CENTERED

    companion object {
        /**
         * Maximum off-centre magnitude as a fraction of the operational radius. IEC 62388 §10.4.2.1
         * (MSC.192/5.21.2) requires the limit to be between 50 % and 75 %; 0.66 (≈ 2/3) is chosen
         * inside that band (look-ahead ≤ 2/3 radius).
         */
        const val MAX_OFFSET_FRACTION = 0.66f

        /** Magnitude below which the picture is treated as centred. */
        const val CENTER_EPS = 1e-4f

        /** The centred (no-offset) view. */
        val CENTERED = ViewOffset(0f, 0f)

        /**
         * Offset magnitude used by the True-Motion look-ahead reset (IEC 62388 §10.4.3.1,
         * MSC.192/5.21.3 — 50–75 % of radius). Same 2/3 as the manual limit.
         */
        const val LOOK_AHEAD_FRACTION = 0.66f

        /**
         * The True-Motion automatic-reset offset that gives **maximum view ahead** (IEC 62388
         * §10.4.3.1): the CCRP is displaced *opposite* the "ahead" direction so the area ahead fills
         * the screen.
         *
         * @param aheadScreenAngleDeg screen angle (clockwise from screen-up) of "ahead". In head-up
         *   and course-up "ahead" is screen-up (0°, the default → CCRP moves to +y, i.e. lower half).
         *   For north-up the orchestrator passes the own-ship heading as the screen angle.
         */
        fun tmLookAheadReset(aheadScreenAngleDeg: Float = 0f): ViewOffset {
            val a = Math.toRadians(aheadScreenAngleDeg.toDouble())
            // ahead unit vector in screen space (+x right, +y down): (sin a, -cos a)
            val ux = sin(a)
            val uy = -cos(a)
            return ViewOffset((-LOOK_AHEAD_FRACTION * ux).toFloat(), (-LOOK_AHEAD_FRACTION * uy).toFloat())
        }
    }
}
