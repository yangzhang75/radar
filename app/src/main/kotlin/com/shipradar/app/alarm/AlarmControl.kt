package com.shipradar.app.alarm

import com.shipradar.comms.alarm.AlarmEventType

/**
 * An operator action raised from the alarm HMI. These are **intents**, not state changes: the UI
 * never mutates the BAM state machine directly. A [RadarAlarmController] forwards them to the comms
 * layer, which applies them via `com.shipradar.comms.alarm.BamAlarmManager.command(...)` and emits
 * the resulting ACN/ARC over IEC 61162 (IEC 62923-1 Annex C). This keeps T2.8 free of any wiring to
 * the T1.1 service and aligns the UI 1:1 with the T2.8a state machine.
 *
 * @property identifier standard BAM alert id (IEC 62923-2 Table A.1).
 * @property instance per-instance discriminator (default 1).
 */
sealed interface AlarmAction {
    val identifier: Int
    val instance: Int

    /** Acknowledge the alert (→ [AlarmEventType.ACKNOWLEDGE]). */
    data class Acknowledge(override val identifier: Int, override val instance: Int = 1) : AlarmAction

    /** Temporarily silence the audible (→ [AlarmEventType.SILENCE]); audible restarts after 30 s. */
    data class Silence(override val identifier: Int, override val instance: Int = 1) : AlarmAction

    /** Request transfer of responsibility (→ [AlarmEventType.TRANSFER_RESPONSIBILITY]); may be refused (ARC). */
    data class TransferResponsibility(override val identifier: Int, override val instance: Int = 1) : AlarmAction

    /** The matching state-machine event this action requests. */
    val event: AlarmEventType
        get() = when (this) {
            is Acknowledge -> AlarmEventType.ACKNOWLEDGE
            is Silence -> AlarmEventType.SILENCE
            is TransferResponsibility -> AlarmEventType.TRANSFER_RESPONSIBILITY
        }
}

/**
 * Sink the alarm HMI calls to forward operator actions downstream. Implemented by the orchestrator /
 * comms service (which owns a `BamAlarmManager` + the 61162 channel); the UI only depends on this
 * interface so it can be developed and previewed against fakes (slot-plan requirement).
 */
fun interface RadarAlarmController {
    /** Forward one operator [action] toward the alert source / CAM. Non-blocking; results return via the alarm event stream. */
    fun dispatch(action: AlarmAction)
}

/** A no-op controller for `@Preview`/tests where no service is wired. */
val NoopAlarmController: RadarAlarmController = RadarAlarmController { /* swallow */ }
