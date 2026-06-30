package com.shipradar.app.input

import com.shipradar.contract.TrackCommand
import com.shipradar.uicore.ppi.RangeModel
import com.shipradar.uicore.ppi.ScreenPoint
import com.shipradar.util.Angles
import kotlin.math.hypot

/**
 * The **single source of interaction behaviour** for the PPI. Every operation is a pure function of
 * `(model, context, inputClass)` returning the next [InteractionModel] plus the [InteractionEvent]s
 * to emit. It has NO Android / Compose dependency.
 *
 * Why pure & class-agnostic: CAT 1 requires touch, keyboard and mouse to be equivalent. The three
 * [RadarInputLayer] adapters translate their device events into calls on THIS object, so a given
 * logical operation (e.g. "zoom in") produces an identical model+event no matter which class
 * triggered it — the equivalence is structural, not duplicated per class.
 *
 * Geometry & limits are reused from `ui-core` (`RangeModel`, `PpiProjection`) and grounded in
 * IEC 62388 §9 / §10.4 (see [InteractionModel] for the per-feature clause map).
 */
object RadarInteractionController {

    /** Result of an operation: the next model and any outward events. */
    data class Update(val model: InteractionModel, val events: List<InteractionEvent> = emptyList())

    /** Keyboard/step increments tuned to the standards' accuracy/resolution requirements. */
    object Step {
        /** EBL step, deg — within the ±0.5° settability of IEC 62388 §9.6.2.1. */
        const val EBL_DEG = 0.5
        /** VRM step, NM — matches the 0.01 NM resolution of IEC 62388 §9.5.2.1. */
        const val VRM_NM = 0.01
        /** PI bearing step, deg. */
        const val PI_DEG = 0.5
        /** PI range step, NM. */
        const val PI_NM = 0.01
        /** Pan step as a fraction of the operational radius (keyboard nudge). */
        const val PAN_FRACTION = 0.05
    }

    // ---- Zoom: range-scale stepping (IEC 62388 §9.4.1) ----------------------------------------

    /** Zoom in = step to the next shorter mandatory range scale. VRM/PI ranges are retained. */
    fun zoomIn(model: InteractionModel, ctx: InteractionContext, ic: InputClass): Update {
        val next = RangeModel.previousRangeScale(ctx.rangeScaleNm)
        if (next == ctx.rangeScaleNm) return Update(model)
        return Update(model, listOf(InteractionEvent.RangeScaleChangeRequested(next, ic)))
    }

    /** Zoom out = step to the next longer mandatory range scale. */
    fun zoomOut(model: InteractionModel, ctx: InteractionContext, ic: InputClass): Update {
        val next = RangeModel.nextRangeScale(ctx.rangeScaleNm)
        if (next == ctx.rangeScaleNm) return Update(model)
        return Update(model, listOf(InteractionEvent.RangeScaleChangeRequested(next, ic)))
    }

    // ---- Pan: manual off-centring (IEC 62388 §10.4.2.1, ≤50 % of radius) ----------------------

    /** Pan by a delta expressed as fractions of the operational radius. */
    fun panBy(model: InteractionModel, dxFraction: Double, dyFraction: Double, ic: InputClass): Update {
        val limit = OffCenter.MAX_MANUAL_OFFSET_FRACTION
        val moved = OffCenter(
            xFraction = (model.offCenter.xFraction + dxFraction).coerceIn(-limit, limit),
            yFraction = (model.offCenter.yFraction + dyFraction).coerceIn(-limit, limit),
        )
        if (moved == model.offCenter) return Update(model)
        val next = model.copy(offCenter = moved)
        return Update(next, listOf(InteractionEvent.OffCenterChanged(moved, ic)))
    }

    /** Pan by a pixel delta (converted to radius fractions via the current projection). */
    fun panByPixels(
        model: InteractionModel, dxPx: Double, dyPx: Double, ctx: InteractionContext, ic: InputClass,
    ): Update {
        val r = ctx.projection.radiusPx
        if (r <= 0.0) return Update(model)
        return panBy(model, dxPx / r, dyPx / r, ic)
    }

    /** Reset off-centring to the CCRP-at-centre position by a single operator action (§10.4.3). */
    fun recenter(model: InteractionModel, ic: InputClass): Update {
        if (model.offCenter == OffCenter.CENTERED) return Update(model)
        val next = model.copy(offCenter = OffCenter.CENTERED)
        return Update(next, listOf(InteractionEvent.OffCenterChanged(OffCenter.CENTERED, ic)))
    }

