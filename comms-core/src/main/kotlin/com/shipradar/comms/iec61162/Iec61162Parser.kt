package com.shipradar.comms.iec61162

import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.SensorKind
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget

/**
 * Entry point for IEC 61162-1 ED6 sentence parsing (task T1.5, SENS-01 evidence).
 *
 * [parse] validates the frame (start delimiter, checksum), then dispatches by sentence formatter
 * to a per-sentence decoder that maps onto the frozen contract types. Bad packets (malformed,
 * bad checksum) are dropped (return null) and counted in [stats] for the sensor-supervision /
 * comms-lost logic upstream (alarm 3002, §3.7).
 *
 * The instance is cheap and holds only drop/parse counters; it is NOT thread-safe for the
 * counters (wrap externally if shared across threads). Parsing itself is stateless per sentence.
 *
 * Fully implemented (T1.5 mandatory): HDT, GGA, RMC, VTG, VDM, TTM, ALF.
 * Additionally implemented (share the same primitives, low risk): GLL, GNS, THS, ROT, ZDA, VDO, ALR.
 * Recognised-but-deferred formatters return [ParsedSentence.Unsupported] with the clause TODO.
 */
class Iec61162Parser {

    /** Running counters for drop-reason telemetry (§7.2.4 bad checksum, §7.3 malformed). */
    data class Stats(
        var parsed: Long = 0,
        var checksumFailures: Long = 0,
        var malformed: Long = 0,
        var unsupported: Long = 0,
    )

    val stats = Stats()

    /**
     * Parse one raw sentence (with or without trailing <CR><LF>). Returns null for a dropped
     * packet (malformed structure or checksum mismatch); a non-null result is returned even for
     * recognised-but-unimplemented formatters ([ParsedSentence.Unsupported]).
     *
     * IEC 61162-1 ED6 §7.2.4 (checksum), §7.3.1 (structure).
     */
    fun parse(raw: String): ParsedSentence? {
        return when (val r = SentenceFrame.parse(raw)) {
            is SentenceFrame.Result.Malformed -> { stats.malformed++; null }
            is SentenceFrame.Result.ChecksumError -> { stats.checksumFailures++; null }
            is SentenceFrame.Result.Ok -> dispatch(r.frame)
        }
    }

    private fun dispatch(f: SentenceFrame): ParsedSentence {
        val result = when (f.formatter.uppercase()) {
            "HDT" -> parseHdt(f)
            "THS" -> parseThs(f)
            "ROT" -> parseRot(f)
            "GGA" -> parseGga(f)
            "GLL" -> parseGll(f)
            "GNS" -> parseGns(f)
            "RMC" -> parseRmc(f)
            "VTG" -> parseVtg(f)
            "ZDA" -> parseZda(f)
            "VDM" -> parseVdm(f, ownVessel = false)
            "VDO" -> parseVdm(f, ownVessel = true)
            "TTM" -> parseTtm(f)
            "ALF" -> parseAlf(f)
            "ALR" -> parseAlr(f)
            else -> null
        }
        return when (result) {
            // Recognised-but-deferred, or unknown formatter: surface a framework placeholder.
            null -> {
                stats.unsupported++
                val note = DEFERRED_FORMATTERS[f.formatter.uppercase()]
                    ?: "unknown/unsupported formatter '${f.formatter}'"
                ParsedSentence.Unsupported(f.talker, f.formatter, note)
            }
            // A parser may itself return Unsupported (e.g. multi-fragment AIS) — count as such.
            is ParsedSentence.Unsupported -> { stats.unsupported++; result }
            else -> { stats.parsed++; result }
        }
    }

    // ---- Heading / attitude -------------------------------------------------------------------

    /** §8.3.52 HDT — Heading true. `$--HDT,x.x,T*hh`. (Deprecated in favour of THS, still in use.) */
    private fun parseHdt(f: SentenceFrame): ParsedSentence? {
        val heading = Fields.parseDouble(f.field(1)) ?: return null
        return ownShip(f, OwnShipData(
            headingDeg = heading,
            headingTrue = true,
            sourceValidity = mapOf(SensorKind.HEADING to true),
        ))
    }

