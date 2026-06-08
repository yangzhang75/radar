package com.shipradar.uicore.target

import com.shipradar.contract.TargetSource
import com.shipradar.contract.TrackedTarget

/**
 * Minimum target-handling capacities by equipment category (IEC 62388 FDIS Table 1 / Table 10 / Table 26;
 * IMO MSC.192(79) §5.21/§5.26). CAT 1 is the highest grade (ships/craft ≥10 000 GT) and the target of
 * this project's type certification (CAP-01).
 *
 * Verbatim from IEC 62388 Table 1, column "CAT 1":
 *   - Minimum acquired/tracked radar target capacity: **40**
 *   - Minimum activated AIS target capacity:          **40**
 *   - Minimum sleeping AIS target capacity:           **200**
 *   - Minimum total AIS target and reports capacity:  **240**
 *   - Auto acquisition of targets:                    Yes (CAT 1 only)
 *   - Trial manoeuvre:                                Yes (CAT 1 only)
 */
enum class EquipmentCategory(
    val radarTracked: Int,
    val aisActivated: Int,
    val aisSleeping: Int,
    val aisTotal: Int,
) {
    /** <500 GT. */
    CAT_3(radarTracked = 20, aisActivated = 20, aisSleeping = 100, aisTotal = 120),
    /** 500..<10 000 GT, HSC <10 000 GT. */
    CAT_2(radarTracked = 30, aisActivated = 30, aisSleeping = 150, aisTotal = 180),
    /** ≥10 000 GT — this project's certification target (CAP-01). */
    CAT_1(radarTracked = 40, aisActivated = 40, aisSleeping = 200, aisTotal = 240),
}

/** A single capacity dimension's live status. */
data class CapacityDimension(
    val name: String,
    val count: Int,
    val limit: Int,
) {
    val overLimit: Boolean get() = count > limit
    /** Within the "near-limit" caution band (>= [nearLimitFraction] of the limit) but not yet over. */
    fun nearLimit(nearLimitFraction: Double): Boolean =
        !overLimit && count >= Math.ceil(limit * nearLimitFraction).toInt()
}

/**
 * Live capacity report against a category's minimums, with the flags that drive the BAM capacity alerts:
 *   - **3043** "target capacity about to be exceeded" (caution) — any dimension [nearLimit].
 *   - **3042** "target capacity exceeded" (warning) — any dimension [overLimit].
 * (Alert ids per [com.shipradar.contract.AlarmEvent]; IEC 62388 §11.3.5 requires a caution when capacity
 * is about to be exceeded, §11.5.2 the same for AIS.)
 */
data class CapacityReport(
    val category: EquipmentCategory,
    val radarTracked: CapacityDimension,
    val aisActivated: CapacityDimension,
    val aisSleeping: CapacityDimension,
    val aisTotal: CapacityDimension,
) {
    val dimensions: List<CapacityDimension>
        get() = listOf(radarTracked, aisActivated, aisSleeping, aisTotal)

    /** True if any dimension exceeds its minimum capacity -> alarm 3042 (warning). */
    val anyOverLimit: Boolean get() = dimensions.any { it.overLimit }

    /** True if any dimension is in the near-limit band but none over -> alarm 3043 (caution). */
    fun anyNearLimit(nearLimitFraction: Double = CapacityMonitor.DEFAULT_NEAR_LIMIT_FRACTION): Boolean =
        !anyOverLimit && dimensions.any { it.nearLimit(nearLimitFraction) }
}

/**
 * Counts a target set against an [EquipmentCategory]'s minimum capacities and flags over/near-limit
 * (CAP-01; IEC 62388 §11.3.5, §11.5.2). Pure counting — no platform calls — so a 240+-target set can be
 * injected straight from a unit test.
 */
object CapacityMonitor {

    /**
     * Fraction of a limit at/above which the "about to be exceeded" caution (3043) fires. IEC 62388 says
     * a caution shall be given "about to be exceeded" but fixes no threshold; 90 % is a common choice.
     * TODO(待标准): confirm the acceptance-test threshold and adjust.
     */
    const val DEFAULT_NEAR_LIMIT_FRACTION: Double = 0.9

    fun evaluate(targets: List<TrackedTarget>, category: EquipmentCategory = EquipmentCategory.CAT_1): CapacityReport {
        var radar = 0; var aisActive = 0; var aisSleep = 0
        for (t in targets) {
            when (t.source) {
                TargetSource.RADAR_TT -> radar++
                TargetSource.AIS_ACTIVE -> aisActive++
                TargetSource.AIS_SLEEPING -> aisSleep++
            }
        }
        val aisTotal = aisActive + aisSleep
        return CapacityReport(
            category = category,
            radarTracked = CapacityDimension("radarTracked", radar, category.radarTracked),
            aisActivated = CapacityDimension("aisActivated", aisActive, category.aisActivated),
            aisSleeping = CapacityDimension("aisSleeping", aisSleep, category.aisSleeping),
            aisTotal = CapacityDimension("aisTotal", aisTotal, category.aisTotal),
        )
    }
}
