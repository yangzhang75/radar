package com.shipradar.comms.iec61162

import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.SensorKind
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T1.5 acceptance tests. Every assertion uses a sentence whose checksum was computed against the
 * literal text below (so the checksum path is exercised end-to-end), and each value is checked
 * field-by-field against IEC 61162-1 ED6 §8.3 / §8.2 and ITU-R M.1371-5 §3.3.
 */
class Iec61162ParserTest {

    private val parser = Iec61162Parser()
    private val tol = 1e-6

    // ---- Checksum / framing (§7.2.4, §7.3.1) --------------------------------------------------

    @Test fun checksum_xor_matches_standard_example() {
        // §7.2.4 worked example: "$GPGLL,5057.970,N,00146.110,E,142451,A*27".
        assertEquals("27", SentenceFrame.computeChecksum("GPGLL,5057.970,N,00146.110,E,142451,A")
            .toString(16).uppercase().padStart(2, '0'))
        // §7.2.4 worked example: "$GPVTG,089.0,T,,,15.2,N,,,*53".
        assertEquals("53", SentenceFrame.computeChecksum("GPVTG,089.0,T,,,15.2,N,,,")
            .toString(16).uppercase().padStart(2, '0'))
    }

    @Test fun good_checksum_parses_bad_checksum_is_dropped_and_counted() {
        assertNotNull(parser.parse("\$HEHDT,274.07,T*19"))
        assertEquals(0, parser.stats.checksumFailures)
        // Corrupt the checksum: structurally valid frame, wrong XOR -> dropped.
        assertNull(parser.parse("\$HEHDT,274.07,T*00"))
        assertEquals(1, parser.stats.checksumFailures)
    }

    @Test fun malformed_packets_are_dropped_and_counted() {
        assertNull(parser.parse("HEHDT,274.07,T*19"))      // no start delimiter
        assertNull(parser.parse("\$HEHDT,274.07,T"))        // no checksum delimiter
        assertNull(parser.parse("\$*19"))                   // empty body
        assertNull(parser.parse("\$HE*0D"))                 // valid checksum but address too short
        assertNull(parser.parse(""))                        // empty
        // §7.3.1: > 82 characters is malformed.
        val tooLong = "\$GP" + "X".repeat(90) + "*00"
        assertNull(parser.parse(tooLong))
        assertEquals(6, parser.stats.malformed)
    }

    @Test fun trailing_crlf_is_tolerated() {
        assertNotNull(parser.parse("\$HEHDT,274.07,T*19\r\n"))
    }

    // ---- Heading (§8.3.52 HDT, §8.3.101 THS, §8.3.83 ROT) -------------------------------------

    @Test fun hdt_true_heading() {
        val r = parser.parse("\$HEHDT,274.07,T*19") as ParsedSentence.OwnShipUpdate
        assertEquals("HE", r.talker)
        assertEquals("HDT", r.formatter)
        assertEquals(274.07, r.data.headingDeg!!, tol)
        assertTrue(r.data.headingTrue)
        assertEquals(true, r.data.sourceValidity[SensorKind.HEADING])
    }

    @Test fun ths_heading_with_invalid_mode() {
        val r = parser.parse("\$HETHS,182.5,V*34") as ParsedSentence.OwnShipUpdate
        assertEquals(182.5, r.data.headingDeg!!, tol)
        assertEquals(false, r.data.sourceValidity[SensorKind.HEADING])
    }

    @Test fun rot_rate_of_turn_signed() {
        val r = parser.parse("\$TIROT,-2.3,A*17") as ParsedSentence.OwnShipUpdate
        assertEquals(-2.3, r.data.rotDegMin!!, tol)
    }

    // ---- Position (§8.3.42 GGA, §8.3.43 GLL, §8.3.81 RMC) -------------------------------------

    @Test fun gga_position_and_quality() {
        val r = parser.parse("\$GPGGA,123519.00,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*69")
                as ParsedSentence.OwnShipUpdate
        assertEquals(48.1173, r.data.latitude!!, 1e-4)
        assertEquals(11.516667, r.data.longitude!!, 1e-4)
        assertEquals(true, r.data.sourceValidity[SensorKind.POSITION])
    }