    /** §8.3.101 THS — True heading and status. `$--THS,x.x,a*hh` (a: A/E/M/S/V mode indicator). */
    private fun parseThs(f: SentenceFrame): ParsedSentence? {
        val heading = Fields.parseDouble(f.field(1))
        val mode = f.field(2)
        val valid = mode != null && mode.uppercase() != "V"
        if (heading == null) return null
        return ownShip(f, OwnShipData(
            headingDeg = heading,
            headingTrue = true,
            sourceValidity = mapOf(SensorKind.HEADING to valid),
        ))
    }

    /** §8.3.83 ROT — Rate of turn. `$--ROT,x.x,A*hh` (deg/min, "-" bow turning to port; A/V status). */
    private fun parseRot(f: SentenceFrame): ParsedSentence? {
        val rot = Fields.parseDouble(f.field(1)) ?: return null
        val valid = f.field(2)?.uppercase() != "V"
        return ownShip(f, OwnShipData(
            rotDegMin = rot,
            sourceValidity = mapOf(SensorKind.HEADING to valid),
        ))
    }

    // ---- Position / course / speed ------------------------------------------------------------

    /** §8.3.42 GGA — GPS fix data. `$--GGA,hhmmss.ss,llll.ll,a,yyyyy.yy,a,x,...`. */
    private fun parseGga(f: SentenceFrame): ParsedSentence? {
        val lat = Fields.parseLatitude(f.field(2), f.field(3))
        val lon = Fields.parseLongitude(f.field(4), f.field(5))
        // §8.3.42 comment 1): quality "0" = fix not available/invalid; the field shall not be null.
        val quality = Fields.parseInt(f.field(6))
        val valid = quality != null && quality != 0
        if (lat == null || lon == null) return null
        return ownShip(f, OwnShipData(
            // GGA carries time-of-day only (no date) -> no absolute epoch; date fusion is T1.6.
            // TODO(待标准: 61162-1 §8.3.42) — combine with ZDA/RMC date to populate utcMillis.
            latitude = lat,
            longitude = lon,
            sourceValidity = mapOf(SensorKind.POSITION to valid),
        ))
    }

    /** §8.3.43 GLL — Geographic position. `$--GLL,llll.ll,a,yyyyy.yy,a,hhmmss.ss,A,a*hh`. */
    private fun parseGll(f: SentenceFrame): ParsedSentence? {
        val lat = Fields.parseLatitude(f.field(1), f.field(2))
        val lon = Fields.parseLongitude(f.field(3), f.field(4))
        // §8.3.43 comment 2): status field A=valid V=invalid; mode indicator supplements it.
        val valid = f.field(6)?.uppercase() == "A"
        if (lat == null || lon == null) return null
        return ownShip(f, OwnShipData(
            latitude = lat,
            longitude = lon,
            sourceValidity = mapOf(SensorKind.POSITION to valid),
        ))
    }

    /** §8.3.44 GNS — GNSS fix data. `$--GNS,hhmmss.ss,llll.ll,a,yyyyy.yy,a,c--c,...`. */
    private fun parseGns(f: SentenceFrame): ParsedSentence? {
        val lat = Fields.parseLatitude(f.field(2), f.field(3))
        val lon = Fields.parseLongitude(f.field(4), f.field(5))
        // §8.3.44 comment 1): mode indicator string; 'N' in all positions = no fix. Treat any
        // non-'N' first char as a usable fix; full per-constellation handling is deferred.
        val mode = f.field(6)
        val valid = mode != null && mode.any { it.uppercaseChar() != 'N' }
        if (lat == null || lon == null) return null
        return ownShip(f, OwnShipData(
            latitude = lat,
            longitude = lon,
            sourceValidity = mapOf(SensorKind.POSITION to valid),
        ))
    }

    /** §8.3.81 RMC — Recommended minimum specific GNSS data.
     *  `$--RMC,hhmmss.ss,A,llll.ll,a,yyyyy.yy,a,x.x,x.x,ddmmyy,x.x,a,a,a*hh`. */
    private fun parseRmc(f: SentenceFrame): ParsedSentence? {
        val status = f.field(2)?.uppercase()
        val lat = Fields.parseLatitude(f.field(3), f.field(4))
        val lon = Fields.parseLongitude(f.field(5), f.field(6))
        val sog = Fields.parseDouble(f.field(7))
        val cog = Fields.parseDouble(f.field(8))
        val epoch = Fields.parseUtcEpochMillis(f.field(1), f.field(9))
        // §8.3.81 comment: status A = data valid, V = navigation receiver warning.
        val valid = status == "A"
        if (lat == null || lon == null) return null
        return ownShip(f, OwnShipData(
            utcMillis = epoch,
            latitude = lat,
            longitude = lon,
            cogDeg = cog,
            sogKn = sog,
            sourceValidity = mapOf(
                SensorKind.POSITION to valid,
                SensorKind.COG_SOG to valid,
            ),
        ))
    }

