package com.shipradar.uicore.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * Operator-settable CPA/TCPA limits for the dangerous-target ("close-quarters") warning.
 *
 * A.823(19) §3.5.2: the ARPA shall warn of any tracked target predicted to close within "a minimum
 * range and time **chosen by the observer**". The standard deliberately fixes **no numeric default** —
 * the limits are operator-configurable (see also GB 11711-2002 §… CPA/TCPA 报警, and IEC 62388 §11.6
 * which surfaces this as alarm/CPA-TCPA functionality driving BAM id 3044).
 *
 * The defaults below are common bridge values, **not** mandated figures; they are starting points the
 * operator overrides. TODO(待标准): if the target class society / GB acceptance test fixes a specific
 * default safe-CPA/TCPA, replace these and cite the exact clause.
 *
 * @property safeCpaNm   Minimum acceptable CPA (NM). A predicted CPA at or below this is unsafe.
 * @property safeTcpaSec Maximum lead time (s) within which a CPA breach matters.
 */
data class DangerCriteria(
    val safeCpaNm: Double = DEFAULT_SAFE_CPA_NM,
    val safeTcpaSec: Double = DEFAULT_SAFE_TCPA_SEC,
) {
    init {
        require(safeCpaNm >= 0) { "safeCpaNm must be >= 0" }
        require(safeTcpaSec >= 0) { "safeTcpaSec must be >= 0" }
    }

    companion object {
        /** Common default safe CPA — operator-configurable, NOT a standard-mandated value (A.823 §3.5.2). */
        const val DEFAULT_SAFE_CPA_NM: Double = 2.0
        /** Common default safe TCPA = 12 min — operator-configurable, NOT mandated (A.823 §3.5.2). */
        const val DEFAULT_SAFE_TCPA_SEC: Double = 12.0 * 60.0
    }
}

/**
 * Classifies tracked targets as dangerous per the CPA/TCPA limits (A.823 §3.5.2). A `true` result drives
 * the red collision-threat symbol and BAM alarm **3044** (see [com.shipradar.contract.AlarmEvent]).
 */
object DangerClassifier {

    /**
     * Dangerous when the predicted CPA is at or inside the safe limit **and** the target is still
     * approaching it within the safe lead time, i.e.:
     *   - `cpaNm <= safeCpaNm`, and
     *   - `tcpaSec` is non-null, `>= 0` (CPA not yet passed) and `<= safeTcpaSec`.
     *
     * A target whose CPA has already passed (`tcpaSec < 0`, opening) is not flagged: it poses no future
     * close-quarters threat (A.823 §3.5.2 is predictive). A target with no relative motion
     * (`tcpaSec == null`) is flagged only if it is *already* within the safe CPA, since it will sit there.
     */
    fun isDangerous(solution: CpaSolution, criteria: DangerCriteria = DangerCriteria()): Boolean {
        val withinCpa = solution.cpaNm <= criteria.safeCpaNm
        if (!withinCpa) return false
        val tcpa = solution.tcpaSec ?: return true // no relative motion but already inside safe CPA
        return tcpa >= 0.0 && tcpa <= criteria.safeTcpaSec
    }

    /**
     * Returns a copy of [target] with `cpaNm`, `tcpaSec` and `dangerous` populated. Targets for which
     * CPA/TCPA cannot be computed (missing motion/bearing) are returned with `dangerous = false`.
     */
    fun evaluate(
        ownShip: OwnShipData,
        target: TrackedTarget,
        criteria: DangerCriteria = DangerCriteria(),
    ): TrackedTarget {
        val s = CpaTcpaCalculator.compute(ownShip, target) ?: return target.copy(dangerous = false)
        return target.copy(cpaNm = s.cpaNm, tcpaSec = s.tcpaSec, dangerous = isDangerous(s, criteria))
    }

    /** Evaluates a whole list (e.g. the fused target set) against one set of operator limits. */
    fun evaluateAll(
        ownShip: OwnShipData,
        targets: List<TrackedTarget>,
        criteria: DangerCriteria = DangerCriteria(),
    ): List<TrackedTarget> = targets.map { evaluate(ownShip, it, criteria) }
}
