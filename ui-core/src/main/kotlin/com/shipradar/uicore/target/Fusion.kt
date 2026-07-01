package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TrackedTarget
import kotlin.math.abs

/**
 * Default association gate — how close in position **and** motion a radar TT and an AIS target must be
 * to be considered one physical target (IEC 62388 §11.8.2 / IMO MSC.192(79) §5.30).
 *
 * The standard requires "harmonised criteria" based on position and motion, tested against the four
 * association scenarios of §11.8.2.3–6, but does **not** publish single numeric defaults — they are the
 * "radar's default values", manufacturer-set and validated by those scenarios, and the operator may vary
 * them by up to 300 % (§11.8.2.1). Disassociation uses a wider (hysteresis) gate to stop the
 * association state "hunting" (§11.8.2 test g).
 *
 * TODO(待标准): calibrate these defaults against IEC 62388 Tables 28–34 during acceptance and record the
 * chosen values in the test report; the figures below are reasonable starting points, not mandated.
 *
 * @property positionNm   Max separation (NM) to associate.
 * @property courseDeg    Max course difference (deg) to associate.
 * @property speedKn      Max speed difference (knots) to associate.
 * @property hysteresis   Multiplier (>1) applied to the gate once associated, widening it for the
 *                        disassociation test so a target near the boundary doesn't flicker.
 */
data class AssociationGate(
    val positionNm: Double = 0.5,
    val courseDeg: Double = 15.0,
    val speedKn: Double = 2.0,
    val hysteresis: Double = 1.5,
) {
    init {
        require(positionNm >= 0 && courseDeg >= 0 && speedKn >= 0) { "gate values must be >= 0" }
        require(hysteresis >= 1.0) { "hysteresis must be >= 1 (disassociation gate is wider)" }
    }
}

/** Which sensor "wins" the symbol/data for an associated pair (MSC.192(79) §5.30.1/.30.2). */
enum class FusionPriority {
    /** Default per §5.30.1: show the activated-AIS symbol and AIS data for associated targets. */
    AIS,
    /** §5.30.2: operator may switch the default to the tracked radar target. */
    RADAR_TT,
}

/** A radar-TT ↔ AIS correlation found by the association algorithm. */
data class TargetAssociation(
    val radarId: String,
    val aisId: String,
    val separationNm: Double,
    val courseDiffDeg: Double,
    val speedDiffKn: Double,
)

/**
 * Output of fusion.
 * @property fused        Deduplicated unified list — one entry per physical target. For an associated
 *                        pair the surviving entry is chosen by [FusionPriority]; unmatched targets pass
 *                        through unchanged (their source tag preserved per the task contract).
 * @property associations The radar↔AIS correlations that were collapsed.
 */
data class FusionResult(
    val fused: List<TrackedTarget>,
    val associations: List<TargetAssociation>,
)

/**
 * Radar-TT / AIS target association and de-duplication (IEC 62388 §11.8, IMO MSC.192(79) §5.30).
 *
 * §5.30: "avoid the presentation of two target symbols for the same physical target." When position and
 * motion criteria are met the pair is treated as one physical target; by default the AIS symbol/data are
 * selected (§5.30.1), switchable to radar (§5.30.2). If the two later diverge beyond the gate they are
 * shown as two distinct targets again and **no alert** is raised (§5.30.3).
 *
 * This is a stateless single-frame association (greedy nearest match under the gate). The hysteresis
 * field of [AssociationGate] is exposed for the stateful tracker (T-series tracking layer) that must
 * apply the wider disassociation gate to already-associated pairs across frames; carrying that state is
 * out of scope for this pure geometry layer. TODO(待标准): wire frame-to-frame hysteresis when the
 * tracking state machine is built, validating against IEC 62388 §11.8.2 scenarios 1 & 2.
 */
object TargetFusion {

    /** Distinguishes the two AIS source tags from radar TT. */
    private fun isAis(s: TargetSource) = s == TargetSource.AIS_ACTIVE || s == TargetSource.AIS_SLEEPING

