package com.shipradar.comms.iec61162

import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.SensorKind
import com.shipradar.contract.TargetStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W5-A: secondary IEC 61162-1 ED6 §8.3 sentences. Each test uses a standard-format sample with a
 * computed §7.2.4 checksum and asserts the field-by-field mapping onto `contract.*` / the typed
 * intents. (Functional T1.5 suite = [Iec61162ParserTest]; audit = [Iec61162AuditTest].)
 */
class Iec61162SecondaryTest {

    private val parser = Iec61162Parser()
    private val tol = 1e-6

    // ---- Heading / own-ship / speed -----------------------------------------------------------

    @Test fun hdg_magnetic_to_true_heading() {
        // §8.3.51: sensor 98.3, deviation 2.1°W, variation 7.2°E -> true = 98.3 −2.1 +7.2 = 103.4.
        val r = parser.parse("\$HCHDG,98.3,2.1,W,7.2,E*64") as ParsedSentence.OwnShipUpdate
        assertEquals(103.4, r.data.headingDeg!!, tol)
        assertTrue(r.data.headingTrue) // variation applied -> true heading
        assertEquals(true, r.data.sourceValidity[SensorKind.HEADING])
    }

    @Test fun osd_own_ship_data() {
        // §8.3.75: heading 35.1 (valid), course 36.0, speed 10.2 kn (units N).
        val r = parser.parse("\$RAOSD,35.1,A,36.0,M,10.2,P,,,N*6A") as ParsedSentence.OwnShipUpdate
        assertEquals(35.1, r.data.headingDeg!!, tol)
        assertEquals(36.0, r.data.cogDeg!!, tol)
        assertEquals(10.2, r.data.sogKn!!, tol)
        assertEquals(true, r.data.sourceValidity[SensorKind.HEADING])
    }

    @Test fun vbw_ground_speed_is_sog() {
        // §8.3.113: SOG = longitudinal ground speed (field 4) = 10.5 kn, ground status A.
        val r = parser.parse("\$VDVBW,10.1,0.5,A,10.5,0.4,A,,,,*54") as ParsedSentence.OwnShipUpdate
        assertEquals(10.5, r.data.sogKn!!, tol)
        assertEquals(true, r.data.sourceValidity[SensorKind.COG_SOG])
    }

    // ---- Targets / radar ----------------------------------------------------------------------

    @Test fun tll_target_lat_lon() {
        // §8.3.103: target 02 at 35°N 125°E, name TGT, status T(tracking).
        val r = parser.parse("\$RATLL,02,3500.000,N,12500.000,E,TGT,123519,T,*4C")
                as ParsedSentence.TargetGeoUpdate
        assertEquals("02", r.targetNumber)
        assertEquals("TGT", r.name)
        assertEquals(35.0, r.latitude!!, 1e-6)
        assertEquals(125.0, r.longitude!!, 1e-6)
        assertEquals(TargetStatus.TRACKED, r.status)
        assertEquals(false, r.isReference)
    }

    @Test fun tlb_target_labels() {
        // §8.3.102: number/label pairs.
        val r = parser.parse("\$RATLB,1,TARGET1,2,TGT2*1F") as ParsedSentence.TargetLabels
        assertEquals(mapOf("1" to "TARGET1", "2" to "TGT2"), r.labels)
    }

    @Test fun rsd_radar_system_data_cursor_ebl_vrm() {
        // §8.3.87: VRM1 12 NM, EBL1 135°, VRM2 6 NM, EBL2 90°, cursor 3.5 NM @45°, scale 12 NM,
        // units N(nm), rotation N(north-up).
        val r = parser.parse("\$RARSD,0.0,0.0,12.0,135.0,0.0,0.0,6.0,90.0,3.5,045.0,12.0,N,N*6B")
                as ParsedSentence.RadarSystemDataUpdate
        assertEquals(12.0, r.data.vrm1Nm!!, tol)
        assertEquals(135.0, r.data.ebl1Deg!!, tol)
        assertEquals(6.0, r.data.vrm2Nm!!, tol)
        assertEquals(90.0, r.data.ebl2Deg!!, tol)
        assertEquals(3.5, r.data.cursorRangeNm!!, tol)
        assertEquals(45.0, r.data.cursorBearingDeg!!, tol)
        assertEquals(12.0, r.data.rangeScaleNm!!, tol)
        assertEquals(DisplayOrientation.NORTH_UP, r.data.orientation)
    }

    @Test fun rsd_km_units_convert_to_nm() {
        // units 'K' (km) -> nm: 18.52 km == 10 nm for the range-scale field.
        val r = parser.parse(withChecksum("RARSD,,,,,,,,,,,18.52,K,H")) as ParsedSentence.RadarSystemDataUpdate
        assertEquals(10.0, r.data.rangeScaleNm!!, 1e-6)
        assertEquals(DisplayOrientation.HEAD_UP, r.data.orientation)
    }

