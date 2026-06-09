package com.shipradar.comms.iec61162

/**
 * Operator/CAM alert command carried by an ACN sentence (IEC 61162-1 ED6 §8.3.7) — and the legacy
 * ACK sentence (§8.3.6). This is the **inbound intent**: a description of the command the bridge
 * issued, decoded from the wire. The alarm state machine (W5-B, `comms.alarm.BamAlarmManager`)
 * consumes these and decides the resulting transition; this parser performs no state logic.
 *
 * Alert addressing per IEC 62923-1 §6.x: an alert is identified by (identifier, instance).
 */
data class AlertCommand(
    /** Standard alert identifier (IEC 62923-2 Table A.1), e.g. 3044. */
    val identifier: Int,
    /** Alert instance (ACN §8.3.7 field, 1..999999); null when not addressed to one instance. */
    val instance: Int? = null,
    /** The requested command (ACN field 5 / §8.3.7 comment 5). */
    val kind: AlertCommandKind,
    /** Manufacturer mnemonic for proprietary alerts (ACN field 2); null for standardised alerts. */
    val manufacturer: String? = null,
    /** Time of the command, seconds-of-day from the ACN `hhmmss.ss` field (no date on the wire). */
    val utcSecondsOfDay: Double? = null,
)

/**
 * The alert command kinds defined by ACN field 5 / ARC field 5 (IEC 61162-1 ED6 §8.3.7 comment 5,
 * §8.3.17 comment 6):
 *  - A acknowledge; Q request/repeat the alert; O transfer responsibility; S temporary silence.
 */
enum class AlertCommandKind {
    ACKNOWLEDGE,            // A
    REQUEST_REPEAT,         // Q
    RESPONSIBILITY_TRANSFER,// O
    SILENCE;                // S

    companion object {
        /** Decode the single-character command code, or null if unknown. */
        fun fromCode(code: String?): AlertCommandKind? = when (code?.trim()?.uppercase()) {
            "A" -> ACKNOWLEDGE
            "Q" -> REQUEST_REPEAT
            "O" -> RESPONSIBILITY_TRANSFER
            "S" -> SILENCE
            else -> null
        }
    }
}

/**
 * An alert command that the source refused, carried by an ARC sentence (IEC 61162-1 ED6 §8.3.17).
 * The alarm logic (W5-B) raised it (e.g. silencing an emergency alarm is not permitted); this is
 * the decoded inbound report of such a refusal.
 */
data class AlertCommandRefusal(
    val identifier: Int,
    val instance: Int? = null,
    /** The command that was refused (ARC field 5 / §8.3.17 comment 6). */
    val refused: AlertCommandKind,
    val manufacturer: String? = null,
    val utcSecondsOfDay: Double? = null,
)
