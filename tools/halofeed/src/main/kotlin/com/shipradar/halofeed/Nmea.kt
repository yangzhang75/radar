package com.shipradar.halofeed

import com.shipradar.constants.Endpoint
import com.shipradar.constants.Iec450Groups
import com.shipradar.contract.OwnShipData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Own-ship is NOT carried over the HALO radar protocol — it is fused from IEC 61162 (NMEA-0183)
 * sentences (HDT/THS heading, GGA/RMC position, VTG course/speed, ROT). This builder emits valid
 * NMEA-0183 sentences so the 61162 parser (T1.4) can replay own-ship offline. Sentences are routed
 * to the IEC 61162-450 transport groups in [com.shipradar.constants.Iec450Groups].
 *
 * TODO(待协议): the raw NMEA sentence is sent as the UDP payload. The full 61162-450 ED3 framing
 * (UdPbC\0 tag + TAG block with source id / line count) is NOT applied here — confirm against
 * 80-1094-IEC 61162-450 ED3 (T1.4 owns that) and wrap if the parser requires it. The sentence bodies
 * and XOR checksums themselves are standard NMEA-0183 and correct.
 */
object Nmea {
    /** NMEA-0183 checksum: XOR of every char between '$'/'!' and '*'. Returns the full "$BODY*HH\r\n". */
    fun sentence(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return "\$%s*%02X\r\n".format(Locale.ROOT, body, cs)
    }

    /** GGA — GPS fix (position + time). */
    fun gga(utcSecondsOfDay: Double, lat: Double, lon: Double): String =
        sentence("GPGGA,${hms(utcSecondsOfDay)},${latField(lat)},${lonField(lon)},1,08,1.0,0.0,M,0.0,M,,")

    /** HDT — true heading. */
    fun hdt(headingDeg: Double): String =
        sentence("HEHDT,%.1f,T".format(Locale.ROOT, headingDeg))

    /** VTG — course over ground + speed. */
    fun vtg(cogDeg: Double, sogKn: Double): String =
        sentence("GPVTG,%.1f,T,,M,%.1f,N,%.1f,K".format(Locale.ROOT, cogDeg, sogKn, sogKn * 1.852))

    /** ROT — rate of turn, deg/min (+ = bow to starboard). */
    fun rot(rotDegMin: Double): String =
        sentence("TIROT,%.1f,A".format(Locale.ROOT, rotDegMin))

    /**
     * Build the set of (endpoint, sentence) datagrams for one own-ship update. Heading-class data
     * goes to SATD (high rate), position/COG/SOG to NAVD. Skips fields that are null.
     */
    fun ownShipDatagrams(s: OwnShipData, utcSecondsOfDay: Double): List<Datagram> = buildList {
        s.headingDeg?.let { add(dg(Iec450Groups.SATD, hdt(it))) }
        s.rotDegMin?.let { add(dg(Iec450Groups.SATD, rot(it))) }
        if (s.latitude != null && s.longitude != null) add(dg(Iec450Groups.NAVD, gga(utcSecondsOfDay, s.latitude!!, s.longitude!!)))
        if (s.cogDeg != null && s.sogKn != null) add(dg(Iec450Groups.NAVD, vtg(s.cogDeg!!, s.sogKn!!)))
    }

    private fun dg(ep: Endpoint, sentence: String) = Datagram(ep, sentence.toByteArray(Charsets.US_ASCII))

    private fun hms(secOfDay: Double): String {
        val s = ((secOfDay % 86400.0) + 86400.0) % 86400.0
        val h = (s / 3600).toInt(); val m = ((s % 3600) / 60).toInt(); val sec = s % 60
        return "%02d%02d%05.2f".format(Locale.ROOT, h, m, sec)
    }

    private fun latField(lat: Double): String {
        val deg = floor(abs(lat)).toInt()
        val min = (abs(lat) - deg) * 60.0
        return "%02d%07.4f,%s".format(Locale.ROOT, deg, min, if (lat >= 0) "N" else "S")
    }

    private fun lonField(lon: Double): String {
        val deg = floor(abs(lon)).toInt()
        val min = (abs(lon) - deg) * 60.0
        return "%03d%07.4f,%s".format(Locale.ROOT, deg, min, if (lon >= 0) "E" else "W")
    }
}
