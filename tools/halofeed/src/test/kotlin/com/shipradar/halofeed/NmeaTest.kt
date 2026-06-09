package com.shipradar.halofeed

import com.shipradar.constants.Iec450Groups
import com.shipradar.contract.OwnShipData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NmeaTest {

    /** Independently recompute and validate a sentence's XOR checksum. */
    private fun checksumValid(s: String): Boolean {
        val star = s.indexOf('*')
        if (!s.startsWith("$") || star < 0) return false
        var cs = 0
        for (i in 1 until star) cs = cs xor s[i].code
        val declared = s.substring(star + 1).trim().toInt(16)
        return cs == declared
    }

    @Test fun checksumMatchesCanonicalExample() {
        // Canonical NMEA-0183 example with known checksum 0x47.
        val body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertEquals("\$$body*47\r\n", Nmea.sentence(body))
    }

    @Test fun sentencesAreWellFormed() {
        assertTrue(checksumValid(Nmea.hdt(75.0)))
        assertTrue(checksumValid(Nmea.vtg(75.0, 12.0)))
        assertTrue(checksumValid(Nmea.gga(43200.0, 36.05, -5.40)))
        assertTrue(checksumValid(Nmea.rot(3.5)))
    }

    @Test fun hdtFormat() {
        val s = Nmea.hdt(123.4)
        assertTrue(s.startsWith("\$HEHDT,123.4,T*"))
        assertTrue(s.endsWith("\r\n"))
    }

    @Test fun ownShipRoutesByChannel() {
        val s = OwnShipData(latitude = 36.05, longitude = -5.40, headingDeg = 75.0,
            headingTrue = true, cogDeg = 75.0, sogKn = 12.0, rotDegMin = 0.0)
        val dgs = Nmea.ownShipDatagrams(s, 43200.0)
        // heading + rot -> SATD ; gga + vtg -> NAVD
        val satd = dgs.filter { it.endpoint == Iec450Groups.SATD }.map { String(it.payload) }
        val navd = dgs.filter { it.endpoint == Iec450Groups.NAVD }.map { String(it.payload) }
        assertTrue(satd.any { it.startsWith("\$HEHDT") })
        assertTrue(satd.any { it.startsWith("\$TIROT") })
        assertTrue(navd.any { it.startsWith("\$GPGGA") })
        assertTrue(navd.any { it.startsWith("\$GPVTG") })
    }

    @Test fun southWestHemispheres() {
        // lat negative -> S, lon negative -> W
        val gga = Nmea.gga(0.0, -10.5, -20.25)
        assertTrue(gga.contains(",S,"))
        assertTrue(gga.contains(",W,"))
    }
}
