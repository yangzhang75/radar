package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import kotlin.math.hypot

/**
 * Step 2 of the radar tracking pipeline — **tracking** (plots → tracks).
 *
 * A stateful scan-to-scan tracker that turns the per-scan [RadarPlot] lists from [PlotExtractor] into
 * stable [TrackedTarget]s with estimated course/speed — the ARPA core that lets CPA/TCPA and collision
 * alarms run on radar-derived targets (IMO A.823(19) / IEC 62388 §11 target tracking).
 *
 * Each [update] (one antenna revolution):
 *  1. **Predict** every live track forward by its velocity over the elapsed time.
 *  2. **Associate** incoming plots to predicted tracks by greedy nearest-neighbour within a range gate
 *     ([TrackerConfig.gateNm]) — closest pairs first, each plot/track used once.
 *  3. **Update** matched tracks with an **α-β filter** on position (smooths jitter, estimates velocity).
 *  4. **Initiate** a new tentative track from each unmatched plot.
 *  5. **Age** tracks: tentative → confirmed after [TrackerConfig.confirmHits] hits; a confirmed track that
 *     misses keeps **coasting** (predicted) until [TrackerConfig.maxCoastScans] misses, then it is dropped.
 *
 * Geometry is done in a **north-stabilised NE plane** (x = East, y = North, nautical miles) relative to
 * own ship. The filter estimates the target's *relative* velocity directly (which is what CPA/TCPA needs);
 * when own-ship course/speed are supplied, the reported [TrackedTarget.courseDeg]/[speedKn] are converted
 * to **true** motion (relative + own-ship velocity).
 *
 * Pure and deterministic given the (plots, dt) sequence — fully unit-testable without a real radar.
 */
data class TrackerConfig(
    /** Association gate radius (NM): a plot beyond this from a track's prediction can't update it. */
    val gateNm: Double = 0.5,
    /** Position smoothing gain (0..1) — higher trusts the new measurement more. */
    val alpha: Double = 0.5,
    /** Velocity smoothing gain (0..1). */
    val beta: Double = 0.3,
    /** Hits required for a tentative track to become confirmed (M-of-N initiation). */
    val confirmHits: Int = 3,
    /** Consecutive misses a confirmed track may coast before being dropped. */
    val maxCoastScans: Int = 3,
) {
    init {
        require(gateNm > 0) { "gateNm must be > 0" }
        require(alpha in 0.0..1.0 && beta in 0.0..1.0) { "alpha/beta must be in 0..1" }
        require(confirmHits >= 1 && maxCoastScans >= 0) { "invalid lifecycle counts" }
    }
}

class TrackManager(private val config: TrackerConfig = TrackerConfig()) {

    /** Internal mutable track state in the NE plane (NM / knots). */
    private class Track(
        val id: String,
        var pos: Vec2,
        var vel: Vec2,
        var hits: Int,
        var misses: Int,
        var confirmed: Boolean,
    )

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    /** Live track count (any non-dropped track), for tests/diagnostics. */
    val trackCount: Int get() = tracks.size

    /** Reset all state (e.g. on transmit restart or range change that invalidates the picture). */
    fun reset() {
        tracks.clear()
        nextId = 1
    }

    /**
     * Advance the tracker by one scan. [plots] are this revolution's detections, [dtSeconds] the time
     * since the previous update (> 0), [ownShip] optional for true-motion output. Returns the current
     * tracked targets (tentative tracks are reported with [TargetStatus.ACQUIRING], confirmed with
     * [TargetStatus.TRACKED]).
     */
    fun update(plots: List<RadarPlot>, dtSeconds: Double, ownShip: OwnShipData? = null): List<TrackedTarget> {
        val dtH = dtSeconds / 3600.0

        // 1) predict.
        for (t in tracks) t.pos = t.pos + t.vel * dtH

        // 2) associate: greedy nearest-neighbour within the gate.
        val measurements = plots.map { it to Vec2.ofBearing(it.trueBearingDeg, it.rangeNm) }
        val candidates = ArrayList<Triple<Double, Int, Int>>() // (dist, trackIdx, plotIdx)
        for (ti in tracks.indices) {
            for (pi in measurements.indices) {
                val d = (tracks[ti].pos - measurements[pi].second).norm()
                if (d <= config.gateNm) candidates.add(Triple(d, ti, pi))
            }
        }
        candidates.sortBy { it.first }
        val trackUsed = BooleanArray(tracks.size)
        val plotUsed = BooleanArray(measurements.size)
        val matchedPlotForTrack = HashMap<Int, Int>()
        for ((_, ti, pi) in candidates) {
            if (trackUsed[ti] || plotUsed[pi]) continue
            trackUsed[ti] = true
            plotUsed[pi] = true
            matchedPlotForTrack[ti] = pi
        }

        // 3) update matched tracks with the α-β filter; coast the unmatched.
        for (ti in tracks.indices) {
            val t = tracks[ti]
            val pi = matchedPlotForTrack[ti]
            if (pi != null) {
                val z = measurements[pi].second
                val residual = z - t.pos // t.pos is the prediction
                t.pos = t.pos + residual * config.alpha
                if (dtH > 0.0) t.vel = t.vel + residual * (config.beta / dtH)
                t.hits++
                t.misses = 0
                if (t.hits >= config.confirmHits) t.confirmed = true
            } else {
                t.misses++
            }
        }

        // 4) initiate new tentative tracks from unmatched plots.
        for (pi in measurements.indices) {
            if (plotUsed[pi]) continue
            tracks.add(
                Track(id = "T${nextId++}", pos = measurements[pi].second, vel = Vec2(0.0, 0.0), hits = 1, misses = 0, confirmed = false),
            )
        }

        // 5) drop tracks that have coasted too long.
        tracks.removeAll { it.misses > config.maxCoastScans }

        // emit.
        val ownVel = ownShip?.let { Geometry.ownVelocity(it) }
        return tracks.map { t -> toTarget(t, ownVel) }
    }

    /** Convert internal NE-plane state to a [TrackedTarget] (true bearing; true course/speed if own vel known). */
    private fun toTarget(t: Track, ownVel: Vec2?): TrackedTarget {
        val rangeNm = t.pos.norm()
        val bearing = Geometry.bearingOf(t.pos)
        // relative velocity from the filter; true velocity = relative + own-ship velocity.
        val trueVel = if (ownVel != null) t.vel + ownVel else t.vel
        val speed = hypot(trueVel.x, trueVel.y)
        // only report course/speed once a velocity estimate exists (≥2 updates) and motion is non-trivial.
        val hasMotion = t.hits >= 2 && speed >= 0.1
        return TrackedTarget(
            id = t.id,
            source = TargetSource.RADAR_TT,
            rangeNm = rangeNm,
            bearingDeg = bearing,
            trueBearing = true,
            courseDeg = if (hasMotion) Geometry.bearingOf(trueVel) else null,
            speedKn = if (hasMotion) speed else null,
            status = if (t.confirmed) TargetStatus.TRACKED else TargetStatus.ACQUIRING,
        )
    }
}
