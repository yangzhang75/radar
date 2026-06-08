package com.shipradar.comms.iec61162

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Multi-fragment AIS reassembly (§8.3.114 / §7.3.4.2). */
class AisReassemblerTest {

    @Test fun two_fragments_reassemble_into_one_payload() {
        val r = AisReassembler()
        // Fragment 1 of 2 (shared sequential message id "7").
        assertTrue(r.feed("!AIVDM,2,1,7,B,first0payload00,0*35") is AisReassembler.Result.Incomplete)
        // Fragment 2 of 2 -> complete, payloads concatenated in order, fill from last fragment.
        val done = r.feed("!AIVDM,2,2,7,B,second0payload0,0*6C")
        assertTrue(done is AisReassembler.Result.Complete)
        done as AisReassembler.Result.Complete
        assertEquals("first0payload00second0payload0", done.payload)
        assertEquals(0, done.fillBits)
    }

    @Test fun single_fragment_completes_immediately() {
        val r = AisReassembler()
        val done = r.feed("!AIVDM,1,1,,A,19NSTL@03=Jrw>0HDIH3N`Up0000,0*21")
        assertTrue(done is AisReassembler.Result.Complete)
    }
}