    // ---- Cursor selection / acquisition (IEC 62388 §9.7.3, §6 acquisition) --------------------

    /**
     * Select the nearest target within [InteractionContext.selectThresholdPx] of [point], or
     * de-select if none is close enough (§9.7.3.1 select/de-select by cursor).
     */
    fun selectAt(
        model: InteractionModel, point: ScreenPoint, ctx: InteractionContext, ic: InputClass,
    ): Update {
        val hit = nearestTargetId(point, ctx)
        if (hit == model.selectedTargetId) return Update(model)
        val next = model.copy(selectedTargetId = hit)
        return Update(next, listOf(InteractionEvent.TargetSelectionChanged(hit, ic)))
    }

    /**
     * Manually acquire a target at the designated [point]: convert to range/bearing about the CCRP
     * and emit a [TrackCommand.Acquire]. Bearing is bow-relative (matches the spoke azimuth frame).
     * The model is unchanged — acquisition is an outward command; tracking state lives downstream.
     */
    fun acquireAt(model: InteractionModel, point: ScreenPoint, ctx: InteractionContext, ic: InputClass): Update {
        val polar = ctx.projection.screenToPolar(point)
        val rangeNm = RangeModel.fractionToRangeNm(polar.rangeFraction, ctx.rangeScaleNm)
        val bearingBowRel = Angles.normalizeDeg(polar.azimuthDeg)
        return Update(
            model,
            listOf(
                InteractionEvent.AcquireTargetRequested(
                    command = TrackCommand.Acquire(rangeNm = rangeNm, bearingDeg = bearingBowRel),
                    trueBearing = false,
                    inputClass = ic,
                ),
            ),
        )
    }

    /** Cancel tracking of the currently-selected target (§ target tracking control). */
    fun cancelSelected(model: InteractionModel, ic: InputClass): Update {
        val id = model.selectedTargetId ?: return Update(model)
        return Update(
            model.copy(selectedTargetId = null),
            listOf(
                InteractionEvent.CancelTargetRequested(TrackCommand.Cancel(id), ic),
                InteractionEvent.TargetSelectionChanged(null, ic),
            ),
        )
    }

    // ---- EBL — electronic bearing line (IEC 62388 §9.6) ---------------------------------------

    fun toggleEbl(model: InteractionModel, index: Int, ic: InputClass): Update {
        val ebl = model.ebls.getOrNull(index) ?: return Update(model)
        val nowOn = !ebl.enabled
        val ebls = model.ebls.replaceAt(index, ebl.copy(enabled = nowOn))
        val tool = if (nowOn) ActiveTool.EblTool(index) else clearTool(model.activeTool, ActiveTool.EblTool(index))
        return Update(model.copy(ebls = ebls, activeTool = tool))
    }

    fun setEblReference(model: InteractionModel, index: Int, reference: BearingReference): Update {
        val ebl = model.ebls.getOrNull(index) ?: return Update(model)
        return Update(model.copy(ebls = model.ebls.replaceAt(index, ebl.copy(reference = reference))))
    }

    fun nudgeEbl(model: InteractionModel, index: Int, deltaDeg: Double): Update {
        val ebl = model.ebls.getOrNull(index) ?: return Update(model)
        val b = Angles.normalizeDeg(ebl.bearingDeg + deltaDeg)
        return Update(model.copy(ebls = model.ebls.replaceAt(index, ebl.copy(bearingDeg = b, enabled = true))))
    }

    /** Drag/point an EBL at a screen position; stores the bearing in the EBL's reference frame. */
    fun setEblFromPoint(
        model: InteractionModel, index: Int, point: ScreenPoint, ctx: InteractionContext,
    ): Update {
        val ebl = model.ebls.getOrNull(index) ?: return Update(model)
        val bowRel = Angles.normalizeDeg(ctx.projection.screenToPolar(point).azimuthDeg)
        val bearing = when (ebl.reference) {
            BearingReference.RELATIVE -> bowRel
            BearingReference.TRUE -> Angles.normalizeDeg(bowRel + (ctx.ownHeadingDeg ?: 0.0))
        }
        return Update(model.copy(ebls = model.ebls.replaceAt(index, ebl.copy(bearingDeg = bearing, enabled = true))))
    }