    // ---- Display / supervision ----------------------------------------------------------------

    @Test fun ddc_display_dimming() {
        // §8.3.26: mode Night, brightness 50%, palette Night.
        val r = parser.parse("\$RADDC,N,50,N,C*16") as ParsedSentence.DisplayDimming
        assertEquals(DimMode.NIGHT, r.mode)
        assertEquals(50, r.brightnessPercent)
        assertEquals(DimMode.NIGHT, r.palette)
    }

    @Test fun hbt_heartbeat() {
        // §8.3.49: interval 30 s, equipment status A (alive).
        val r = parser.parse("\$RAHBT,30.0,A,1*0C") as ParsedSentence.Heartbeat
        assertEquals(30.0, r.intervalSec!!, tol)
        assertTrue(r.alive)
    }

    @Test fun hbt_error_status_is_not_alive() {
        val r = parser.parse(withChecksum("RAHBT,30.0,V,1")) as ParsedSentence.Heartbeat
        assertTrue(!r.alive)
    }

    // ---- Alerts / commands (ALC / ACN / ACK / ARC), 62923-2 priority --------------------------

    @Test fun alc_cyclic_alert_list_with_catalogue_priority() {
        // §8.3.13: 2 entries — 3044 (ALARM per 62923-2 Table A.1) inst1 rev5; 3052 (WARNING) inst2 rev3.
        val r = parser.parse("\$RAALC,01,01,,2,RA,3044,1,5,RA,3052,2,3*6D") as ParsedSentence.AlertListUpdate
        assertEquals(2, r.entries.size)
        assertEquals(3044, r.entries[0].identifier)
        assertEquals(1, r.entries[0].instance)
        assertEquals(5, r.entries[0].revisionCounter)
        assertEquals(AlarmPriority.ALARM, r.entries[0].priority)     // 62923-2 → priority
        assertEquals(3052, r.entries[1].identifier)
        assertEquals(AlarmPriority.WARNING, r.entries[1].priority)
    }

    @Test fun acn_alert_command_intent() {
        // §8.3.7: acknowledge (A) of alert 3044 instance 1.
        val r = parser.parse("\$RAACN,123519,RA,3044,1,A,C*71") as ParsedSentence.AlertCommandReceived
        assertEquals(3044, r.command.identifier)
        assertEquals(1, r.command.instance)
        assertEquals(AlertCommandKind.ACKNOWLEDGE, r.command.kind)
        assertEquals("RA", r.command.manufacturer)
    }

    @Test fun acn_responsibility_transfer_and_silence_codes() {
        val o = parser.parse(withChecksum("RAACN,000000,RA,3044,1,O,C")) as ParsedSentence.AlertCommandReceived
        assertEquals(AlertCommandKind.RESPONSIBILITY_TRANSFER, o.command.kind)
        val s = parser.parse(withChecksum("RAACN,000000,RA,3044,1,S,C")) as ParsedSentence.AlertCommandReceived
        assertEquals(AlertCommandKind.SILENCE, s.command.kind)
    }

    @Test fun ack_legacy_acknowledge() {
        // §8.3.6: legacy ACK of alarm number 42 -> acknowledge command.
        val r = parser.parse("\$RAACK,042*40") as ParsedSentence.AlertCommandReceived
        assertEquals(42, r.command.identifier)
        assertEquals(AlertCommandKind.ACKNOWLEDGE, r.command.kind)
        assertNull(r.command.instance)
    }

    @Test fun arc_alert_command_refused() {
        // §8.3.17: refusal of a silence (S) on alert 3044.
        val r = parser.parse("\$RAARC,123519,RA,3044,1,S*10") as ParsedSentence.AlertCommandRefused
        assertEquals(3044, r.refusal.identifier)
        assertEquals(1, r.refusal.instance)
        assertEquals(AlertCommandKind.SILENCE, r.refusal.refused)
    }

    @Test fun alr_priority_resolved_from_catalogue() {
        // §8.3.15 legacy: alarm number 3052 is a known 62923-2 id -> WARNING (not the default).
        val r = parser.parse(withChecksum("RAALR,123519,3052,A,V,Lost target")) as ParsedSentence.AlertUpdate
        assertEquals(AlarmPriority.WARNING, r.alarm.priority)
        assertEquals(3052, r.alarm.identifier)
    }

    // ---- still-deferred AIS static (skipped per W5-A scope) ------------------------------------

    @Test fun ais_static_formatters_remain_deferred() {
        val ssd = parser.parse(withChecksum("AISSD,@@@@@@@,@@,0,0,0,0,0,AI"))
        assertTrue(ssd is ParsedSentence.Unsupported)
        assertTrue((ssd as ParsedSentence.Unsupported).note.contains("static"))
    }

    /** Append the §7.2.4 XOR checksum to a sentence body (between, excl., "$" and "*"). */
    private fun withChecksum(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return "\$" + body + "*" + cs.toString(16).uppercase().padStart(2, '0')
    }
}
