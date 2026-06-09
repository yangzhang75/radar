package com.shipradar.app.input

import com.shipradar.contract.TrackCommand

/**
 * Semantic events the interaction layer emits **outward** for the PPI render layer, the target
 * overlay, and the radar controller to consume. The input layer never reaches into those packages
 * directly (task T2.5: "输出手势/选择事件供 PPI 与目标层消费，经回调/状态，不直接改它们的包") — it only
 * raises these events through a single callback and owns its own overlay graphics.
 *
 * [inputClass] records which of the three CAT 1 input classes produced the event; the event itself
 * is identical regardless of class (the equivalence requirement).
 */
sealed interface InteractionEvent {
    val inputClass: InputClass

    /**
     * The operator requested a different range scale (zoom). [newScaleNm] is always one of the
     * mandatory range scales (IEC 62388 §9.4.1). The control panel (T2.6) turns this into a
     * [com.shipradar.contract.RadarCommand.SetRange]; the PPI re-projects.
     */
    data class RangeScaleChangeRequested(
        val newScaleNm: Double,
        override val inputClass: InputClass,
    ) : InteractionEvent

    /** The CCRP off-centre (pan) changed; the PPI re-centres its projection (§10.4 off-centring). */
    data class OffCenterChanged(
        val offCenter: OffCenter,
        override val inputClass: InputClass,
    ) : InteractionEvent

    /** A target was selected (or null to de-select) via the cursor (§9.7.3.1). */
    data class TargetSelectionChanged(
        val targetId: String?,
        override val inputClass: InputClass,
    ) : InteractionEvent

    /**
     * Manual acquisition of a radar target at a screen position the operator designated
     * (range/bearing relative to the CCRP). Maps to [TrackCommand.Acquire]; the bearing is
     * bow-relative unless [trueBearing] is set. (IEC 62388 §6 target acquisition; cursor §9.7.3.)
     */
    data class AcquireTargetRequested(
        val command: TrackCommand.Acquire,
        val trueBearing: Boolean,
        override val inputClass: InputClass,
    ) : InteractionEvent

    /** Cancel tracking of the selected target. Maps to [TrackCommand.Cancel]. */
    data class CancelTargetRequested(
        val command: TrackCommand.Cancel,
        override val inputClass: InputClass,
    ) : InteractionEvent
}
