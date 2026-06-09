package com.shipradar.comms.service

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.SensorKind
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TrackedTarget

/**
 * Field-wise merge of the PARTIAL [OwnShipData] snapshots that each 61162 sentence yields
 * (HDT carries heading only, GGA position only, VTG cog/sog…) into one cumulative own-ship state.
 *
 * Each field keeps its last non-null value; [OwnShipData.headingTrue] is only adopted when the update
 * actually carries a heading. Per-sensor validity flags accumulate (latest wins per key).
 *
 * Boundary: the contract ([com.shipradar.comms.iec61162.ParsedSentence]) documents fusion as the
 * sync stage's job; T1.6a delivered the timing primitives ([com.shipradar.comms.sync.MultiRateAligner]
 * etc.) but not a sentence merger, so this minimal merge lives here. Geometry-dependent fusion
 * (AIS lat/lon → own-ship-relative range/bearing) stays out — that needs ui-core geometry.
 */
class OwnShipFusion {
    private var cur = OwnShipData()

    fun merge(update: OwnShipData): OwnShipData {
        cur = cur.copy(
            utcMillis = update.utcMillis ?: cur.utcMillis,
            latitude = update.latitude ?: cur.latitude,
            longitude = update.longitude ?: cur.longitude,
            headingDeg = update.headingDeg ?: cur.headingDeg,
            headingTrue = if (update.headingDeg != null) update.headingTrue else cur.headingTrue,
            cogDeg = update.cogDeg ?: cur.cogDeg,
            sogKn = update.sogKn ?: cur.sogKn,
            rotDegMin = update.rotDegMin ?: cur.rotDegMin,
            sourceValidity = cur.sourceValidity + update.sourceValidity,
        )
        return cur
    }

    fun current(): OwnShipData = cur
}

/**
 * Maintains the unified tracked-target list from two sources that update differently:
 *  - HALO target channel (236.6.7.18) delivers a full radar-TT **snapshot** each message → it replaces
 *    all radar-sourced entries ([replaceRadarSnapshot]);
 *  - 61162 TTM sentences deliver **one** target at a time → upsert by id ([upsert]).
 * AIS targets are not added here (they need own-ship-relative geometry — deferred to T1.6/ui-core).
 *
 * Insertion order is preserved for stable rendering.
 */
class TargetAggregator {
    private val byId = LinkedHashMap<String, TrackedTarget>()

    fun replaceRadarSnapshot(snapshot: List<TrackedTarget>): List<TrackedTarget> {
        byId.values.removeAll { it.source == TargetSource.RADAR_TT }
        for (t in snapshot) byId[t.id] = t
        return snapshot()
    }

    fun upsert(target: TrackedTarget): List<TrackedTarget> {
        byId[target.id] = target
        return snapshot()
    }

    fun snapshot(): List<TrackedTarget> = byId.values.toList()
}