    /** §8.3.122 VTG — Course over ground and ground speed.
     *  `$--VTG,x.x,T,x.x,M,x.x,N,x.x,K,a*hh`. */
    private fun parseVtg(f: SentenceFrame): ParsedSentence? {
        val cogTrue = Fields.parseDouble(f.field(1)) // field 2 == "T"
        val sogKn = Fields.parseDouble(f.field(5))    // field 6 == "N" (knots)
        // §8.3.122 comment 2): mode indicator shall not be null; 'N' = data not valid.
        val valid = f.field(9)?.uppercase() != "N"
        if (cogTrue == null && sogKn == null) return null
        return ownShip(f, OwnShipData(
            cogDeg = cogTrue,
            sogKn = sogKn,
            sourceValidity = mapOf(SensorKind.COG_SOG to valid),
        ))
    }

    /** §8.3.130 ZDA — Time and date. `$--ZDA,hhmmss.ss,xx,xx,xxxx,xx,xx*hh` (day,month,year,zone). */
    private fun parseZda(f: SentenceFrame): ParsedSentence? {
        val day = Fields.parseInt(f.field(2)) ?: return null
        val month = Fields.parseInt(f.field(3)) ?: return null
        val year = Fields.parseInt(f.field(4)) ?: return null
        val epoch = Fields.parseUtcEpochMillis(f.field(1), year, month, day) ?: return null
        return ownShip(f, OwnShipData(utcMillis = epoch))
    }

    // ---- AIS (VDM peers, VDO own vessel) ------------------------------------------------------

    /** §8.3.114 VDM / §8.3.115 VDO — AIS VHF data-link message. `!--VDM,x,x,x,a,s--s,x*hh`.
     *  Single-fragment messages decode immediately; multi-fragment reassembly is deferred to
     *  [AisReassembler] (wired by T1.6). VDO is the own-vessel report and maps to own-ship state. */
    private fun parseVdm(f: SentenceFrame, ownVessel: Boolean): ParsedSentence? {
        val total = Fields.parseInt(f.field(1)) ?: return null
        val payload = f.field(5) ?: return null
        val fillBits = Fields.parseInt(f.field(6)) ?: return null
        if (total != 1) {
            // Multi-fragment AIS message — needs cross-sentence reassembly (stateful), done by
            // AisReassembler in the T1.6 sync stage. TODO(待标准: ITU-R M.1371-5 §3.3).
            return ParsedSentence.Unsupported(
                f.talker, f.formatter,
                "multi-fragment AIS ($total fragments) — reassemble via AisReassembler (T1.6)",
            )
        }
        // §7.3.4/§8.2 six-bit de-armour; a corrupt encapsulated payload (valid checksum but bad
        // 6-bit chars / fill count) is reported distinctly rather than as "unknown formatter".
        val reader = AisPayloadDecoder.unarmor(payload, fillBits)
            ?: return ParsedSentence.Unsupported(f.talker, f.formatter, "AIS payload de-armour failed (§7.3.4/§8.2 6-bit)")
        val fields = AisPayloadDecoder.decodePositionReport(reader)
        if (fields == null) {
            // Recognised AIS sentence, but this message type is not a decodable position report.
            val type = AisPayloadDecoder.messageType(reader)
            return ParsedSentence.Unsupported(
                f.talker, f.formatter,
                "AIS message type $type not decoded (only 1/2/3/18/19 position reports) — " +
                    "TODO(待标准: ITU-R M.1371-5 §3.3 type 5/24 static; needs contract static fields)",
            )
        }
        return if (ownVessel) {
            // VDO: own vessel -> OwnShipData.
            ownShip(f, OwnShipData(
                latitude = fields.latitude,
                longitude = fields.longitude,
                cogDeg = fields.cogDeg,
                sogKn = fields.sogKn,
                headingDeg = fields.headingDeg,
                headingTrue = true,
                rotDegMin = fields.rotDegMin,
                sourceValidity = mapOf(SensorKind.AIS to true),
            ))
        } else {
            ParsedSentence.AisPositionReport(
                talker = f.talker,
                formatter = f.formatter,
                messageType = fields.messageType,
                mmsi = fields.mmsi,
                navStatus = fields.navStatus,
                latitude = fields.latitude,
                longitude = fields.longitude,
                cogDeg = fields.cogDeg,
                sogKn = fields.sogKn,
                headingDeg = fields.headingDeg,
                rotDegMin = fields.rotDegMin,
            )
        }
    }