    /** Smallest signed course difference, in [0, 180]. */
    internal fun courseDiff(a: Double, b: Double): Double {
        val d = abs(Geometry.normalizeDeg(a) - Geometry.normalizeDeg(b)) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** True if the radar TT and AIS target meet the position+motion gate for this own-ship state. */
    private fun matches(
        radar: TrackedTarget,
        ais: TrackedTarget,
        ownShip: OwnShipData,
        gate: AssociationGate,
        positionLimitNm: Double,
    ): TargetAssociation? {
        val rp = Geometry.relativePosition(radar, ownShip) ?: return null
        val ap = Geometry.relativePosition(ais, ownShip) ?: return null
        val sep = (rp - ap).norm()
        if (sep > positionLimitNm) return null

        // Motion gate: only applied when both carry course/speed. AIS always reports COG/SOG; a radar TT
        // mid-acquisition may not yet have a course, in which case position alone gates (§5.30 leaves the
        // exact criteria to the algorithm; position is the primary cue).
        val rc = radar.courseDeg; val ac = ais.courseDeg
        val rs = radar.speedKn; val asp = ais.speedKn
        var cDiff = 0.0
        var sDiff = 0.0
        if (rc != null && ac != null && rs != null && asp != null) {
            cDiff = courseDiff(rc, ac)
            sDiff = abs(rs - asp)
            if (cDiff > gate.courseDeg) return null
            if (sDiff > gate.speedKn) return null
        }
        return TargetAssociation(radar.id, ais.id, sep, cDiff, sDiff)
    }

    /**
     * Associate and de-duplicate. Radar TTs are matched greedily to their nearest eligible AIS target
     * (each AIS used at most once). For each match the loser's symbol is suppressed (§5.30):
     *   - [FusionPriority.AIS] (default): keep the AIS entry, drop the radar TT.
     *   - [FusionPriority.RADAR_TT]: keep the radar TT, drop the AIS entry.
     * Unmatched targets are passed through unchanged.
     */
    fun fuse(
        targets: List<TrackedTarget>,
        ownShip: OwnShipData,
        gate: AssociationGate = AssociationGate(),
        priority: FusionPriority = FusionPriority.AIS,
        retained: Set<Pair<String, String>> = emptySet(),
    ): FusionResult {
        val radars = targets.filter { it.source == TargetSource.RADAR_TT }
        val aisTargets = targets.filter { isAis(it.source) }

        val usedAis = HashSet<String>()
        val associations = ArrayList<TargetAssociation>()
        val droppedRadar = HashSet<String>()
        val droppedAis = HashSet<String>()

        for (radar in radars) {
            // nearest eligible, unused AIS target. A pair associated last frame ([retained]) keeps a WIDER
            // position gate (positionNm × hysteresis) before it is allowed to split — IEC 62388 §11.8.2
            // hysteresis, so a pair straddling the gate boundary doesn't flicker between one/two symbols.
            var best: TargetAssociation? = null
            var bestAis: TrackedTarget? = null
            for (ais in aisTargets) {
                if (ais.id in usedAis) continue
                val limit = if ((radar.id to ais.id) in retained) gate.positionNm * gate.hysteresis else gate.positionNm
                val m = matches(radar, ais, ownShip, gate, limit) ?: continue
                if (best == null || m.separationNm < best.separationNm) {
                    best = m; bestAis = ais
                }
            }
            if (best != null && bestAis != null) {
                usedAis += bestAis.id
                associations += best
                when (priority) {
                    FusionPriority.AIS -> droppedRadar += radar.id
                    FusionPriority.RADAR_TT -> droppedAis += bestAis.id
                }
            }
        }

        val fused = ArrayList<TrackedTarget>(targets.size)
        for (t in targets) {
            when {
                t.id in droppedRadar && t.source == TargetSource.RADAR_TT -> {} // suppressed
                t.id in droppedAis && isAis(t.source) -> {} // suppressed
                else -> fused += t // radar/AIS that wasn't suppressed, plus any other source, passes through
            }
        }
        return FusionResult(fused, associations)
    }
}

/**
 * Stateful frame-to-frame wrapper around [TargetFusion] that applies IEC 62388 §11.8.2 **hysteresis**:
 * a radar↔AIS pair associated on the previous frame is held together under the wider disassociation gate
 * (positionNm × hysteresis) until it clearly separates, so the display does not flicker between one and
 * two symbols for a pair sitting on the association-gate boundary (§11.8.2 scenarios 1 & 2).
 */
class AssociationTracker {
    private var retained: Set<Pair<String, String>> = emptySet()

    fun fuse(
        targets: List<TrackedTarget>,
        ownShip: OwnShipData,
        gate: AssociationGate = AssociationGate(),
        priority: FusionPriority = FusionPriority.AIS,
    ): FusionResult {
        val result = TargetFusion.fuse(targets, ownShip, gate, priority, retained)
        retained = result.associations.map { it.radarId to it.aisId }.toSet()
        return result
    }

    fun reset() { retained = emptySet() }
}
