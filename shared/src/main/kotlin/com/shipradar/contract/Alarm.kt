package com.shipradar.contract

/**
 * BAM alarm event (IEC 62923-2 / 61924-2). Identifiers use the standard IDs directly.
 * Carried over 61162 ALF/ALC/ACN/ARC and surfaced by the alarm UI (T2.8).
 */
data class AlarmEvent(
    /** Standard BAM identifier, e.g. 3044 CPA/TCPA, 3052 lost target, 3048 new target,
     *  3042/3043 capacity over/near limit, 3015 sensor failure, 3002 comms lost. */
    val identifier: Int,
    val priority: AlarmPriority,
    val state: AlarmState,
    val text: String? = null,
    val source: String = "",
    val utcMillis: Long = 0,
)

/** BAM priorities (descending urgency). */
enum class AlarmPriority { EMERGENCY_ALARM, ALARM, WARNING, CAUTION }

/**
 * BAM alert state per IEC 62923-1:2018 §6.3.2.1 and Annex G state diagrams (Fig G.1–G.4).
 * Finalized from the T2.8a delivery (was a placeholder). Non-acknowledgeable priorities
 * (emergency alarm / caution) use [ACTIVE]; acknowledgeable ones (alarm / warning) traverse the
 * unack → silenced → ack → responsibility-transferred / rectified states.
 */
enum class AlarmState {
    /** No alert condition. Annex G E1/A1/W1/C1. */
    NORMAL,
    /** Active, non-acknowledgeable (emergency alarm / caution). Annex G E2/C2. */
    ACTIVE,
    /** Active – unacknowledged: flashing + audible. Annex G A2/W2. */
    ACTIVE_UNACK,
    /** Active – silenced: audible temporarily suppressed (restarts after 30 s). Annex G A4/W4. */
    ACTIVE_SILENCED,
    /** Active – acknowledged. Annex G A5/W5. */
    ACTIVE_ACK,
    /** Active – responsibility transferred. Annex G A6/W6. */
    ACTIVE_RESP_TRANSFERRED,
    /** Rectified – unacknowledged: condition cleared, not yet acknowledged. Annex G A3/W3. */
    RECTIFIED_UNACK,
}
