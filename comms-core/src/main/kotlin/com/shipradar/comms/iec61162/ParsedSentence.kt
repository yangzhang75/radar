package com.shipradar.comms.iec61162

import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TrackedTarget

/**
 * Typed result of parsing one IEC 61162-1 sentence.
 *
 * The parser maps each recognised sentence onto the frozen contract types
 * ([OwnShipData] / [TrackedTarget] / [AlarmEvent]). Fusion of successive partial
 * updates into a single coherent state (e.g. combining HDT heading with GGA position,
 * or resolving an AIS lat/lon into an own-ship-relative range/bearing) is the
 * responsibility of the sync/fusion stage (T1.6) — this stage stays stateless and
 * per-sentence so it is exhaustively unit-testable.
 *
 * IEC 61162-1 ED6 §7.3 (sentence structure).
 */
sealed interface ParsedSentence {
    /** Two-character talker identifier, e.g. "GP", "GN", "HE", "RA", "AI" (§7.3.2, §8 address field). */
    val talker: String
    /** Three-character sentence formatter, e.g. "HDT", "GGA", "VDM" (§7.3.2). */
    val formatter: String

    /**
     * A position / heading / speed sentence carrying a PARTIAL own-ship snapshot.
     * Only the fields the sentence actually provides are populated; everything else is
     * null/default and must be merged downstream.
     */
    data class OwnShipUpdate(
        override val talker: String,
        override val formatter: String,
        val data: OwnShipData,
    ) : ParsedSentence

    /** A radar/ARPA tracked-target sentence (TTM) carrying range/bearing directly. */
    data class TargetUpdate(
        override val talker: String,
        override val formatter: String,
        val target: TrackedTarget,
    ) : ParsedSentence

    /** An alert/alarm sentence (ALF, ALR) mapped to a BAM [AlarmEvent]. */
    data class AlertUpdate(
        override val talker: String,
        override val formatter: String,
        val alarm: AlarmEvent,
    ) : ParsedSentence

    /**
     * A decoded AIS position report (from VDM). Geographic-only: a unified [TrackedTarget]
     * needs an own-ship-relative range/bearing which requires own-ship position fusion
     * (T1.6 + ui-core geometry), so we surface the raw decoded fields here rather than
     * fabricating range=0/bearing=0.
     *
     * ITU-R M.1371 message types 1/2/3 (Class A) and 18 (Class B) position reports.
     * 6-bit armouring per IEC 61162-1 ED6 §7.3.4 / §8.2 (Table 5).
     */
    data class AisPositionReport(
        override val talker: String,
        override val formatter: String,
        val messageType: Int,
        val mmsi: Long,
        /** ITU-R M.1371 navigation status (0-15); null for Class B (type 18). */
        val navStatus: Int? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val cogDeg: Double? = null,
        val sogKn: Double? = null,
        val headingDeg: Double? = null,
        val rotDegMin: Double? = null,
    ) : ParsedSentence

    /**
     * Sentence whose formatter is recognised by the registry but whose full field mapping
     * is not yet implemented (framework + TODO), OR an unknown formatter. [note] records the
     * standard clause still to be implemented so the cert traceability stays honest.
     */
    data class Unsupported(
        override val talker: String,
        override val formatter: String,
        val note: String,
    ) : ParsedSentence
}
