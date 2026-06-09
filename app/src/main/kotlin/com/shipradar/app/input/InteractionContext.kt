package com.shipradar.app.input

import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.PpiProjection
import com.shipradar.uicore.ppi.ScreenPoint

/**
 * Everything the [RadarInteractionController] needs about the current frame to interpret a pointer
 * position: the (possibly off-centred) PPI projection, the selected range scale, own-ship heading
 * (for true/relative bearing conversion), and the on-screen targets for hit-testing.
 *
 * Built once per frame by the Compose layer from the shared PPI view-state; the controller stays a
 * set of pure functions over (model, context) so the touch / keyboard / mouse paths are identical.
 */
data class InteractionContext(
    /** Projection whose centre is the CURRENT CCRP screen position (off-centring already applied). */
    val projection: PpiProjection,
    /** Selected range scale, NM (one of the mandatory scales, IEC 62388 §9.4.1). */
    val rangeScaleNm: Double,
    /** Own-ship true heading, deg; null when the heading sensor is invalid (unstabilised). */
    val ownHeadingDeg: Double? = null,
    /** Targets currently displayed, for cursor pick (§9.7.3). */
    val targets: List<TrackedTarget> = emptyList(),
    /** Pick tolerance in pixels for target selection. */
    val selectThresholdPx: Double = DEFAULT_SELECT_THRESHOLD_PX,
) {
    companion object {
        const val DEFAULT_SELECT_THRESHOLD_PX = 28.0

        /** Build a context, constructing the [PpiProjection] from raw viewport primitives. */
        fun create(
            center: ScreenPoint,
            radiusPx: Double,
            orientation: PpiOrientation,
            rangeScaleNm: Double,
            ownHeadingDeg: Double? = null,
            ownCourseDeg: Double? = null,
            targets: List<TrackedTarget> = emptyList(),
            selectThresholdPx: Double = DEFAULT_SELECT_THRESHOLD_PX,
        ): InteractionContext = InteractionContext(
            projection = PpiProjection.create(center, radiusPx, orientation, ownHeadingDeg, ownCourseDeg),
            rangeScaleNm = rangeScaleNm,
            ownHeadingDeg = ownHeadingDeg,
            targets = targets,
            selectThresholdPx = selectThresholdPx,
        )
    }
}