    @Test fun gga_quality_zero_marks_position_invalid() {
        // §8.3.42 comment 1): quality indicator "0" = fix not available/invalid.
        val r = parser.parse("\$GPGGA,123519.00,4807.038,N,01131.000,E,0,00,,,,,,,*7C")
                as ParsedSentence.OwnShipUpdate
        assertEquals(false, r.data.sourceValidity[SensorKind.POSITION])
    }

    @Test fun gga_southern_western_hemisphere_signs() {
        val r = parser.parse("\$GPGGA,123519.00,4807.038,S,01131.000,W,1,08,0.9,545.4,M,,,,*3E")
                as ParsedSentence.OwnShipUpdate
        assertTrue(r.data.latitude!! < 0)
        assertTrue(r.data.longitude!! < 0)
        assertEquals(-48.1173, r.data.latitude!!, 1e-4)
        assertEquals(-11.516667, r.data.longitude!!, 1e-4)
    }

    @Test fun rmc_full_fix_with_epoch() {
        val r = parser.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230324,003.1,W,A,S*73")
                as ParsedSentence.OwnShipUpdate
        assertEquals(48.1173, r.data.latitude!!, 1e-4)
        assertEquals(11.516667, r.data.longitude!!, 1e-4)
        assertEquals(22.4, r.data.sogKn!!, tol)
        assertEquals(84.4, r.data.cogDeg!!, tol)
        // ddmmyy "230324" -> 2024-03-23T12:35:19Z (2-digit year mapped to 2000-2099, see Fields).
        assertEquals(1711197319000L, r.data.utcMillis)
        assertEquals(true, r.data.sourceValidity[SensorKind.POSITION])
    }

    @Test fun rmc_status_void_marks_invalid() {
        val r = parser.parse("\$GPRMC,123519,V,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W,N,V*65")
                as ParsedSentence.OwnShipUpdate
        assertEquals(false, r.data.sourceValidity[SensorKind.POSITION])
    }

    // ---- Course/speed + time (§8.3.122 VTG, §8.3.130 ZDA) -------------------------------------

    @Test fun vtg_course_and_speed() {
        val r = parser.parse("\$GPVTG,089.0,T,094.1,M,15.2,N,28.1,K,A*23") as ParsedSentence.OwnShipUpdate
        assertEquals(89.0, r.data.cogDeg!!, tol)
        assertEquals(15.2, r.data.sogKn!!, tol)
        assertEquals(true, r.data.sourceValidity[SensorKind.COG_SOG])
    }

    @Test fun vtg_mode_n_marks_invalid() {
        // §8.3.122 comment 2): mode indicator 'N' = data not valid (even when fields carry values).
        val r = parser.parse("\$GPVTG,089.0,T,094.1,M,15.2,N,28.1,K,N*2C") as ParsedSentence.OwnShipUpdate
        assertEquals(false, r.data.sourceValidity[SensorKind.COG_SOG])
    }

    @Test fun zda_time_and_date_epoch() {
        val r = parser.parse("\$GPZDA,160012.00,11,03,2024,00,00*65") as ParsedSentence.OwnShipUpdate
        // 2024-03-11T16:00:12Z
        assertEquals(1710172812000L, r.data.utcMillis)
    }

    // ---- Radar target (§8.3.108 TTM) ----------------------------------------------------------

    @Test fun ttm_tracked_target() {
        val r = parser.parse("\$RATTM,11,11.4,13.6,T,7.0,20.0,T,1.2,3.0,N,TGT1,T,,123456,A*6D")
                as ParsedSentence.TargetUpdate
        val t = r.target
        assertEquals("TT-11", t.id)
        assertEquals(TargetSource.RADAR_TT, t.source)
        assertEquals(11.4, t.rangeNm, tol)       // N units -> already nm
        assertEquals(13.6, t.bearingDeg, tol)
        assertTrue(t.trueBearing)                // field 4 == "T"
        assertEquals(7.0, t.speedKn!!, tol)
        assertEquals(20.0, t.courseDeg!!, tol)
        assertEquals(1.2, t.cpaNm!!, tol)
        assertEquals(180.0, t.tcpaSec!!, tol)    // 3.0 min -> 180 s
        assertEquals(TargetStatus.TRACKED, t.status)
    }

    @Test fun ttm_km_units_convert_to_nm() {
        // units field 'K' (km / km·h^-1) -> nm / kn. distance 18.52 km == 10 nm.
        val r = parser.parse("\$RATTM,02,18.52,90.0,T,37.04,45.0,T,,,K,,T,,123456,A*2F")
                as ParsedSentence.TargetUpdate
        assertEquals(10.0, r.target.rangeNm, 1e-6)
        assertEquals(20.0, r.target.speedKn!!, 1e-6)   // 37.04 km/h -> 20 kn
    }

