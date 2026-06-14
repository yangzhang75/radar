package com.shipradar.app.tracks

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * One past-position mark for a target: a timestamp plus the target's position relative to own ship,
 * stored **north-referenced** (true bearing + range). North-referencing is what makes the mark
 * azimuth-stabilised — the render layer converts it to a bow-relative angle with the *current* heading
 * so the trail is fixed in the north frame regardless of own-ship turns (NORTH_UP/COURSE_UP), while
 * HEAD_UP correctly smears it on a turn.
 *
 * @param timestampMs sample time (monotonic-ish wall clock supplied by the caller).
 * @param trueBearingDeg target bearing from own ship, degrees, referenced to true north [0,360).
 * @param rangeNm target range from own ship, nautical miles.
 */
data class TrackPoint(val timestampMs: Long, val trueBearingDeg: Double, val rangeNm: Double)

/**
 * W7-C — per-target ring buffer of equally time-spaced past positions (IEC 62388 §11.2).
 *
 * These are **relative past positions** (the target's past position relative to own ship, held in a
 * north-referenced frame). Per §11.2.2.2(g) relative past positions are unaffected by ground/sea
 * stabilisation, which is why this self-contained implementation chooses the relative mode — *true*
 * past positions (§11.2.2.2 h) would additionally need own-ship EP / set-and-drift integration and
 * are out of scope here (noted in the delivery report).
 *
 * Not thread-safe: call [sample] / [snapshot] from a single thread (the Compose frame / effect).
 *
 * @param config sampling interval + total plot time + capacity, from [TracksConfig].
 */
class TrackHistory(private val config: TracksConfig) {

    private val byId = LinkedHashMap<String, ArrayDeque<TrackPoint>>()
    private val lastSampleMs = HashMap<String, Long>()

    /**
     * Record a past position for each [targets] entry **iff** at least [TracksConfig.sampleIntervalMs]
     * has elapsed since that target's previous sample (equal spacing — denser calls are ignored), then
     * drop marks older than [TracksConfig.totalMillis]. When the config is disabled the history is
     * cleared. Targets that stop appearing keep their marks until they age out (a lost target's trail
     * fades), then are removed.
     *
     * @param ownShip current own-ship state; [OwnShipData.headingDeg] converts a bow-relative target
     *   bearing to true. If heading is null, a relative target's bearing is stored as-is (degraded
     *   head-up; documented).
     * @param nowMs current time in ms.
     */
    fun sample(targets: List<TrackedTarget>, ownShip: OwnShipData, nowMs: Long) {
        if (!config.enabled) {
            clear()
            return
        }
        val interval = config.sampleIntervalMs
        val heading = ownShip.headingDeg
        for (t in targets) {
            val last = lastSampleMs[t.id]
            if (last != null && nowMs - last < interval) continue // too dense — keep equal spacing
            val deque = byId.getOrPut(t.id) { ArrayDeque() }
            deque.addLast(TrackPoint(nowMs, trueBearingOf(t, heading), t.rangeNm))
            lastSampleMs[t.id] = nowMs
            while (deque.size > config.capacityPerTarget) deque.removeFirst() // capacity backstop
        }
        prune(nowMs)
    }

    /** Drop marks older than the plot time and forget targets whose history has fully aged out. */
    fun prune(nowMs: Long) {
        val maxAge = config.totalMillis
        val it = byId.entries.iterator()
        while (it.hasNext()) {
            val (id, dq) = it.next()
            while (dq.isNotEmpty() && nowMs - dq.first().timestampMs > maxAge) dq.removeFirst()
            if (dq.isEmpty()) {
                it.remove()
                lastSampleMs.remove(id)
            }
        }
    }

    /** Immutable per-target snapshot (oldest → newest) for the render layer. */
    fun snapshot(): Map<String, List<TrackPoint>> =
        byId.mapValues { (_, dq) -> dq.toList() }

    /** Marks for one target (oldest → newest), or empty. */
    fun pointsFor(id: String): List<TrackPoint> = byId[id]?.toList() ?: emptyList()

    fun clear() {
        byId.clear()
        lastSampleMs.clear()
    }

    /** Convert a target's bearing to true (north) using [headingDeg] when the bearing is bow-relative. */
    private fun trueBearingOf(t: TrackedTarget, headingDeg: Double?): Double =
        if (t.trueBearing || headingDeg == null) normalizeDeg(t.bearingDeg)
        else normalizeDeg(t.bearingDeg + headingDeg)

    private fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}
