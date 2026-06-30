package com.shipradar.comms.iec61162

import com.shipradar.comms.alarm.AlertCatalog
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.ConningData
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.SensorKind
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.util.Angles

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
 * Implemented sentence set (§8.3): HDT, THS, ROT, HDG, GGA, GLL, GNS, RMC, VTG, OSD, VBW, ZDA
 * (own-ship/nav); VDM, VDO, TTM, TLL, TLB, RSD (targets/radar); ALF, ALR, ALC, ACN, ACK, ARC
 * (alerts/commands); DDC, HBT (display/supervision). AIS position reports decode Msg 1/2/3/18/19.
 * Deferred (see [DEFERRED_FORMATTERS]): AIS static data (TTD/SSD/VSD — needs ITU-R M.1371 + a
 * contract static-attributes extension) and TXT. Deferred formatters return [ParsedSentence.Unsupported].
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
            "HDG" -> parseHdg(f)
            "OSD" -> parseOsd(f)
            "VBW" -> parseVbw(f)
            "VDM" -> parseVdm(f, ownVessel = false)
            "VDO" -> parseVdm(f, ownVessel = true)
            "TTM" -> parseTtm(f)
            "TLL" -> parseTll(f)
            "TLB" -> parseTlb(f)
            "RSD" -> parseRsd(f)
            "RSA" -> parseRsa(f)
            "RPM" -> parseRpm(f)
            "DPT" -> parseDpt(f)
            "DBT" -> parseDbt(f)
            "ALF" -> parseAlf(f)
            "ALR" -> parseAlr(f)
            "ALC" -> parseAlc(f)
            "ACN" -> parseAcn(f)
            "ACK" -> parseAck(f)
            "ARC" -> parseArc(f)
            "DDC" -> parseDdc(f)
            "HBT" -> parseHbt(f)
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
            // ALR carries no BAM priority field. Resolve from IEC 62923-2 Table A.1 when the alarm
            // number is a standard alert id; ALR is legacy (device-local numbers) so fall back to
            // WARNING. (ALRM-01 via comms.alarm.AlertCatalog.)
            priority = AlertCatalog.priorityOf(identifier) ?: AlarmPriority.WARNING,
            state = state,
            text = f.field(5),
            source = f.talker,
            utcMillis = 0,
        ))
    }

    /** §8.3.51 HDG — Heading, deviation and variation. `$--HDG,x.x,x.x,a,x.x,a*hh`.
     *  Computes true heading from the magnetic sensor reading + deviation + variation. */
    private fun parseHdg(f: SentenceFrame): ParsedSentence? {
        val sensor = Fields.parseDouble(f.field(1)) ?: return null
        // §8.3.51 comments 2)/3): magnetic = sensor ± deviation (E +, W −); true = magnetic ± variation.
        val deviation = signedDir(Fields.parseDouble(f.field(2)), f.field(3))
        val variation = signedDir(Fields.parseDouble(f.field(4)), f.field(5))
        val magnetic = sensor + deviation
        val hasVariation = Fields.parseDouble(f.field(4)) != null && f.field(5) != null
        val heading = if (hasVariation) magnetic + variation else magnetic
        return ownShip(f, OwnShipData(
            headingDeg = Angles.normalizeDeg(heading),
            headingTrue = hasVariation, // true heading only once variation has been applied
            sourceValidity = mapOf(SensorKind.HEADING to true),
        ))
    }

    /** §8.3.75 OSD — Own ship data. `$--OSD,x.x,A,x.x,a,x.x,a,x.x,x.x,a*hh`. */
    private fun parseOsd(f: SentenceFrame): ParsedSentence? {
        val heading = Fields.parseDouble(f.field(1))   // degrees true
        val headingValid = f.field(2)?.uppercase() != "V"
        val course = Fields.parseDouble(f.field(3))    // degrees true
        val speed = Fields.parseDouble(f.field(5))
        val units = f.field(9)?.uppercase() ?: "N"     // K km/h, N knots, S statute mi/h
        val sog = speed?.let { it * distanceToNm(units) }
        if (heading == null && course == null && sog == null) return null
        val validity = buildMap {
            if (heading != null) put(SensorKind.HEADING, headingValid)
            if (course != null || sog != null) put(SensorKind.COG_SOG, true)
        }
        return ownShip(f, OwnShipData(
            headingDeg = heading, headingTrue = true, cogDeg = course, sogKn = sog,
            sourceValidity = validity,
        ))
    }

    /** §8.3.113 VBW — Dual ground/water speed. `$--VBW,x.x,x.x,A,x.x,x.x,A,x.x,A,x.x,A*hh` (knots).
     *  SOG = longitudinal ground speed (field 4). Speed-through-water (field 1) has no contract home. */
    private fun parseVbw(f: SentenceFrame): ParsedSentence? {
        val longitudinalGround = Fields.parseDouble(f.field(4)) ?: return null
        val groundValid = f.field(6)?.uppercase() != "V"
        // TODO(待标准: 61162-1 §8.3.113) transverse drift (field 5) + STW (field 1) — no OwnShipData field.
        return ownShip(f, OwnShipData(
            sogKn = longitudinalGround,
            // VBW writes no speed-log (STW) value, so only ground/COG-SOG validity is asserted (not SPEED_LOG).
            sourceValidity = mapOf(SensorKind.COG_SOG to groundValid),
        ))
    }

    /** §8.3.103 TLL — Target latitude and longitude.
     *  `$--TLL,xx,llll.ll,a,yyyyy.yy,a,c--c,hhmmss.ss,a,a*hh`. */
    private fun parseTll(f: SentenceFrame): ParsedSentence? {
        val number = f.field(1) ?: return null
        val lat = Fields.parseLatitude(f.field(2), f.field(3))
        val lon = Fields.parseLongitude(f.field(4), f.field(5))
        if (lat == null || lon == null) return null
        val status = when (f.field(8)?.uppercase()) {
            "L" -> TargetStatus.LOST
            "Q" -> TargetStatus.ACQUIRING
            "T" -> TargetStatus.TRACKED
            else -> null
        }
        return ParsedSentence.TargetGeoUpdate(
            f.talker, f.formatter,
            targetNumber = number.trim(),
            name = f.field(6),
            latitude = lat, longitude = lon,
            utcMillisOfDay = Fields.parseTimeOfDaySeconds(f.field(7))?.let { it * 1000.0 },
            status = status,
            isReference = f.field(9)?.uppercase() == "R",
        )
    }

    /** §8.3.102 TLB — Target label. `$--TLB,x.x,c--c,x.x,c--c,...*hh` (target-number/label pairs). */
    private fun parseTlb(f: SentenceFrame): ParsedSentence? {
        val labels = LinkedHashMap<String, String>()
        var i = 1
        while (i <= f.dataFieldCount) {
            val number = f.field(i)
            val label = f.field(i + 1)
            if (number != null && label != null) labels[number.trim()] = label
            i += 2
        }
        if (labels.isEmpty()) return null
        return ParsedSentence.TargetLabels(f.talker, f.formatter, labels)
    }

    /** §8.3.87 RSD — Radar system data.
     *  `$--RSD,x.x,x.x,x.x,x.x,x.x,x.x,x.x,x.x,x.x,x.x,x.x,a,a*hh` (cursor/EBL·VRM 1·2/scale/units/rot). */
    private fun parseRsd(f: SentenceFrame): ParsedSentence? {
        val units = f.field(12)?.uppercase() ?: "N"  // §8.3.87: K km, N nm, S statute mi
        val toNm = distanceToNm(units)
        fun rng(idx: Int) = Fields.parseDouble(f.field(idx))?.let { it * toNm }
        return ParsedSentence.RadarSystemDataUpdate(f.talker, f.formatter, RadarSystemData(
            origin1RangeNm = rng(1), origin1BearingDeg = Fields.parseDouble(f.field(2)),
            vrm1Nm = rng(3), ebl1Deg = Fields.parseDouble(f.field(4)),
            origin2RangeNm = rng(5), origin2BearingDeg = Fields.parseDouble(f.field(6)),
            vrm2Nm = rng(7), ebl2Deg = Fields.parseDouble(f.field(8)),
            cursorRangeNm = rng(9), cursorBearingDeg = Fields.parseDouble(f.field(10)),
            rangeScaleNm = rng(11),
            orientation = DisplayOrientation.fromCode(f.field(13)),
        ))
    }

    /** §8.3.86 RSA — Rudder sensor angle. `$--RSA,x.x,A,x.x,A*hh` (starboard/main, status, port, status). */
    private fun parseRsa(f: SentenceFrame): ParsedSentence? {
        val stbd = Fields.parseDouble(f.field(1))?.takeIf { f.field(2)?.uppercase() != "V" }
        val port = Fields.parseDouble(f.field(3))?.takeIf { f.field(4)?.uppercase() != "V" }
        if (stbd == null && port == null) return null
        return conning(f, ConningData(rudderAngleDeg = stbd, portRudderAngleDeg = port))
    }

    /** §8.3.84 RPM — Revolutions. `$--RPM,a,x,x.x,x.x,A*hh` (source S/E, number, rev/min, pitch%, status).
     *  Odd shaft/engine number = starboard, even = port; 0 (single/centre) is treated as starboard. */
    private fun parseRpm(f: SentenceFrame): ParsedSentence? {
        if (f.field(5)?.uppercase() == "V") return null // invalid
        val rpm = Fields.parseDouble(f.field(3)) ?: return null
        val number = Fields.parseInt(f.field(2)) ?: 0
        val starboard = number == 0 || number % 2 != 0
        return conning(f, if (starboard) ConningData(rpmStbd = rpm) else ConningData(rpmPort = rpm))
    }

    /** §8.3.28 DPT — Depth. `$--DPT,x.x,x.x,x.x*hh` (below transducer m, offset m, max range m). */
    private fun parseDpt(f: SentenceFrame): ParsedSentence? {
        val below = Fields.parseDouble(f.field(1)) ?: return null
        val offset = Fields.parseDouble(f.field(2)) ?: 0.0
        // offset > 0 = transducer-to-waterline ⇒ depth below waterline = below + offset; negative offset
        // (transducer-to-keel) is left out of the waterline figure.
        return conning(f, ConningData(depthM = below + offset.coerceAtLeast(0.0)))
    }

    /** §8.3.25 DBT — Depth below transducer. `$--DBT,x.x,f,x.x,M,x.x,F*hh` (feet, metres, fathoms). */
    private fun parseDbt(f: SentenceFrame): ParsedSentence? {
        val metres = Fields.parseDouble(f.field(3)) ?: return null // field 3 = depth in metres
        return conning(f, ConningData(depthM = metres))
    }

    private fun conning(f: SentenceFrame, data: ConningData): ParsedSentence =
        ParsedSentence.ConningUpdate(f.talker, f.formatter, data)

    /** §8.3.13 ALC — Cyclic alert list. `$--ALC,xx,xx,xx,x.x,{aaa,x.x,x.x,x.x}...*hh`. */
    private fun parseAlc(f: SentenceFrame): ParsedSentence {
        val count = Fields.parseInt(f.field(4)) ?: 0
        val entries = ArrayList<AlertListEntry>()
        for (k in 0 until count) {
            val base = 5 + k * 4 // groups of {manufacturer, identifier, instance, revision}
            val id = Fields.parseInt(f.field(base + 1)) ?: continue
            entries.add(AlertListEntry(
                identifier = id,
                instance = Fields.parseInt(f.field(base + 2)),
                revisionCounter = Fields.parseInt(f.field(base + 3)),
                manufacturer = f.field(base),
                // ALRM-01: resolve priority from IEC 62923-2 Table A.1 for standardised ids.
                priority = AlertCatalog.priorityOf(id),
            ))
        }
        return ParsedSentence.AlertListUpdate(f.talker, f.formatter, entries)
    }

    /** §8.3.7 ACN — Alert command. `$--ACN,hhmmss.ss,aaa,x.x,x.x,a,a*hh` (cmd A/Q/O/S, flag C). */
    private fun parseAcn(f: SentenceFrame): ParsedSentence? {
        val identifier = Fields.parseInt(f.field(3)) ?: return null
        val kind = AlertCommandKind.fromCode(f.field(5)) ?: return null
        return ParsedSentence.AlertCommandReceived(f.talker, f.formatter, AlertCommand(
            identifier = identifier,
            instance = Fields.parseInt(f.field(4)),
            kind = kind,
            manufacturer = f.field(2),
            utcSecondsOfDay = Fields.parseTimeOfDaySeconds(f.field(1)),
        ))
    }

    /** §8.3.6 ACK — Acknowledge alarm (legacy, deprecated). `$--ACK,xxx*hh` (unique alarm number). */
    private fun parseAck(f: SentenceFrame): ParsedSentence? {
        val identifier = Fields.parseInt(f.field(1)) ?: return null
        return ParsedSentence.AlertCommandReceived(f.talker, f.formatter, AlertCommand(
            identifier = identifier, kind = AlertCommandKind.ACKNOWLEDGE,
        ))
    }

    /** §8.3.17 ARC — Alert command refused. `$--ARC,hhmmss.ss,aaa,x.x,x.x,a*hh` (refused cmd A/Q/O/S). */
    private fun parseArc(f: SentenceFrame): ParsedSentence? {
        val identifier = Fields.parseInt(f.field(3)) ?: return null
        val refused = AlertCommandKind.fromCode(f.field(5)) ?: return null
        return ParsedSentence.AlertCommandRefused(f.talker, f.formatter, AlertCommandRefusal(
            identifier = identifier,
            instance = Fields.parseInt(f.field(4)),
            refused = refused,
            manufacturer = f.field(2),
            utcSecondsOfDay = Fields.parseTimeOfDaySeconds(f.field(1)),
        ))
    }

    /** §8.3.26 DDC — Display dimming control. `$--DDC,a,xx,a,a*hh` (mode/brightness/palette/status). */
    private fun parseDdc(f: SentenceFrame): ParsedSentence? {
        val mode = DimMode.fromCode(f.field(1))
        val brightness = Fields.parseInt(f.field(2))?.coerceIn(0, 99)
        val palette = DimMode.fromCode(f.field(3))
        if (mode == null && brightness == null && palette == null) return null
        return ParsedSentence.DisplayDimming(f.talker, f.formatter, mode, brightness, palette)
    }

    /** §8.3.49 HBT — Heartbeat supervision. `$--HBT,x.x,A,x*hh` (interval s, status A/V, seq id). */
    private fun parseHbt(f: SentenceFrame): ParsedSentence? {
        val status = f.field(2)?.uppercase() ?: return null
        return ParsedSentence.Heartbeat(
            f.talker, f.formatter,
            intervalSec = Fields.parseDouble(f.field(1)),
            alive = status == "A", // §8.3.49 comment 2): A normal operation, V error
        )
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun ownShip(f: SentenceFrame, data: OwnShipData) =
        ParsedSentence.OwnShipUpdate(f.talker, f.formatter, data)

    /** Apply an E/W direction sign to a magnitude (E = +, W = −); 0 when value/direction absent. */
    private fun signedDir(value: Double?, dir: String?): Double {
        if (value == null) return 0.0
        return when (dir?.uppercase()) {
            "E" -> value
            "W" -> -value
            else -> 0.0
        }
    }

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
            // AIS static/voyage data — skipped pending the AIS message standard + a contract
            // static-attributes extension (W5-A scope note; see also AisPayloadDecoder type 5/24).
            "TTD" to "TODO(待标准: ITU-R M.1371) encapsulated tracked target data (6-bit) — needs AIS decode + contract",
            "SSD" to "TODO(待标准: ITU-R M.1371) AIS ship static data (name/callsign/dims) — needs contract static fields",
            "VSD" to "TODO(待标准: ITU-R M.1371) AIS voyage static data (draught/destination) — needs contract static fields",
            // Free text — no structured contract mapping required yet.
            "TXT" to "TODO(待标准: 61162-1 §8.3.110) text transmission",
        )
    }
}
