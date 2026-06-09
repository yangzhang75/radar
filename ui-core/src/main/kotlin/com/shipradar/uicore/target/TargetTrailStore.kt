package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * Accumulates **past positions** for targets across successive update frames (IEC 62288 def 3.33 /
 * MSC191/6.4.5.8; A.823 §3.3.5 "at least four equally time-spaced past positions"). Pure, no platform
 * types — the Compose layer calls [record] once per [com.shipradar.contract.RadarDataBus.targets]
 * emission and passes [snapshot] to [OverlayProjector].
 *
 * Positions are stored as **relative** NE offsets (NM, own ship at origin) — i.e. *relative* trails,
 * the natural form for a relative-motion PPI where own ship is fixed at centre. Equal time spacing
 * relies on the upstream tracker's steady update cadence (one [record] per plot interval).
 *
 * TODO(待标准 A.823 §3.3.5): *true* (ground/sea-referenced) trails additionally need own-ship position
 * history to re-reference past relative points; add when the true-trail mode is wired (own-ship track).
 *
 * @param maxPoints Past positions retained per target (default 4 — the A.823 §3.3.5 minimum).
 */
class TargetTrailStore(private val maxPoints: Int = 4) {

    init { require(maxPoints >= 1) { "maxPoints must be >= 1" } }

    // Insertion order preserved; oldest entries pruned when a target disappears.
    private val history = LinkedHashMap<String, ArrayDeque<Vec2>>()

    /**
     * Append the current relative position of every target in [targets], dropping the oldest sample
     * per target beyond [maxPoints]. Targets absent from [targets] are forgotten (their track ended —
     * e.g. lost/out-of-range), so the map cannot grow without bound.
     */
    fun record(targets: List<TrackedTarget>, ownShip: OwnShipData) {
        val present = HashSet<String>(targets.size)
        for (t in targets) {
            val pos = Geometry.relativePosition(t, ownShip) ?: continue
            present += t.id
            val dq = history.getOrPut(t.id) { ArrayDeque() }
            dq.addLast(pos)
            while (dq.size > maxPoints) dq.removeFirst()
        }
        history.keys.retainAll(present)
    }

    /** Past positions per target id, oldest → newest (current position included as the last sample). */
    fun snapshot(): Map<String, List<Vec2>> = history.mapValues { it.value.toList() }

    /** Past positions for one target, oldest → newest, or empty if none recorded. */
    fun trailOf(id: String): List<Vec2> = history[id]?.toList() ?: emptyList()

    fun clear() = history.clear()
}
