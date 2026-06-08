package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmState

/**
 * BAM alert lifecycle states per **IEC 62923-1:2018 §6.3.2.1** and the state diagrams of
 * **Annex G** (Figure G.1 emergency alarm, G.2 alarm, G.3 warning, G.4 caution).
 *
 * This is the *complete* state set the standard requires. It is a superset of the placeholder
 * [com.shipradar.contract.AlarmState], which is missing [ACTIVE] and [ACTIVE_RESP_TRANSFERRED].
 * See the T2.8a delivery report for the recommended final `contract.AlarmState` definition
 * (controlled shared change owned by the orchestrator). [toContract] gives a best-effort, lossy
 * mapping until the contract is widened.
 */
enum class BamAlertState {
    /** No alert condition present. Annex G E1 / A1 / W1 / C1. */
    NORMAL,

    /**
     * Single "active" state used only by non-acknowledgeable priorities — emergency alarm
     * (Annex G Figure G.1, state E2) and caution (Figure G.4, state C2). These priorities have
     * no ack/silence and therefore never enter the unack/silenced/ack/rectified states.
     */
    ACTIVE,

    /** active – unacknowledged: flashing + audible. Annex G A2 / W2. The state a raised alarm/warning enters. */
    ACTIVE_UNACK,

    /**
     * active – silenced: audible temporarily suppressed. Annex G A4 / W4. Reverts to
     * [ACTIVE_UNACK] when the temporary-silence period ([BamTiming.TEMPORARY_SILENCE_MS]) expires
     * (IEC 62923-1 §6.3.4/§6.3.5 — audible restarts after 30 s).
     */
    ACTIVE_SILENCED,

    /** active – acknowledged: no flashing + no audio. Annex G A5 / W5. */
    ACTIVE_ACK,

    /** active – responsibility transferred: no flashing + no audio. Annex G A6 / W6. */
    ACTIVE_RESP_TRANSFERRED,

    /**
     * rectified – unacknowledged: the alert condition no longer exists but has not yet been
     * acknowledged. Flashing + no audio. Annex G A3 / W3.
     */
    RECTIFIED_UNACK,
    ;

    /**
     * Best-effort projection onto the (currently narrower) frozen contract enum. **Lossy:**
     * [ACTIVE] collapses to [AlarmState.ACTIVE_UNACK] and [ACTIVE_RESP_TRANSFERRED] collapses to
     * [AlarmState.ACTIVE_ACK]. Callers needing the true BAM state must read [BamAlertState]
     * directly. TODO(待标准:62923-1 §6.3.2.1) — replace with a 1:1 mapping once the orchestrator
     * widens `contract.AlarmState` per the T2.8a report.
     */
    fun toContract(): AlarmState = when (this) {
        NORMAL -> AlarmState.NORMAL
        ACTIVE -> AlarmState.ACTIVE_UNACK
        ACTIVE_UNACK -> AlarmState.ACTIVE_UNACK
        ACTIVE_SILENCED -> AlarmState.ACTIVE_SILENCED
        ACTIVE_ACK -> AlarmState.ACTIVE_ACK
        ACTIVE_RESP_TRANSFERRED -> AlarmState.ACTIVE_ACK
        RECTIFIED_UNACK -> AlarmState.RECTIFIED_UNACK
    }
}

/**
 * Stimuli that drive the BAM state machine. KDoc cites the matching Annex G transition labels
 * (AT = alarm Figure G.2, WT = warning Figure G.3; ET/CT = emergency/caution Figure G.1/G.4).
 *
 * - [RAISE]/[RECTIFY]/[TERMINATE] originate from the alert *condition* (the alert source).
 * - [ACKNOWLEDGE]/[SILENCE]/[TRANSFER_RESPONSIBILITY] originate from an operator or CAM as a
 *   61162 **ACN** command (IEC 62923-1 Annex C); an unhonourable one yields an **ARC** refusal.
 * - [SILENCE_TIMEOUT]/[ESCALATION_TIMEOUT] are time-driven and injected by [BamAlarmManager.tick];
 *   they are never sent by a caller directly.
 */
enum class AlarmEventType {
    /** Alert condition becomes true. NORMAL → ACTIVE_UNACK (AT1/WT1) or NORMAL → ACTIVE (ET1/CT1). */
    RAISE,

    /** Operator/CAM acknowledges. ACTIVE_UNACK/SILENCED → ACTIVE_ACK (AT9/WT9); RECTIFIED_UNACK → NORMAL (AT2/WT2). */
    ACKNOWLEDGE,

    /** Operator temporarily silences the audible. ACTIVE_UNACK → ACTIVE_SILENCED (AT16/WT16). */
    SILENCE,

    /** Alert condition clears. See [AlarmStateMachine] for the per-state target (AT5/AT10/WT5/WT10/WT12). */
    RECTIFY,

    /** Responsibility-transfer request granted. ACTIVE_ACK → ACTIVE_RESP_TRANSFERRED (WT14). */
    TRANSFER_RESPONSIBILITY,

    /** Providing function switched off (e.g. equipment powered down). Any active state → NORMAL (AT18/WT19). */
    TERMINATE,

    /** Internal: temporary-silence period expired. ACTIVE_SILENCED → ACTIVE_UNACK (audible restarts). */
    SILENCE_TIMEOUT,

    /** Internal: warning escalation period expired (handled by [BamAlarmManager], warning-only, §6.3.5). */
    ESCALATION_TIMEOUT,
}

/** Visual annunciation state derived from the alert state + priority (Annex G / Table 3). */
enum class VisualState { NONE, STEADY, FLASHING }

/** Audible annunciation state derived from the alert state + priority (Annex G / Table 3, Table 5). */
enum class AudibleState { SILENT, AUDIBLE, TEMPORARILY_SILENCED }
