package com.shipradar.app.ppi

import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.PpiOrientation

/**
 * Immutable display configuration for [PpiView]. Everything here is data the render surface needs
 * but does not own: the operator/HMI (T2.4 range & mode, T2.9 framework) and the sensor fusion
 * (own-ship heading/COG) feed it in. Pure values — no Android types.
 *
 * @param rangeScaleNm selected range scale, NM (one of the mandatory scales — IEC 62388 §9.4.1.1).
 * @param orientation display orientation (head-up §3.32 / north-up §3.44 / course-up §3.17). All
 *   three SHALL be provided — IEC 62388 §10.4.4.1 (MSC.192/5.20.2).
 * @param palette ambient HMI palette (day/dusk/night — IEC 62288 §4.5.1, Table 1).
 * @param headingDeg own-ship heading, deg true. Required for NORTH_UP/COURSE_UP; null ⇒ render
 *   falls back to HEAD_UP — the mandated fallback "when heading-sensor data becomes unavailable"
 *   (IEC 62388 §10.4.4.1, MSC.192/5.20.2). See [effectiveOrientation].
 * @param courseDeg own-ship course over ground, deg true. Required for COURSE_UP.
 * @param antennaRpm antenna rotation rate (rev/min), used only to bound the range-change blanking
 *   budget to ≤ 1 scan (DISP-02, IEC 62388 §9.4.1.2(d)).
 * @param spokeWidthDeg angular width painted per spoke. Default ≈ 360/2048 (HALO full-rotation
 *   spoke count) so adjacent spokes tile without radial gaps. TODO(待确认): exact HALO spoke count
 *   per revolution — see [com.shipradar.constants] / T1.2 spoke parser.
 * @param showRangeRings / [showBearingScale] / [showHeadingLine] graphic layer toggles
 *   (IEC 62388 §9.11/§9.10/§8.2.3 — each must be switchable).
 */
data class PpiConfig(
    val rangeScaleNm: Double = 6.0,
    val orientation: PpiOrientation = PpiOrientation.HEAD_UP,
    val palette: ColorMapper.Palette = ColorMapper.Palette.DAY,
    val headingDeg: Double? = null,
    val courseDeg: Double? = null,
    val antennaRpm: Double = 24.0,
    val spokeWidthDeg: Float = 360f / 2048f,
    val showRangeRings: Boolean = true,
    val showBearingScale: Boolean = true,
    val showHeadingLine: Boolean = true,
) {
    /**
     * The orientation actually usable given available sensors. Azimuth-stabilised modes (north-up,
     * course-up) need a heading (course-up also a course); when the data is missing this falls back
     * to HEAD_UP — the fallback mandated by IEC 62388 §10.4.4.1 (MSC.192/5.20.2) "when heading-sensor
     * data becomes unavailable". HEAD_UP itself always passes through.
     */
    val effectiveOrientation: PpiOrientation
        get() = when (orientation) {
            PpiOrientation.HEAD_UP -> PpiOrientation.HEAD_UP
            PpiOrientation.NORTH_UP -> if (headingDeg != null) PpiOrientation.NORTH_UP else PpiOrientation.HEAD_UP
            PpiOrientation.COURSE_UP ->
                if (headingDeg != null && courseDeg != null) PpiOrientation.COURSE_UP else PpiOrientation.HEAD_UP
        }
}
