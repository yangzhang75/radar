package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmEvent

/**
 * Outbound *intents* produced by the alarm logic — **descriptions of what should happen**, not the
 * actions themselves. Per T2.8a scope this layer never touches sockets, serial or 61162 encoding:
 * the comms channel (T1.3/T1.5) turns [ReportAlf]/[RefuseAcn] into IEC 61162-1 ALF/ARC sentences,
 * and the UI (second wave) consumes [Annunciate]. Everything here is plain immutable data.
 */
sealed interface AlarmIntent {

    /**
     * Report a state change to consumers — encoded downstream as an **ALF** alert sentence
     * (IEC 62923-1 Annex C / §8). [event] is a contract snapshot of the alert *after* the
     * transition; [bamState] is the true (possibly finer) BAM state, since
     * [com.shipradar.contract.AlarmState] cannot yet represent every state — see
     * [BamAlertState.toContract]. [previous] and [cause] aid traceability and the ALF time stamp.
     */
    data class ReportAlf(
        val event: AlarmEvent,
        val bamState: BamAlertState,
        val previous: BamAlertState,
        val cause: AlarmEventType,
    ) : AlarmIntent

    /**
     * Refuse an inbound operator/CAM command that the state machine rejected — encoded downstream as
     * an **ARC** (alert command refused) sentence (IEC 62923-1 §8). Examples: silencing an
     * emergency alarm, acknowledging from a state with no ACK transition, or a denied
     * responsibility-transfer request (§6.7).
     */
    data class RefuseAcn(
        val identifier: Int,
        val instance: Int,
        val attempted: AlarmEventType,
        val reason: String,
    ) : AlarmIntent

    /**
     * Drive the annunciator. Derived from the alert state + priority per Annex G / Table 3 (visual)
     * and Table 5 (caution is always silent). Consumed by the UI layer; produced on every state
     * change so the indicator always tracks the machine.
     */
    data class Annunciate(
        val identifier: Int,
        val instance: Int,
        val visual: VisualState,
        val audible: AudibleState,
    ) : AlarmIntent

    /**
     * Warning escalation (IEC 62923-1 §6.3.5): the original warning is terminated and a *new* alarm
     * with a **different** alert id is raised. Both the termination and the new raise also emit
     * their own [ReportAlf]; this intent records the escalation link for the audit trail.
     */
    data class Escalate(
        val fromIdentifier: Int,
        val instance: Int,
        val toAlarmIdentifier: Int,
    ) : AlarmIntent
}
