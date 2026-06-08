package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmPriority

/**
 * Per-alert behaviour knobs that bend the otherwise-fixed lifecycle, all anchored in the standard.
 *
 * @property bypassRectifiedUnack IEC 62923-1 Annex G footnote b: a transitory-event alert may, when
 *   its cause is rectified, bypass `rectified – unacknowledged` and go straight to `normal`
 *   (the standard names CPA/TCPA alarms — see Table 3). Default false.
 * @property escalationMillis Warning escalation period (§6.3.5). When non-null and the priority is
 *   WARNING, an unacknowledged warning escalates to an alarm after this many millis. The standard
 *   requires the period not exceed [BamTiming.MAX_ESCALATION_MS] (5 min). Null = no escalation.
 * @property escalateToAlarmId Alert id of the new alarm raised on escalation. §6.3.5 requires it to
 *   differ from the original warning id. Required (non-null) iff [escalationMillis] is set.
 * @property grantResponsibilityTransfer Whether a [AlarmEventType.TRANSFER_RESPONSIBILITY] request is
 *   honoured; if false the request is refused with an ARC (IEC 62923-1 §6.7 responsibility-transfer
 *   reject). Default true.
 */
data class AlertConfig(
    val bypassRectifiedUnack: Boolean = false,
    val escalationMillis: Long? = null,
    val escalateToAlarmId: Int? = null,
    val grantResponsibilityTransfer: Boolean = true,
) {
    init {
        require(escalationMillis == null || escalationMillis in 1..BamTiming.MAX_ESCALATION_MS) {
            "escalationMillis must be 1..${BamTiming.MAX_ESCALATION_MS} (IEC 62923-1 §6.3.5: not exceeding 5 min)"
        }
        require((escalationMillis == null) == (escalateToAlarmId == null)) {
            "escalateToAlarmId is required iff escalationMillis is set (§6.3.5: escalated alarm needs a distinct id)"
        }
    }
}

/** Outcome of a single state-machine step: either an accepted transition or a rejected (illegal) one. */
sealed interface TransitionResult {
    /** A legal transition per IEC 62923-1 Annex G. */
    data class Accepted(val from: BamAlertState, val event: AlarmEventType, val to: BamAlertState) : TransitionResult

    /** An illegal transition — refused per the fail-to-safe principle (IEC 62923-1 §8, inconsistent combinations). */
    data class Rejected(val from: BamAlertState, val event: AlarmEventType, val reason: String) : TransitionResult
}

/**
 * Pure, stateless BAM alert state machine (**IEC 62923-1:2018**, Annex G state diagrams).
 *
 * Given (priority, current state, event) it returns the next state or a rejection. No time, no I/O,
 * no mutation — wall-clock-driven transitions ([AlarmEventType.SILENCE_TIMEOUT] /
 * [AlarmEventType.ESCALATION_TIMEOUT]) are scheduled by [BamAlarmManager]; this function only
 * resolves them positionally.
 *
 * ALRM-01: every legal transition below is traceable to an Annex G transition label.
 */
object AlarmStateMachine {

    /** Resolve one step. [config] only influences alarm/warning lifecycles. */
    fun next(
        priority: AlarmPriority,
        current: BamAlertState,
        event: AlarmEventType,
        config: AlertConfig = AlertConfig(),
    ): TransitionResult {
        // Emergency alarm (Figure G.1) and caution (Figure G.4) share the minimal NORMAL<->ACTIVE
        // lifecycle: no acknowledge, no silence. Emergency alarms "cannot be in active-silenced";
        // cautions are silent and "no acknowledgement shall be necessary" (§6.3.6).
        return if (priority == AlarmPriority.EMERGENCY_ALARM || priority == AlarmPriority.CAUTION) {
            minimalLifecycle(priority, current, event)
        } else {
            fullLifecycle(current, event, config)
        }
    }

    private fun minimalLifecycle(
        priority: AlarmPriority,
        current: BamAlertState,
        event: AlarmEventType,
    ): TransitionResult = when (current) {
        // ET1 / CT1: raise.
        BamAlertState.NORMAL -> when (event) {
            AlarmEventType.RAISE -> accept(current, event, BamAlertState.ACTIVE)
            else -> reject(current, event, "$priority in NORMAL accepts only RAISE")
        }
        // ET2/CT2 rectify, ET3/CT3 terminate -> normal. ACK/SILENCE are not permitted for this priority.
        BamAlertState.ACTIVE -> when (event) {
            AlarmEventType.RECTIFY, AlarmEventType.TERMINATE -> accept(current, event, BamAlertState.NORMAL)
            AlarmEventType.SILENCE ->
                reject(current, event, "$priority cannot be silenced (IEC 62923-1 §6.3.3/§6.3.6)")
            AlarmEventType.ACKNOWLEDGE ->
                reject(current, event, "$priority requires no acknowledgement (IEC 62923-1 §6.3.6)")
            else -> reject(current, event, "$priority in ACTIVE accepts only RECTIFY/TERMINATE")
        }
        else -> reject(current, event, "$priority only uses NORMAL/ACTIVE (Annex G Figure G.1/G.4)")
    }