    // ---- Radar / ARPA targets -----------------------------------------------------------------

    /** §8.3.108 TTM — Tracked target message.
     *  `$--TTM,xx,x.x,x.x,a,x.x,x.x,a,x.x,x.x,a,c--c,a,a,hhmmss.ss,a*hh`. */
    private fun parseTtm(f: SentenceFrame): ParsedSentence? {
        val number = f.field(1) ?: return null
        val distance = Fields.parseDouble(f.field(2)) ?: return null
        val bearing = Fields.parseDouble(f.field(3)) ?: return null
        val bearingRef = f.field(4)?.uppercase()      // T = true, R = relative
        val speed = Fields.parseDouble(f.field(5))
        val course = Fields.parseDouble(f.field(6))
        val cpa = Fields.parseDouble(f.field(8))
        val tcpaMin = Fields.parseDouble(f.field(9))
        val units = f.field(10)?.uppercase() ?: "N"    // K = km, N = nm, S = statute mi
        val status = f.field(12)?.uppercase()          // L = lost, Q = acquiring/query, T = tracking
        val toNm = distanceToNm(units)

        return ParsedSentence.TargetUpdate(f.talker, f.formatter, TrackedTarget(
            id = "TT-${number.trim()}",
            source = TargetSource.RADAR_TT,
            rangeNm = distance * toNm,
            bearingDeg = bearing,
            trueBearing = bearingRef == "T",
            courseDeg = course,
            speedKn = speed?.let { it * speedToKn(units) },
            cpaNm = cpa?.let { it * toNm },
            // §8.3.108 field 9: time to CPA in minutes, "-" = increasing (CPA already passed).
            // Contract: negative tcpaSec == CPA passed — sign aligns directly.
            tcpaSec = tcpaMin?.let { it * 60.0 },
            status = when (status) {
                "L" -> TargetStatus.LOST
                "Q" -> TargetStatus.ACQUIRING
                "T" -> TargetStatus.TRACKED
                else -> TargetStatus.TRACKED
            },
            // 'dangerous' is derived from CPA/TCPA thresholds during fusion (ui-core), not here.
        ))
    }

    // ---- Alerts / alarms ----------------------------------------------------------------------

    /** §8.3.14 ALF — Alert sentence.
     *  `$--ALF,x,x,x,hhmmss.ss,a,a,a,aaa,x.x,x.x,x.x,x,c---c*hh`. */
    private fun parseAlf(f: SentenceFrame): ParsedSentence? {
        // §8.3.14 comment 8): alert identifier — integer, max 7 digits; standardized BAM alert id.
        val identifier = Fields.parseInt(f.field(9)) ?: return null
        // §8.3.14 comment 5): priority E/A/W/C (IMO MSC.302(87)).
        val priority = when (f.field(6)?.uppercase()) {
            "E" -> AlarmPriority.EMERGENCY_ALARM
            "A" -> AlarmPriority.ALARM
            "W" -> AlarmPriority.WARNING
            "C" -> AlarmPriority.CAUTION
            else -> return null
        }
        // §8.3.14 comment 6): alert state transition (IEC 62923-1) V/S/A/O/U/N.
        val state = when (f.field(7)?.uppercase()) {
            "V" -> AlarmState.ACTIVE_UNACK
            "S" -> AlarmState.ACTIVE_SILENCED
            "A" -> AlarmState.ACTIVE_ACK
            // 'O' = responsibility transferred. The frozen contract has no dedicated state;
            // treat as acknowledged-active. TODO(待标准: 62923-1 §5) add RESPONSIBILITY_TRANSFERRED.
            "O" -> AlarmState.ACTIVE_ACK
            "U" -> AlarmState.RECTIFIED_UNACK
            "N" -> AlarmState.NORMAL
            else -> return null
        }
        return ParsedSentence.AlertUpdate(f.talker, f.formatter, AlarmEvent(
            identifier = identifier,
            priority = priority,
            state = state,
            text = f.field(13),
            source = f.talker,
            // ALF field 4 is time-of-day only (optional, §8.3.14 comment 3), not an epoch.
            // TODO(待标准: 61162-1 §8.3.14) populate utcMillis via ZDA/RMC date fusion (T1.6).
            utcMillis = 0,
        ))
    }