    // ---- VRM — variable range marker (IEC 62388 §9.5) -----------------------------------------

    fun toggleVrm(model: InteractionModel, index: Int, ic: InputClass): Update {
        val vrm = model.vrms.getOrNull(index) ?: return Update(model)
        val nowOn = !vrm.enabled
        val vrms = model.vrms.replaceAt(index, vrm.copy(enabled = nowOn))
        val tool = if (nowOn) ActiveTool.VrmTool(index) else clearTool(model.activeTool, ActiveTool.VrmTool(index))
        return Update(model.copy(vrms = vrms, activeTool = tool))
    }

    fun nudgeVrm(model: InteractionModel, index: Int, deltaNm: Double): Update {
        val vrm = model.vrms.getOrNull(index) ?: return Update(model)
        val r = (vrm.rangeNm + deltaNm).coerceAtLeast(0.0)
        return Update(model.copy(vrms = model.vrms.replaceAt(index, vrm.copy(rangeNm = r, enabled = true))))
    }

    /** Drag a VRM to a screen position; range is the cursor distance from the CCRP. */
    fun setVrmFromPoint(
        model: InteractionModel, index: Int, point: ScreenPoint, ctx: InteractionContext,
    ): Update {
        val vrm = model.vrms.getOrNull(index) ?: return Update(model)
        val rangeNm = RangeModel.fractionToRangeNm(ctx.projection.screenToPolar(point).rangeFraction, ctx.rangeScaleNm)
        return Update(model.copy(vrms = model.vrms.replaceAt(index, vrm.copy(rangeNm = rangeNm, enabled = true))))
    }

    // ---- PI — parallel index lines (IEC 62388 §9.9) -------------------------------------------

    fun togglePi(model: InteractionModel, index: Int, ic: InputClass): Update {
        val pi = model.piLines.getOrNull(index) ?: return Update(model)
        val nowOn = !pi.enabled
        val lines = model.piLines.replaceAt(index, pi.copy(enabled = nowOn))
        val tool = if (nowOn) ActiveTool.PiTool(index) else clearTool(model.activeTool, ActiveTool.PiTool(index))
        return Update(model.copy(piLines = lines, activeTool = tool))
    }

    /** §9.9.2.1: turn on/off all PI lines as a group. */
    fun togglePiGroup(model: InteractionModel): Update =
        Update(model.copy(piGroupVisible = !model.piGroupVisible))

    fun setPiBearing(model: InteractionModel, index: Int, trueBearingDeg: Double): Update {
        val pi = model.piLines.getOrNull(index) ?: return Update(model)
        return Update(model.copy(piLines = model.piLines.replaceAt(index, pi.copy(bearingDeg = Angles.normalizeDeg(trueBearingDeg)))))
    }

    fun setPiRange(model: InteractionModel, index: Int, rangeNm: Double): Update {
        val pi = model.piLines.getOrNull(index) ?: return Update(model)
        return Update(model.copy(piLines = model.piLines.replaceAt(index, pi.copy(rangeNm = rangeNm.coerceAtLeast(0.0)))))
    }

    fun truncatePi(model: InteractionModel, index: Int, truncateNm: Double?): Update {
        val pi = model.piLines.getOrNull(index) ?: return Update(model)
        return Update(model.copy(piLines = model.piLines.replaceAt(index, pi.copy(truncateNm = truncateNm))))
    }

    /** §9.9.2.1: reset an individual index line parallel to own-ship heading by a simple action. */
    fun resetPiToHeading(model: InteractionModel, index: Int, ctx: InteractionContext): Update {
        val pi = model.piLines.getOrNull(index) ?: return Update(model)
        val heading = ctx.ownHeadingDeg ?: return Update(model)
        return Update(model.copy(piLines = model.piLines.replaceAt(index, pi.copy(bearingDeg = Angles.normalizeDeg(heading)))))
    }

    // ---- active-tool drag dispatch & cursor readout -------------------------------------------

    fun setActiveTool(model: InteractionModel, tool: ActiveTool): Update =
        Update(model.copy(activeTool = tool))

    /** Apply a drag at [point] to whichever tool is active (EBL bearing / VRM range / PI line). */
    fun dragActiveTool(
        model: InteractionModel, point: ScreenPoint, ctx: InteractionContext, ic: InputClass,
    ): Update = when (val t = model.activeTool) {
        is ActiveTool.EblTool -> setEblFromPoint(model, t.index, point, ctx)
        is ActiveTool.VrmTool -> setVrmFromPoint(model, t.index, point, ctx)
        is ActiveTool.PiTool -> setPiFromPoint(model, t.index, point, ctx)
        else -> Update(model)
    }

