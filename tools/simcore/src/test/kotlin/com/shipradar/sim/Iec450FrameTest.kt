package com.shipradar.sim

import com.shipradar.constants.Iec450Groups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Iec450FrameTest {

    @Test fun wrap_has_udpbc_tag_and_sentence() {
        val f = Iec450Frame.wrap("\$GNGGA,123519,3113.8,N,12209.0,E*4D\r\n")
        val s = String(f, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("UdPbC"), "应以 UdPbC 开头")
        assertEquals(0, f["UdPbC".length].toInt(), "UdPbC 后应有 0x00")
        assertTrue(s.contains("\\s:SI0001*"), "应含发送方 tag 块")
        assertTrue(s.contains("\$GNGGA,123519,3113.8,N,12209.0,E*4D"), "应含原始语句")
        assertTrue(s.endsWith("\r\n"), "应以 CRLF 结尾")
    }

    @Test fun routes_by_sentence_type() {
        assertEquals(Iec450Groups.TGTD, Iec450Frame.endpointFor("!AIVDM,1,1,,A,352Lfr,0*26"))
        assertEquals(Iec450Groups.SATD, Iec450Frame.endpointFor("\$HEHDT,87.0,T*10"))
        assertEquals(Iec450Groups.SATD, Iec450Frame.endpointFor("\$HETHS,87.0,A*12"))
        assertEquals(Iec450Groups.NAVD, Iec450Frame.endpointFor("\$GNGGA,123,*4D"))
        assertEquals(Iec450Groups.NAVD, Iec450Frame.endpointFor("\$WIMWV,45.0,R,14.0,N,A*27"))
    }
}