    /** §8.3.15 ALR — Set alarm state (legacy). `$--ALR,hhmmss.ss,xxx,A,A,c--c*hh`.
     *  field2: alarm id; field3: condition A/V; field4: ack A/V; field5: text. */
    private fun parseAlr(f: SentenceFrame): ParsedSentence? {
        val identifier = Fields.parseInt(f.field(2)) ?: return null
        val condition = f.field(3)?.uppercase()  // A = threshold exceeded (active), V = not
        val acked = f.field(4)?.uppercase()       // A = acknowledged, V = not
        val state = when {
            condition == "A" && acked == "V" -> AlarmState.ACTIVE_UNACK
            condition == "A" && acked == "A" -> AlarmState.ACTIVE_ACK
            condition == "V" -> AlarmState.NORMAL
            else -> AlarmState.ACTIVE_UNACK
        }
        return ParsedSentence.AlertUpdate(f.talker, f.formatter, AlarmEvent(
            identifier = identifier,
            // ALR carries no BAM priority field; priority resolved from the alert catalogue (T2.8).
            // TODO(待标准: 61162-1 §8.3.15) map identifier -> priority via 62923-2 alert list.
            priority = AlarmPriority.WARNING,
            state = state,
            text = f.field(5),
            source = f.talker,
            utcMillis = 0,
        ))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun ownShip(f: SentenceFrame, data: OwnShipData) =
        ParsedSentence.OwnShipUpdate(f.talker, f.formatter, data)

    /** §8.3.108 field 10 units: K = kilometres, N = nautical miles, S = statute miles -> NM factor. */
    private fun distanceToNm(units: String): Double = when (units) {
        "K" -> 1.0 / 1.852          // km  -> nm
        "S" -> 1.609344 / 1.852     // statute mi -> nm
        else -> 1.0                 // N (nautical miles) — already NM
    }

    /** Speed companion to [distanceToNm]: km/h or mph -> knots (same ratios). */
    private fun speedToKn(units: String): Double = distanceToNm(units)

    companion object {
        /**
         * Formatters recognised by T1.5 but whose full mapping is deferred (framework + TODO).
         * Each value names the standard clause to be implemented; this list is the SENS-01
         * "remaining work" registry surfaced via [ParsedSentence.Unsupported].
         */
        val DEFERRED_FORMATTERS: Map<String, String> = mapOf(
            "HDG" to "TODO(待标准: 61162-1 §8.3.51) magnetic heading + deviation/variation -> true heading",
            "VBW" to "TODO(待标准: 61162-1 §8.3.113) dual ground/water speed -> OwnShipData",
            "OSD" to "TODO(待标准: 61162-1 §8.3.75) own ship data (heading/course/speed/set/drift)",
            "TTD" to "TODO(待标准: 61162-1 §8.3.107 + ITU-R M.1371) encapsulated tracked target data (6-bit)",
            "TLL" to "TODO(待标准: 61162-1 §8.3.103) target lat/lon -> TrackedTarget (needs own-ship range/bearing fusion)",
            "TLB" to "TODO(待标准: 61162-1 §8.3.102) target label association",
            "RSD" to "TODO(待标准: 61162-1 §8.3.87) radar system data (display/cursor/EBL/VRM)",
            "SSD" to "TODO(待标准: 61162-1 §8.3.99) AIS ship static data",
            "VSD" to "TODO(待标准: 61162-1 §8.3.121) AIS voyage static data",
            "ALC" to "TODO(待标准: 61162-1 §8.3.13 + 62923-1) cyclic alert list",
            "ACN" to "TODO(待标准: 61162-1 §8.3.7 + 62923-1) alert command (ack/silence/responsibility)",
            "ARC" to "TODO(待标准: 61162-1 §8.3.17) alert command refused",
            "HBT" to "TODO(待标准: 61162-1 §8.3.49) heartbeat supervision",
            "DDC" to "TODO(待标准: 61162-1 §8.3.26) display dimming control",
            "TXT" to "TODO(待标准: 61162-1 §8.3.110) text transmission",
        )
    }
}
