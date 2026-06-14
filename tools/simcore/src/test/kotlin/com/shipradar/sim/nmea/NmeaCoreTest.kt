package com.shipradar.sim.nmea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 校验和与句框架核心测试,用外部公认的标准句向量验证 XOR 校验和实现无误。 */
class NmeaCoreTest {

    /** 公认的 NMEA-0183 参考句(校验和外部已知),验证 [Nmea.checksum] 正确。 */
    @Test fun checksum_matches_known_reference_sentences() {
        assertEquals("47", Nmea.checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
        assertEquals("6A", Nmea.checksum("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"))
        assertEquals("31", Nmea.checksum("GPGLL,4916.45,N,12311.12,W,225444,A"))
        assertEquals("48", Nmea.checksum("GPVTG,054.7,T,034.4,M,005.5,N,010.2,K"))
    }

    @Test fun sentence_wraps_with_dollar_star_checksum_crlf() {
        val s = Nmea.sentence("HE", "HDT,274.1,T")
        assertTrue(s.startsWith("\$HEHDT,274.1,T*"))
        assertTrue(s.endsWith("\r\n"))
        assertEquals(Nmea.checksum("HEHDT,274.1,T"), s.substringAfter('*').substringBefore('\r'))
    }

    @Test fun checksum_is_two_uppercase_hex() {
        val cs = Nmea.checksum("GNTHS,000.0,A")
        assertEquals(2, cs.length)
        assertTrue(cs.all { it in '0'..'9' || it in 'A'..'F' }, "应为大写十六进制: $cs")
    }

    @Test fun latitude_longitude_field_format() {
        assertEquals("4830.0000,N", Nmea.lat(48.5))
        assertEquals("4830.0000,S", Nmea.lat(-48.5))
        assertEquals("01115.0000,E", Nmea.lon(11.25))
        assertEquals("01115.0000,W", Nmea.lon(-11.25))
    }

    @Test fun time_and_date_fields() {
        val t = UtcTime(12, 35, 19.0, day = 23, month = 3, year = 1994)
        assertEquals("123519.00", Nmea.hms(t))
        assertEquals("230394", Nmea.ddmmyy(t))
    }

    @Test fun deg360_normalises_into_range() {
        assertEquals("0.1", Nmea.deg360(360.1))
        assertEquals("350.0", Nmea.deg360(-10.0))
    }
}
