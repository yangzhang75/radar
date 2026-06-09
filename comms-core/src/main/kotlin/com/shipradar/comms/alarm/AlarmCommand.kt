package com.shipradar.comms.alarm

/**
 * A received alert command — the inbound side of Bridge Alert Management (**IEC 62923-1 §6.3 / §6.9**,
 * carried on the 61162 **ACN** sentence). It is normalised to the *kind* of command rather than the
 * wire letter, so the state machine consumes intent, not encoding.
 *
 * This is the **interface the 61162 ACN parser (W5-A) targets**: that worker maps the ACN command
 * field (A acknowledge / S silence / O responsibility-transfer / Q request-repeat) to a [Kind] and
 * fills [identifier]/[instance] from the sentence. Until W5-A lands, callers (and tests) build this
 * directly — [BamAlarmManager.accept] depends only on this type, never on the parser.
 *
 * @property identifier standard BAM alert id (IEC 62923-2 Table A.1).
 * @property instance per-instance discriminator (default 1).
 * @property kind the requested command.
 * @property source originator (CAM / HMI id) — echoed into the ARC/ALF receipts for addressing.
 */
data class AlarmCommand(
    val identifier: Int,
    val kind: Kind,
    val instance: Int = 1,
    val source: String = "",
) {
    /** ACN command kinds (IEC 62923-1; 61162-1 ACN command field). */
    enum class Kind {
        /** Acknowledge the alert (ACN 'A'): active-unack/silenced → active-acknowledged. */
        ACKNOWLEDGE,

        /** Temporarily silence the audible (ACN 'S'): active-unack → active-silenced (§6.3.4). */
        SILENCE,

        /**
         * Request transfer of responsibility (ACN 'O'): active-acknowledged → active-responsibility-
         * transferred. Granted unless denial is mandated, in which case an ARC is returned (§6.9.2.1).
         */
        RESPONSIBILITY_TRANSFER,

        /**
         * Request a repeat of the alert information (ACN 'Q'): no state change; the source re-issues
         * the current ALC list (and the ALF for the addressed alert). §6.3.
         */
        REQUEST_REPEAT,
    }

    /** The state-machine event this command requests, or null for [Kind.REQUEST_REPEAT] (no transition). */
    val event: AlarmEventType?
        get() = when (kind) {
            Kind.ACKNOWLEDGE -> AlarmEventType.ACKNOWLEDGE
            Kind.SILENCE -> AlarmEventType.SILENCE
            Kind.RESPONSIBILITY_TRANSFER -> AlarmEventType.TRANSFER_RESPONSIBILITY
            Kind.REQUEST_REPEAT -> null
        }
}
