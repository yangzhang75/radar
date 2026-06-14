package com.shipradar.app.viewctl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Hoistable UI state for off-centre (look-ahead) display and True-Motion reset.
 *
 * Holds the current [ViewOffset] and the off-centre on/off switch. The PPI render layer reads
 * [effectiveOffset] and applies it to the projection centre — see [ViewOffset] KDoc for the exact
 * formula. This class owns no Android graphics; it is plain Compose snapshot state.
 *
 * Standards: IEC 62388 §10.4 (off-centring) and §10.4.3 (True-Motion automatic reset).
 */
@Stable
class ViewControlState(
    offCenterEnabled: Boolean = false,
    offset: ViewOffset = ViewOffset.CENTERED,
) {
    /** Whether off-centring is active. When false the PPI is centred regardless of [offset]. */
    var offCenterEnabled by mutableStateOf(offCenterEnabled)
        private set

    /** The raw (stored) offset; only applied when [offCenterEnabled]. Always within the §10.4 limit. */
    var offset by mutableStateOf(offset.clampedToLimit())
        private set

    /** The offset the PPI should actually apply: [ViewOffset.CENTERED] unless off-centre is on. */
    val effectiveOffset: ViewOffset
        get() = if (offCenterEnabled) offset else ViewOffset.CENTERED

    /** True when the displayed picture is off-centred. */
    val isOffCenter: Boolean get() = effectiveOffset.isOffCenter

    /** Toggle off-centring. The stored [offset] is kept so it can be restored when re-enabled. */
    fun setOffCenter(enabled: Boolean) {
        offCenterEnabled = enabled
    }

    /**
     * Nudge the own-ship/CCRP position by a normalised step in screen space (+x right, +y down),
     * clamped to the §10.4.2.1 limit. No-op when off-centring is disabled.
     */
    fun nudge(dx: Float, dy: Float) {
        if (offCenterEnabled) offset = offset.offsetBy(dx, dy)
    }

    /** Manual recenter (回中): own-ship back to the centre of the bearing scale (IEC 62388 §10.4.2.1). */
    fun reset() {
        offset = ViewOffset.CENTERED
    }

    /**
     * True-Motion automatic reset (IEC 62388 §10.4.3.1): reposition own-ship for the maximum view
     * ahead and ensure off-centring is on. Call when own-ship drifts to the reset trigger
     * (position- or time-based) or for the mandated early reset.
     *
     * @param aheadScreenAngleDeg screen angle of "ahead" (0° = up for head-up/course-up; pass the
     *   heading for north-up). See [ViewOffset.tmLookAheadReset].
     */
    fun tmLookAheadReset(aheadScreenAngleDeg: Float = 0f) {
        offset = ViewOffset.tmLookAheadReset(aheadScreenAngleDeg)
        offCenterEnabled = true
    }

    companion object {
        /** Normalised step per nudge-button press (fraction of operational radius). */
        const val NUDGE_STEP = 0.1f
    }
}

/** Remember a [ViewControlState] across recompositions. */
@Composable
fun rememberViewControlState(
    offCenterEnabled: Boolean = false,
    offset: ViewOffset = ViewOffset.CENTERED,
): ViewControlState = remember { ViewControlState(offCenterEnabled, offset) }
