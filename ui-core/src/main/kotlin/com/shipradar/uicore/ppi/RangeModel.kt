package com.shipradar.uicore.ppi

import com.shipradar.constants.MANDATORY_RANGE_SCALES_NM
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Range-axis geometry: the radial mapping between echo samples, range in nautical miles, range
 * fractions, and screen pixels — plus the mandatory range-scale stepping (DISP-02).
 *
 * Pure functions; no platform dependency. "Range fraction" 0..1 spans the selected range scale
 * (fraction 1.0 = the outer range ring at [PpiProjection.radiusPx]); the over-scan region lies at
 * fraction > 1.0.
 */
object RangeModel {

    /** One nautical mile in metres (exact, by definition). */
    const val METERS_PER_NM = 1852.0

    /**
     * HALO spoke over-scan factor: a spoke carries samples covering 1.8× the *selected* range so
     * the picture can be off-centred / look ahead and so range changes are seamless. The selected
     * range maps to fraction 1.0; samples beyond that are over-scan and are clipped by the render
     * layer. Source: 雷达天线端协议文档-HALO.docx §3.2 (over-scan = 1.8); cross-checked against the
     * IEC 62388 §9.4.1.2(e) requirement that the displayed range be within +0 % … +8 % of the
     * range scale at the cardinal points (over-scan supplies the headroom; the render layer must
     * clip the visible picture to ≤ +8 %).
     */
    const val OVER_SCAN = 1.8

    // ---- sample ↔ range fraction (over-scan) -------------------------------------------------

    /**
     * Range fraction (of the selected range scale) for sample [index] of a spoke with
     * [totalSamples] samples. Sample 0 is at the CCRP; the last sample is at the full over-scanned
     * range (fraction = [OVER_SCAN]). Linear range axis (IEC 62388 §6 / MSC.192/5.9.5).
     */
    fun sampleIndexToRangeFraction(index: Int, totalSamples: Int): Double {
        require(totalSamples > 0) { "totalSamples must be > 0" }
        return OVER_SCAN * index / totalSamples
    }

    /**
     * Number of samples that fall within the selected range (fraction ≤ 1.0), i.e. the count to
     * keep when clipping the over-scan region. Indices `0 until inRangeSampleCount` are in range.
     *
     * NOTE: the precise boundary sample is a sub-pixel ±1 detail tied to the exact `nOfSamples`
     * and the inclusive/exclusive convention of the spoke header (§3.2, owned by T1.2). The doc
     * example "samples 0~569 in range" corresponds to `round(1024 / 1.8) = 569` in-range samples;
     * this uses [roundToInt] to match. See delivery report — flagged for confirmation against §3.2.
     */
    fun inRangeSampleCount(totalSamples: Int): Int {
        require(totalSamples > 0) { "totalSamples must be > 0" }
        return (totalSamples / OVER_SCAN).roundToInt()
    }

    /** True if sample [index] falls within the selected range (i.e. is not over-scan). */
    fun isSampleInRange(index: Int, totalSamples: Int): Boolean =
        index in 0 until inRangeSampleCount(totalSamples)

    // ---- NM ↔ fraction ↔ pixels --------------------------------------------------------------

    /** Range in NM → fraction of the selected range scale. */
    fun rangeNmToFraction(rangeNm: Double, scaleNm: Double): Double {
        require(scaleNm > 0.0) { "scaleNm must be > 0" }
        return rangeNm / scaleNm
    }

    /** Fraction of the selected range scale → range in NM. */
    fun fractionToRangeNm(fraction: Double, scaleNm: Double): Double = fraction * scaleNm

    /** Range in NM → pixel radius, given the selected range scale and the operational radius. */
    fun rangeNmToPx(rangeNm: Double, scaleNm: Double, radiusPx: Double): Double {
        require(scaleNm > 0.0) { "scaleNm must be > 0" }
        return rangeNm / scaleNm * radiusPx
    }

    /** Pixel radius → range in NM, given the selected range scale and the operational radius. */
    fun pxToRangeNm(px: Double, scaleNm: Double, radiusPx: Double): Double {
        require(radiusPx > 0.0) { "radiusPx must be > 0" }
        return px / radiusPx * scaleNm
    }

