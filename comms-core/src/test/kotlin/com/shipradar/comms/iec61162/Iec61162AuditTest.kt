package com.shipradar.comms.iec61162

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4-F certification audit (SENS-01): field-order / unit / checksum verification of the IEC 61162-1
 * ED6 §8.3 sentence set against the standard, plus AIS six-bit de-armour completion.
 *
 * Distinct from [Iec61162ParserTest] (the T1.5 functional suite) — this file holds the audit
 * evidence: the standard's OWN worked-example sentences, controlled AIS round-trips per
 * ITU-R M.1371-5 §3.3 bit layout, and the explicit "deferred AIS type" behaviour.
 */
class Iec61162AuditTest {

    private val parser = Iec61162Parser()
    private val tol = 1e-6

    // ---- The standard's own worked examples (IEC 61162-1 ED6 §7.2.4) ---------------------------

    @Test fun standard_gll_example_parses() {
        // Verbatim from §7.2.4: "$GPGLL,5057.970,N,00146.110,E,142451,A*27".
        val r = parser.parse("\$GPGLL,5057.970,N,00146.110,E,142451,A*27") as ParsedSentence.OwnShipUpdate
        assertEquals(50.96616667, r.data.latitude!!, 1e-6)   // 50° 57.970'
        assertEquals(1.76850000, r.data.longitude!!, 1e-6)   // 001° 46.110'
        assertEquals(true, r.data.sourceValidity[com.shipradar.contract.SensorKind.POSITION])
        assertEquals(0, parser.stats.checksumFailures)
    }

    @Test fun standard_vtg_example_parses() {
        // Verbatim from §7.2.4: "$GPVTG,089.0,T,,,15.2,N,,,*53" (note: null mode field, older form).
        val r = parser.parse("\$GPVTG,089.0,T,,,15.2,N,,,*53") as ParsedSentence.OwnShipUpdate
        assertEquals(89.0, r.data.cogDeg!!, tol)
        assertEquals(15.2, r.data.sogKn!!, tol)
    }

    // ---- AIS Class B position reports (ITU-R M.1371-5 §3.3, Message 18 / 19) -------------------
    // Controlled round-trips: the payload was encoded field-by-field at the standard's bit offsets,
    // so a correct decode must reproduce the exact MMSI / lat / lon / COG / SOG / heading.

    @Test fun ais_type18_class_b_position_report() {
        val r = parser.parse("!AIVDM,1,1,,B,B4eGrSP0NmjirP72qK23UaP00000,0*58")
                as ParsedSentence.AisPositionReport
        assertEquals(18, r.messageType)
        assertEquals(316013198L, r.mmsi)
        assertEquals(49.25, r.latitude!!, 1e-5)
        assertEquals(-123.4, r.longitude!!, 1e-5)
        assertEquals(210.5, r.cogDeg!!, tol)
        assertEquals(12.3, r.sogKn!!, tol)
        assertEquals(211.0, r.headingDeg!!, tol)
        assertEquals(null, r.navStatus) // Class B carries no navigation status
    }

    @Test fun ais_type19_extended_class_b_position_report() {
        val r = parser.parse("!AIVDM,1,1,,B,C69?UCP0AR;2s@4Ma@0sHg000000000000000000000000000000,0*3D")
                as ParsedSentence.AisPositionReport
        assertEquals(19, r.messageType)
        assertEquals(412345678L, r.mmsi)
        assertEquals(31.2, r.latitude!!, 1e-5)
        assertEquals(121.5, r.longitude!!, 1e-5)
        assertEquals(95.0, r.cogDeg!!, tol)
        assertEquals(7.0, r.sogKn!!, tol)
        assertEquals(94.0, r.headingDeg!!, tol)
    }

    @Test fun ais_rate_of_turn_uses_m1371_inverse_formula() {
        // ROTAIS = 4.733·sqrt(rate) -> rate = (ROTAIS/4.733)^2. ROTAIS=18 -> 14.4635 °/min.
        val r = parser.parse("!AIVDM,1,1,,A,13HOI:04P0000000000000000000,0*34")
                as ParsedSentence.AisPositionReport
        assertEquals(227006760L, r.mmsi)
        assertEquals(14.4635, r.rotDegMin!!, 1e-3)
    }

    // ---- Deferred / gap behaviour (explicit, not silent) --------------------------------------

    @Test fun ais_undecoded_message_type_is_reported_with_its_type() {
        // Type 4 (base-station report) is a valid AIS message but not a position report -> deferred.
        val r = parser.parse("!AIVDM,1,1,,A,403Owph000000000000000000000,0*01")
        assertTrue(r is ParsedSentence.Unsupported)
        assertTrue((r as ParsedSentence.Unsupported).note.contains("type 4"))
        assertEquals(1, parser.stats.unsupported)
        assertEquals(0, parser.stats.parsed)
    }

    @Test fun ais_dearmour_failure_is_distinguished_from_unknown_formatter() {
        // Fill-bits = 7 is out of the §7.3.4.2 range 0..5 -> de-armour fails, reported distinctly.
        val r = parser.parse("!AIVDM,1,1,,A,15M,7*68")
        assertTrue(r is ParsedSentence.Unsupported)
        assertTrue((r as ParsedSentence.Unsupported).note.contains("de-armour"))
    }

    // ---- §7.3.1 length boundary ----------------------------------------------------------------

    @Test fun length_ceiling_rejects_grossly_overlong_lines() {
        // A line far over the §7.3.1 limit is malformed regardless of a (here bogus) checksum tail.
        val tooLong = "\$GP" + "A".repeat(90) + "*00"
        assertEquals(null, parser.parse(tooLong))
        assertTrue(parser.stats.malformed >= 1)
    }
}
