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

/** BAM alarm state machine (IEC 62923-1). Detailed transitions TODO when 62923-1 is extracted (§5). */
enum class AlarmState { ACTIVE_UNACK, ACTIVE_SILENCED, ACTIVE_ACK, RECTIFIED_UNACK, NORMAL }