    /** Set a PI line's true bearing and perpendicular range from a designated point. */
    fun setPiFromPoint(
        model: InteractionModel, index: Int, point: ScreenPoint, ctx: InteractionContext,
    ): Update {
        val pi = model.piLines.getOrNull(index) ?: return Update(model)
        val polar = ctx.projection.screenToPolar(point)
        val rangeNm = RangeModel.fractionToRangeNm(polar.rangeFraction, ctx.rangeScaleNm)
        // The PI line direction is a true bearing; the point's bow-relative azimuth + heading.
        val trueBearing = Angles.normalizeDeg(polar.azimuthDeg + (ctx.ownHeadingDeg ?: 0.0))
        return Update(model.copy(piLines = model.piLines.replaceAt(index, pi.copy(bearingDeg = trueBearing, rangeNm = rangeNm, enabled = true))))
    }

    /** Update the numerical cursor readout (§9.7.2.1) for the current pointer position. */
    fun updateCursorReadout(model: InteractionModel, point: ScreenPoint, ctx: InteractionContext): Update {
        val polar = ctx.projection.screenToPolar(point)
        val rangeNm = RangeModel.fractionToRangeNm(polar.rangeFraction, ctx.rangeScaleNm)
        val readout = CursorReadout(
            rangeNm = rangeNm,
            bearingDeg = Angles.normalizeDeg(polar.azimuthDeg + (ctx.ownHeadingDeg ?: 0.0)),
            reference = if (ctx.ownHeadingDeg != null) BearingReference.TRUE else BearingReference.RELATIVE,
        )
        return Update(model.copy(cursorReadout = readout))
    }

    // ---- internal helpers ---------------------------------------------------------------------

    /** Nearest target id within the pick threshold of [point], or null. */
    private fun nearestTargetId(point: ScreenPoint, ctx: InteractionContext): String? {
        var bestId: String? = null
        var bestD = ctx.selectThresholdPx
        for (t in ctx.targets) {
            val bowRel = if (t.trueBearing) Angles.normalizeDeg(t.bearingDeg - (ctx.ownHeadingDeg ?: 0.0)) else t.bearingDeg
            val frac = RangeModel.rangeNmToFraction(t.rangeNm, ctx.rangeScaleNm)
            val sp = ctx.projection.polarToScreen(bowRel, frac)
            val d = hypot(sp.x - point.x, sp.y - point.y)
            if (d <= bestD) {
                bestD = d
                bestId = t.id
            }
        }
        return bestId
    }

    /**
     * Snap EBL#0 / VRM#0 onto the nearest tracked target to their current intersection (measurement aid).
     * The EBL bearing is matched in its own reference (true/relative via [InteractionContext.ownHeadingDeg]);
     * both tools are enabled. No-op (model unchanged) when no target lies within [gateNm].
     */
    fun snapEblVrm(model: InteractionModel, ctx: InteractionContext, ic: InputClass, gateNm: Double = 1.0): Update {
        val ebl = model.ebls.firstOrNull() ?: return Update(model)
        val vrm = model.vrms.firstOrNull() ?: return Update(model)
        val hdg = ctx.ownHeadingDeg ?: 0.0
        val refTrue = if (ebl.reference == BearingReference.TRUE) ebl.bearingDeg else ebl.bearingDeg + hdg
        val target = TargetSnap.nearestTo(ctx.targets, refTrue, vrm.rangeNm, gateNm) ?: return Update(model)
        val newEblBearing = Angles.normalizeDeg(
            if (ebl.reference == BearingReference.TRUE) target.bearingDeg else target.bearingDeg - hdg,
        )
        val next = model.copy(
            ebls = model.ebls.replaceAt(0, ebl.copy(enabled = true, bearingDeg = newEblBearing)),
            vrms = model.vrms.replaceAt(0, vrm.copy(enabled = true, rangeNm = target.rangeNm)),
        )
        if (next == model) return Update(model)
        return Update(next)
    }

    private fun clearTool(current: ActiveTool, ifMatches: ActiveTool): ActiveTool =
        if (current == ifMatches) ActiveTool.None else current

    private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
        toMutableList().also { it[index] = value }
}
