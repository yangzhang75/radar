package com.shipradar.uicore.ppi

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One fixed range ring: a circle centred at the CCRP at a known range.
 *
 * @param radiusPx pixel radius of the ring (centred at the CCRP — render layer draws the circle).
 * @param rangeNm range of this ring from own ship, nautical miles.
 * @param separationNm spacing between consecutive rings, NM — the value that must be indicated
 *   when rings are displayed (IEC 62388 §9.11.2.1).
 */
data class RangeRing(val radiusPx: Double, val rangeNm: Double, val separationNm: Double)

/**
 * Fixed range-ring geometry. IEC 62388 §9.11.2.1 (MSC.192/5.11.1, 5.11.2):
 *  - "An appropriate number of equally spaced range rings shall be provided for the range scale
 *    selected. When displayed, the range ring scale (separation) shall be indicated."
 *  - "Typically two to six range rings would be provided for nautical mile range scale units."
 *  - Range rings shall always be centred at the CCRP (the projection centre).
 *  - System accuracy within 1 % of the max range of the scale in use or 30 m, whichever is greater
 *    — satisfied here because ring radii are computed analytically (exact) from the scale.
 *  - Mirrored by IEC 62288 Ed.2 §5.9.2.1 (MSC191/7.1.4): "if range rings are displayed, the range
 *    ring scale shall be indicated"; spaced into equal divisions (two to six rings for NM, up to five
 *    for metric); accuracy within 1 % of max range or 30 m; "shall always be centred at the CCRP";
 *    a means to enable/disable shall be provided. Presentation per Annex A (§5.9.2.2 a).
 */
object RangeRings {

    /**
     * "Nice" ring separations (NM) preferred for the mandatory range scales so that ring labels are
     * round numbers. Each mandatory scale resolves to a separation from this set yielding 2..6
     * equal rings (see [defaultRingSeparationNm]).
     */
    private val NICE_SEPARATIONS_NM = listOf(
        0.05, 0.1, 0.125, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0,
    )

    /**
     * Default ring separation (NM) for [scaleNm]: the largest ring count in 2..6 whose separation
     * is a "nice" round number, preferring more rings. Falls back to scale/4 for non-mandatory
     * scales with no nice divisor.
     *
     * For the 8 mandatory scales this yields: 0.25→0.05(5), 0.5→0.1(5), 0.75→0.125(6),
     * 1.5→0.25(6), 3→0.5(6), 6→1(6), 12→2(6), 24→4(6) rings — all within the 2..6 range.
     */
    fun defaultRingSeparationNm(scaleNm: Double): Double {
        require(scaleNm > 0.0) { "scaleNm must be > 0" }
        for (n in 6 downTo 2) {
            val sep = scaleNm / n
            if (NICE_SEPARATIONS_NM.any { abs(it - sep) <= 1e-6 }) return sep
        }
        return scaleNm / 4.0
    }

    /**
     * Generate equally spaced range rings for the selected range scale.
     *
     * @param scaleNm selected range scale, NM (typically one of the mandatory scales).
     * @param radiusPx pixel radius of the operational display area (range fraction 1.0).
     * @param ringCount optional override of the ring count; when null, derived from
     *   [defaultRingSeparationNm]. Must be ≥ 1.
     * @return rings ordered inner→outer; the outermost ring is at [radiusPx] / [scaleNm].
     */
    fun rangeRings(scaleNm: Double, radiusPx: Double, ringCount: Int? = null): List<RangeRing> {
        require(scaleNm > 0.0) { "scaleNm must be > 0" }
        require(radiusPx > 0.0) { "radiusPx must be > 0" }
        val count = ringCount ?: (scaleNm / defaultRingSeparationNm(scaleNm)).roundToInt()
        require(count >= 1) { "ringCount must be >= 1" }
        val separationNm = scaleNm / count
        return (1..count).map { i ->
            val fraction = i.toDouble() / count
            RangeRing(
                radiusPx = radiusPx * fraction,
                rangeNm = scaleNm * fraction,
                separationNm = separationNm,
            )
        }
    }
}