    fun nmToMeters(nm: Double): Double = nm * METERS_PER_NM
    fun metersToNm(m: Double): Double = m / METERS_PER_NM

    // ---- mandatory range scales (IEC 62388 §9.4.1.1, MSC.192/5.10.1) -------------------------
    //
    // Range scales of 0.25, 0.5, 0.75, 1.5, 3, 6, 12, 24 NM SHALL be provided
    // (com.shipradar.constants.MANDATORY_RANGE_SCALES_NM). Additional NM scales are permitted
    // outside the set and must not interrupt the consecutive mandatory sequence.

    /** The mandatory range scales (NM), ascending. */
    val mandatoryScalesNm: List<Double> get() = MANDATORY_RANGE_SCALES_NM

    /** True if [scaleNm] is one of the mandatory scales (within floating tolerance). */
    fun isMandatoryScale(scaleNm: Double): Boolean =
        MANDATORY_RANGE_SCALES_NM.any { kotlin.math.abs(it - scaleNm) <= 1e-9 }

    /**
     * The next range scale **up** (longer range) from [currentNm]. If [currentNm] is at or above
     * the largest mandatory scale, returns the largest mandatory scale (no change). If [currentNm]
     * is not itself a mandatory value (e.g. an additional scale), returns the smallest mandatory
     * scale strictly greater than it.
     */
    fun nextRangeScale(currentNm: Double): Double =
        MANDATORY_RANGE_SCALES_NM.firstOrNull { it > currentNm + 1e-9 } ?: MANDATORY_RANGE_SCALES_NM.last()

    /**
     * The next range scale **down** (shorter range) from [currentNm]. If [currentNm] is at or below
     * the smallest mandatory scale, returns the smallest mandatory scale (no change). If [currentNm]
     * is not a mandatory value, returns the largest mandatory scale strictly smaller than it.
     */
    fun previousRangeScale(currentNm: Double): Double =
        MANDATORY_RANGE_SCALES_NM.lastOrNull { it < currentNm - 1e-9 } ?: MANDATORY_RANGE_SCALES_NM.first()

    // ---- range-change blanking (DISP-02, IEC 62388 §9.4.1.2(d)) ------------------------------
    //
    // "The display shall not be blanked for more than 1 scan after a change in range scale and
    // within that period, full functionality is restored." This module owns only the geometric /
    // timing budget; the actual blank-then-redraw is the render layer's (T2.1 render / T2.4).

    /** Maximum number of antenna scans the picture may be blank after a range change (§9.4.1.2(d)). */
    const val MAX_BLANKING_SCANS = 1

    /**
     * Antenna scan period in milliseconds for a given rotation rate. One scan = one full 360°
     * revolution. e.g. 24 rpm → 2500 ms.
     */
    fun scanPeriodMs(rpm: Double): Double {
        require(rpm > 0.0) { "rpm must be > 0" }
        return 60_000.0 / rpm
    }

    /**
     * The maximum permitted blanking duration (ms) after a range change at the given [rpm],
     * i.e. [MAX_BLANKING_SCANS] × [scanPeriodMs]. DISP-02 — the render layer must restore full
     * functionality within this budget.
     */
    fun maxBlankingDurationMs(rpm: Double): Double = MAX_BLANKING_SCANS * scanPeriodMs(rpm)

    // ---- over-scan / +8 % cardinal-point check (IEC 62388 §9.4.1.2(e)) -----------------------

    /**
     * Maximum range fraction the render layer may actually display at the cardinal points and still
     * comply with IEC 62388 §9.4.1.2(e): the displayed range must be within +0 % … +8 % of the
     * selected range scale. Over-scan ([OVER_SCAN] = 1.8 → +80 %) supplies the data; this is the
     * clip ceiling the picture must not exceed.
     */
    const val MAX_DISPLAYED_RANGE_FRACTION = 1.08

    /** The largest sample index that may be displayed at a cardinal point under §9.4.1.2(e). */
    fun maxDisplayableSampleIndex(totalSamples: Int): Int {
        require(totalSamples > 0) { "totalSamples must be > 0" }
        return floor(MAX_DISPLAYED_RANGE_FRACTION * totalSamples / OVER_SCAN).toInt()
    }
}
