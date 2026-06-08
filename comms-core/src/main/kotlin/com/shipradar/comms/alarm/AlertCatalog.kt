package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmPriority

/**
 * Alert category A/B/C (IEC 62923-1 §6.3.1 / IMO MSC.302(87)). Governs *where* an alert may be
 * acknowledged, not the lifecycle: A = ack only at the HMI presenting the alert with the associated
 * info; B = ack at any HMI, no extra info needed; C = cannot be acknowledged, awareness only.
 */
enum class AlertCategory { A, B, C }

/**
 * Static specification of a standard alert, sourced from **IEC 62923-2:2018 Table A.1**
 * (standard alert identifiers). [title] is the standardised title (≤16 chars, with `<...>`
 * placeholders the source fills in); [purpose] is the Table A.1 purpose text.
 */
data class AlertSpec(
    val identifier: Int,
    val priority: AlarmPriority,
    val title: String,
    val purpose: String,
    /**
     * TODO(待标准:62923-2) — Table A.1 assigns priority but NOT category. Category comes from the
     * individual equipment performance standard (radar: IEC 62388 / INS: IEC 61924-2). Values here
     * are provisional defaults pending those standards; flagged for orchestrator confirmation.
     */
    val category: AlertCategory,
    val source: String = "IEC 62923-2:2018 Table A.1",
)

/**
 * The standard alert identifiers this radar raises (IEC 62923-2:2018 Table A.1). Priorities are
 * quoted verbatim from the table; see [AlertSpec.category] note on the provisional categories.
 *
 * ALRM-01: id → priority/title mapping with cited source.
 */
object AlertCatalog {
    /** CPA/TCPA collision-danger alarm. */
    const val ID_CPA_TCPA = 3044
    /** New target detected. */
    const val ID_NEW_TARGET = 3048
    /** Acquired target lost. */
    const val ID_LOST_TARGET = 3052
    /** Target store / capacity exceeded — alarm variant. */
    const val ID_TARGET_CAPACITY_ALARM = 3042
    /** Target store / capacity exceeded — caution variant. */
    const val ID_TARGET_CAPACITY_CAUTION = 3043
    /** Lost essential sensor input (e.g. last position sensor). */
    const val ID_LOST_INPUT = 3015
    /** Lost communication with a connected system. */
    const val ID_LOST_INTERFACE = 3002

    private val byId: Map<Int, AlertSpec> = listOf(
        AlertSpec(ID_CPA_TCPA, AlarmPriority.ALARM, "CPA/TCPA <ID>", "Collision danger detected.", AlertCategory.A),
        AlertSpec(ID_NEW_TARGET, AlarmPriority.WARNING, "New Target <ID>", "A new target detected.", AlertCategory.B),
        AlertSpec(ID_LOST_TARGET, AlarmPriority.WARNING, "Lost target <ID>", "An acquired target has been lost.", AlertCategory.B),
        AlertSpec(ID_TARGET_CAPACITY_ALARM, AlarmPriority.WARNING, "<Target store>", "Target processing/display/capacity have been exceeded.", AlertCategory.B),
        AlertSpec(ID_TARGET_CAPACITY_CAUTION, AlarmPriority.CAUTION, "<Target store>", "Target processing/display/capacity have been exceeded.", AlertCategory.C),
        AlertSpec(ID_LOST_INPUT, AlarmPriority.WARNING, "Lost <input>", "A main function has lost essential sensor input.", AlertCategory.B),
        AlertSpec(ID_LOST_INTERFACE, AlarmPriority.WARNING, "Lost <interface>", "The system has lost communication with a connected system.", AlertCategory.B),
    ).associateBy { it.identifier }

    /**
     * Default per-alert lifecycle config. CPA/TCPA (3044) is a transitory-event alarm and bypasses
     * `rectified – unacknowledged` per IEC 62923-1 Annex G footnote b / Table 3.
     */
    private val configById: Map<Int, AlertConfig> = mapOf(
        ID_CPA_TCPA to AlertConfig(bypassRectifiedUnack = true),
    )

    /** All known specs, ascending by id. */
    val all: List<AlertSpec> get() = byId.values.sortedBy { it.identifier }

    fun spec(identifier: Int): AlertSpec? = byId[identifier]

    /** Priority for [identifier], or null if not a known standard id. */
    fun priorityOf(identifier: Int): AlarmPriority? = byId[identifier]?.priority

    /** Default lifecycle config for [identifier] (empty config if none registered). */
    fun configOf(identifier: Int): AlertConfig = configById[identifier] ?: AlertConfig()
}
