package com.shipradar.app.ppi

import kotlin.math.min

/**
 * Single source of truth for the PPI **operational-circle geometry**, shared by the echo renderer
 * ([PpiView]), the target overlay (`app.target.TargetOverlay`) and the interaction layer
 * (`app.input.RadarInputLayer`, wired in `RadarScreen`). All three MUST use the same centre + radius,
 * otherwise targets/cursor are drawn off the range rings (they previously diverged — targets landed
 * outside the circle). Centre is always the view centre; radius is below.
 */
object PpiLayout {
    /** Margin (dp) reserved OUTSIDE the operational circle for the bearing scale (IEC 62388 §9.10.2.1). */
    const val BEARING_SCALE_MARGIN_DP = 30f

    /** Operational-circle radius (px) for a [widthPx]×[heightPx] view at [density], scale margin reserved. */
    fun operationalRadiusPx(widthPx: Float, heightPx: Float, density: Float): Float =
        (min(widthPx, heightPx) / 2f - BEARING_SCALE_MARGIN_DP * density).coerceAtLeast(1f)
}