    private fun fullLifecycle(
        current: BamAlertState,
        event: AlarmEventType,
        config: AlertConfig,
    ): TransitionResult {
        // Target of a rectify from an *unacknowledged* state: rectified-unack, unless this alert is
        // configured to bypass it (Annex G footnote b — e.g. CPA/TCPA).
        val rectifyTargetFromUnack =
            if (config.bypassRectifiedUnack) BamAlertState.NORMAL else BamAlertState.RECTIFIED_UNACK

        return when (current) {
            BamAlertState.NORMAL -> when (event) {
                AlarmEventType.RAISE -> accept(current, event, BamAlertState.ACTIVE_UNACK) // AT1/WT1
                else -> reject(current, event, "NORMAL accepts only RAISE")
            }

            BamAlertState.ACTIVE_UNACK -> when (event) {
                AlarmEventType.ACKNOWLEDGE -> accept(current, event, BamAlertState.ACTIVE_ACK) // AT9/WT9
                AlarmEventType.SILENCE -> accept(current, event, BamAlertState.ACTIVE_SILENCED) // AT16/WT16
                AlarmEventType.RECTIFY -> accept(current, event, rectifyTargetFromUnack) // AT5/WT5 (+footnote b)
                AlarmEventType.TERMINATE -> accept(current, event, BamAlertState.NORMAL) // AT18/WT19
                else -> reject(current, event, "ACTIVE_UNACK accepts ACKNOWLEDGE/SILENCE/RECTIFY/TERMINATE")
            }

            BamAlertState.ACTIVE_SILENCED -> when (event) {
                AlarmEventType.ACKNOWLEDGE -> accept(current, event, BamAlertState.ACTIVE_ACK)
                AlarmEventType.RECTIFY -> accept(current, event, rectifyTargetFromUnack)
                // Temporary-silence period elapsed: audible restarts, revert to unack (§6.3.4/§6.3.5).
                AlarmEventType.SILENCE_TIMEOUT -> accept(current, event, BamAlertState.ACTIVE_UNACK)
                AlarmEventType.TERMINATE -> accept(current, event, BamAlertState.NORMAL)
                else -> reject(current, event, "ACTIVE_SILENCED accepts ACKNOWLEDGE/RECTIFY/SILENCE_TIMEOUT/TERMINATE")
            }

            BamAlertState.ACTIVE_ACK -> when (event) {
                AlarmEventType.RECTIFY -> accept(current, event, BamAlertState.NORMAL) // AT10/WT10
                AlarmEventType.TRANSFER_RESPONSIBILITY ->
                    accept(current, event, BamAlertState.ACTIVE_RESP_TRANSFERRED) // WT14
                AlarmEventType.TERMINATE -> accept(current, event, BamAlertState.NORMAL)
                else -> reject(current, event, "ACTIVE_ACK accepts RECTIFY/TRANSFER_RESPONSIBILITY/TERMINATE")
            }

            BamAlertState.ACTIVE_RESP_TRANSFERRED -> when (event) {
                AlarmEventType.RECTIFY -> accept(current, event, BamAlertState.NORMAL) // WT12
                AlarmEventType.ACKNOWLEDGE -> accept(current, event, BamAlertState.ACTIVE_ACK) // WT18 (optional)
                AlarmEventType.TERMINATE -> accept(current, event, BamAlertState.NORMAL)
                else -> reject(current, event, "ACTIVE_RESP_TRANSFERRED accepts RECTIFY/ACKNOWLEDGE/TERMINATE")
            }

            BamAlertState.RECTIFIED_UNACK -> when (event) {
                AlarmEventType.ACKNOWLEDGE -> accept(current, event, BamAlertState.NORMAL) // AT2/WT2
                AlarmEventType.RAISE -> accept(current, event, BamAlertState.ACTIVE_UNACK) // condition recurred before ack
                AlarmEventType.TERMINATE -> accept(current, event, BamAlertState.NORMAL)
                else -> reject(current, event, "RECTIFIED_UNACK accepts ACKNOWLEDGE/RAISE/TERMINATE")
            }

            BamAlertState.ACTIVE ->
                reject(current, event, "ACTIVE is reserved for emergency/caution lifecycles")
        }
    }

    private fun accept(from: BamAlertState, event: AlarmEventType, to: BamAlertState) =
        TransitionResult.Accepted(from, event, to)

    private fun reject(from: BamAlertState, event: AlarmEventType, reason: String) =
        TransitionResult.Rejected(from, event, "illegal transition $event from $from: $reason")
}

/** Standard-mandated timing constants (IEC 62923-1). */
object BamTiming {
    /** Temporary-silence period: audible restarts 30 s after a temporary silence (§6.3.4/§6.3.5). */
    const val TEMPORARY_SILENCE_MS: Long = 30_000L

    /** Warning escalation period shall not exceed 5 min (§6.3.5). */
    const val MAX_ESCALATION_MS: Long = 300_000L

    /** Audible annunciation is repeated every 7 s to 10 s while active-unacknowledged (§6.3). */
    const val AUDIBLE_REPEAT_MIN_MS: Long = 7_000L
    const val AUDIBLE_REPEAT_MAX_MS: Long = 10_000L
}