    // ---- Alerts (§8.3.14 ALF, §8.3.15 ALR) ----------------------------------------------------

    @Test fun alf_alert_maps_to_alarm_event() {
        val r = parser.parse("\$RAALF,1,1,0,160012.00,A,A,V,,3044,1,1,0,Dangerous target*68")
                as ParsedSentence.AlertUpdate
        assertEquals(3044, r.alarm.identifier)            // §8.3.14 comment 8) alert identifier
        assertEquals(AlarmPriority.ALARM, r.alarm.priority)      // field 6 == "A"
        assertEquals(AlarmState.ACTIVE_UNACK, r.alarm.state)     // field 7 == "V"
        assertEquals("Dangerous target", r.alarm.text)
        assertEquals("RA", r.alarm.source)
    }

    @Test fun alr_legacy_alarm() {
        val r = parser.parse("\$RAALR,160012,030,A,V,Lost target*55") as ParsedSentence.AlertUpdate
        assertEquals(30, r.alarm.identifier)
        assertEquals(AlarmState.ACTIVE_UNACK, r.alarm.state)
        assertEquals("Lost target", r.alarm.text)
    }

    // ---- AIS (§8.3.114 VDM / §8.3.115 VDO, ITU-R M.1371-5 §3.3) -------------------------------

    @Test fun vdm_type1_position_report_decodes_core_fields() {
        val r = parser.parse("!AIVDM,1,1,,A,19NSTL@03=Jrw>0HDIH3N`Up0000,0*21")
                as ParsedSentence.AisPositionReport
        assertEquals(1, r.messageType)
        assertEquals(636019825L, r.mmsi)
        assertEquals(42.5, r.latitude!!, 1e-5)
        assertEquals(-71.0, r.longitude!!, 1e-5)
        assertEquals(89.0, r.cogDeg!!, tol)
        assertEquals(20.5, r.sogKn!!, tol)
        assertEquals(274.0, r.headingDeg!!, tol)
    }

    @Test fun vdo_own_vessel_maps_to_own_ship() {
        val r = parser.parse("!AIVDO,1,1,,A,19NSTL@03=Jrw>0HDIH3N`Up0000,0*23")
                as ParsedSentence.OwnShipUpdate
        assertEquals(42.5, r.data.latitude!!, 1e-5)
        assertEquals(-71.0, r.data.longitude!!, 1e-5)
        assertEquals(20.5, r.data.sogKn!!, tol)
        assertEquals(274.0, r.data.headingDeg!!, tol)
        assertEquals(true, r.data.sourceValidity[SensorKind.AIS])
    }

    @Test fun multi_fragment_vdm_is_deferred_not_parsed() {
        val r = parser.parse("!AIVDM,2,1,3,A,55?MbV02;H;s<HtKR20EHE:0@T4@Dn2222222216L,0*31")
        assertTrue(r is ParsedSentence.Unsupported)
        assertEquals(1, parser.stats.unsupported)
        assertEquals(0, parser.stats.parsed)
    }

    // ---- Talker tolerance & unsupported framework ---------------------------------------------

    @Test fun talker_id_is_tolerant() {
        // Same HDT payload under an unusual talker still parses (talker captured verbatim).
        val r = parser.parse("\$XXHDT,123.4,T*26") as ParsedSentence.OwnShipUpdate
        assertEquals("XX", r.talker)
        assertEquals(123.4, r.data.headingDeg!!, tol)
    }

    @Test fun recognised_but_deferred_formatter_returns_unsupported_with_clause() {
        // TXT remains deferred (W5-A implemented RSD/TLL/etc., so use a still-deferred formatter).
        val r = parser.parse("\$RATXT,01,01,01,TEST*5C")
        assertTrue(r is ParsedSentence.Unsupported)
        assertTrue((r as ParsedSentence.Unsupported).note.contains("§8.3.110"))
        assertEquals(1, parser.stats.unsupported)
        assertEquals(0, parser.stats.parsed)
    }

    @Test fun unknown_formatter_returns_unsupported() {
        val r = parser.parse("\$GPZZZ,1,2,3*51")
        assertTrue(r is ParsedSentence.Unsupported)
    }
}
