package com.shipradar.comms.iec450

import com.shipradar.comms.iec450.Iec450Fixtures.frame
import com.shipradar.comms.iec450.Iec450Fixtures.rawFrame
import com.shipradar.comms.iec450.Iec450Fixtures.sentence
import com.shipradar.comms.iec450.Iec450Fixtures.sentenceBadChecksum
import com.shipradar.comms.iec450.Iec450Fixtures.tag
import com.shipradar.comms.iec450.Iec450Fixtures.tagBadChecksum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transport-layer behaviour of [Iec450FrameParser]. Assertions check only the extracted raw
 * sentence string and the source/sequence/group labels — never sentence-internal fields (T1.5).
 */
class Iec450FrameParserTest {

    private val parser = Iec450FrameParser()

    // --- happy path --------------------------------------------------------------------------

    @Test
    fun `single sentence with source tag is extracted verbatim with label`() {
        val sen = sentence("GPGLL,5057.970,N,00146.110,E,142451,A")
        val result = parser.parse(frame(tag("s:GP0001") + sen), Iec450Group.NAVD)

        assertEquals(1, result.sentences.size)
        val ts = result.sentences[0]
        assertEquals(Iec450Group.NAVD, ts.group)
        assertEquals("GP0001", ts.sourceId)
        assertNull(ts.sequence)
        assertNull(ts.grouping)
        assertEquals(sen, ts.rawSentence) // verbatim, incl. $..*hh, no <CR><LF>
        assertEquals(0, result.discards.total)
    }

    @Test
    fun `line-count n is exposed as sequence`() {
        val result = parser.parse(
            frame(tag("s:TI0001,n:333") + sentence("TIROT,123.45")),
            Iec450Group.SATD,
        )
        assertEquals(333, result.sentences.single().sequence)
        assertEquals("TI0001", result.sentences.single().sourceId)
    }

    @Test
    fun `multiple sentences in one datagram all extracted in order`() {
        val s1 = sentence("HEHDT,90.0,T")
        val s2 = sentence("GPVTG,90.0,T,89.0,M,10.0,N,18.5,K,A", encapsulated = false)
        val s3 = sentence("AIVDM,1,1,,A,13aGt0PP00PD;88MD5MTDww@2D7k,0", encapsulated = true)
        val result = parser.parse(
            frame(
                tag("s:HE0001") + s1,
                tag("s:GP0001") + s2,
                tag("s:AI0001") + s3,
            ),
            Iec450Group.NAVD,
        )
        assertEquals(listOf(s1, s2, s3), result.sentences.map { it.rawSentence })
        assertEquals(listOf("HE0001", "GP0001", "AI0001"), result.sentences.map { it.sourceId })
    }

    // --- standard-grounded TAG/sentence checksum + grouping ----------------------------------

    @Test
    fun `verbatim example line from the standard parses with its published checksums and labels`() {
        // Exact line from IEC 61162-450 ED3 §7.2.3.7, with the standard's own *6B (TAG) and *67
        // (sentence) checksums — a conformance cross-check of the XOR implementation (§B.4 / 61162-1)
        // against the published values. Exercises g + s + n + sentence in one line.
        val line = "\\g:1-2-34,s:TI0001,n:333*6B\\\$TIROT,123.45*67"
        val result = parser.parse(rawFrame(line + "\r\n"), Iec450Group.SATD)

        assertEquals(0, result.discards.total)
        val ts = result.sentences.single()
        assertEquals("\$TIROT,123.45*67", ts.rawSentence)
        assertEquals("TI0001", ts.sourceId)
        assertEquals(333, ts.sequence)
        assertEquals(SentenceGroup(1, 2, 34), ts.grouping)
    }

    // --- source resolution (§7.2.3.2 / §7.2.3.4) ---------------------------------------------

    @Test
    fun `with two conformant s the one closest to the sentence wins`() {
        // \s:AC1000\\s:AI0001\!... -> AI0001 (closest to sentence) per §7.2.3.2.
        val sen = sentence("BSVDM,1,1,,A,3Cu>2", encapsulated = true)
        val result = parser.parse(
            frame(tag("s:AC1000") + tag("s:AI0001") + sen),
            Iec450Group.TGTD,
        )
        assertEquals("AI0001", result.sentences.single().sourceId)
    }

    @Test
    fun `non-conformant s alongside conformant s uses the conformant one`() {
        // §7.2.3.2: conformant BC1000 accepted, non-conformant 002300000 ignored.
        val sen = sentence("BSVDM,1,1,,A,3Cu>2", encapsulated = true)
        val result = parser.parse(
            frame(tag("d:AB0001,s:BC1000") + tag("s:002300000") + sen),
            Iec450Group.TGTD,
        )
        assertEquals("BC1000", result.sentences.single().sourceId)
        assertEquals(0, result.discards.total)
    }

    @Test
    fun `sentence with only a non-conformant s is ignored and counted`() {
        // §7.2.3.4: received messages without any known/conformant s shall be ignored.
        val sen = sentence("BSVDM,1,1,,A,3Cu>2", encapsulated = true)
        val result = parser.parse(frame(tag("s:002300000") + sen), Iec450Group.TGTD)

        assertTrue(result.sentences.isEmpty())
        assertEquals(1, result.discards.ignoredNoSource)
    }

    @Test
    fun `sentence with no tag block at all is ignored (no source)`() {
        val result = parser.parse(frame(sentence("HEHDT,90.0,T")), Iec450Group.SATD)
        assertTrue(result.sentences.isEmpty())
        assertEquals(1, result.discards.ignoredNoSource)
    }

    @Test
    fun `tag-only line emits nothing and is not an error`() {
        // Grouping/auth continuation line with no sentence (§B.5 case 1).
        val sen = sentence("HEHDT,90.0,T")
        val result = parser.parse(
            frame(tag("g:1-1-7,s:HE0001") /* tag-only */, tag("s:HE0001") + sen),
            Iec450Group.SATD,
        )
        assertEquals(1, result.sentences.size)
        assertEquals(0, result.discards.total)
    }

    // --- size / header rules -----------------------------------------------------------------

    @Test
    fun `oversized datagram is discarded (section 6_2_4)`() {
        val big = ByteArray(Iec450FrameParser.MAX_DATAGRAM_BYTES + 1) { 'A'.code.toByte() }
        val result = parser.parse(big, Iec450Group.NAVD)
        assertTrue(result.sentences.isEmpty())
        assertEquals(1, result.discards.oversized)
    }

    @Test
    fun `datagram exactly at the size limit is not rejected for size`() {
        val atLimit = ByteArray(Iec450FrameParser.MAX_DATAGRAM_BYTES)
        // Not a valid header, so it will be an invalidHeader discard — but NOT oversized.
        val result = parser.parse(atLimit, Iec450Group.NAVD)
        assertEquals(0, result.discards.oversized)
    }

    @Test
    fun `unknown header is discarded and counted (section 7_1_1)`() {
        val result = parser.parse(rawFrame("\\s:GP0001*XX\\\$x*00\r\n", header = "BoGuS"), Iec450Group.NAVD)
        assertTrue(result.sentences.isEmpty())
        assertEquals(1, result.discards.invalidHeader)
    }

    @Test
    fun `header without trailing null is invalid`() {
        // "UdPbC" with a non-null 6th byte.
        val bytes = "UdPbCX".toByteArray(Charsets.ISO_8859_1) +
            (tag("s:GP0001") + sentence("HEHDT,90,T") + "\r\n").toByteArray(Charsets.ISO_8859_1)
        val result = parser.parse(bytes, Iec450Group.NAVD)
        assertEquals(1, result.discards.invalidHeader)
    }

    @Test
    fun `frame shorter than header is invalid header`() {
        val result = parser.parse(byteArrayOf('U'.code.toByte(), 'd'.code.toByte()), Iec450Group.NAVD)
        assertEquals(1, result.discards.invalidHeader)
    }

    @Test
    fun `valid non-sentence header (binary file) is reported out of scope`() {
        val result = parser.parse(rawFrame("anything", header = "RaUdP"), Iec450Group.NAVD)
        assertTrue(result.sentences.isEmpty())
        assertEquals(1, result.discards.nonSentenceDatagram)
    }

    // --- malformed TAG / sentence => whole-datagram discard (§7.2.4) -------------------------

    @Test
    fun `tag block with bad checksum discards the whole datagram`() {
        val sen = sentence("HEHDT,90.0,T")
        val good = tag("s:HE0001") + sentence("HEHDT,91.0,T")
        val bad = tagBadChecksum("s:HE0002") + sen
        val result = parser.parse(frame(good, bad), Iec450Group.SATD)

        assertTrue(result.sentences.isEmpty(), "whole datagram must be discarded on any error")
        assertEquals(1, result.discards.tagChecksumError)
    }

    @Test
    fun `tag block without closing delimiter is a framing error`() {
        val result = parser.parse(rawFrame("\\s:GP0001\$GPGLL,1,N*00\r\n"), Iec450Group.NAVD)
        assertEquals(1, result.discards.tagFramingError)
    }

    @Test
    fun `tag block exceeding 80 chars is a syntax error`() {
        val longValue = "t:" + "x".repeat(90)
        val result = parser.parse(frame(tag(longValue) + sentence("HEHDT,90,T")), Iec450Group.NAVD)
        assertEquals(1, result.discards.tagSyntaxError)
    }

    @Test
    fun `tag block missing checksum delimiter is a format error`() {
        // Content with no '*hh' tail.
        val result = parser.parse(rawFrame("\\s:GP0001\\\$GPGLL,1,N*00\r\n"), Iec450Group.NAVD)
        assertEquals(1, result.discards.tagFormatError)
    }

    @Test
    fun `sentence with bad checksum discards the whole datagram`() {
        val result = parser.parse(
            frame(tag("s:GP0001") + sentenceBadChecksum("GPGLL,5057.970,N")),
            Iec450Group.NAVD,
        )
        assertTrue(result.sentences.isEmpty())
        assertEquals(1, result.discards.sentenceError)
    }

    @Test
    fun `sentence longer than 79 chars is a sentence error`() {
        val result = parser.parse(
            frame(tag("s:GP0001") + sentence("GPGLL," + "9".repeat(90))),
            Iec450Group.NAVD,
        )
        assertEquals(1, result.discards.sentenceError)
    }

    @Test
    fun `truncated datagram (no terminator, broken sentence) is discarded not crashed`() {
        // Simulates a VPN-truncated frame: valid TAG block + a partial sentence with no *hh / CRLF.
        val result = parser.parse(rawFrame(Iec450Fixtures.tag("s:GP0001") + "\$GPGLL,5057.9"), Iec450Group.NAVD)
        assertTrue(result.sentences.isEmpty())
        assertEquals(1, result.discards.sentenceError)
    }

    @Test
    fun `garbage between tag block and sentence is a syntax error`() {
        val result = parser.parse(frame(Iec450Fixtures.tag("s:GP0001") + "XYZ"), Iec450Group.NAVD)
        assertEquals(1, result.discards.tagSyntaxError)
    }

    // --- accumulation helper -----------------------------------------------------------------

    @Test
    fun `discard counters accumulate field-wise`() {
        val a = Iec450DiscardCounters(oversized = 1, sentenceError = 2)
        val b = Iec450DiscardCounters(oversized = 3, invalidHeader = 1)
        val sum = a + b
        assertEquals(4, sum.oversized)
        assertEquals(2, sum.sentenceError)
        assertEquals(1, sum.invalidHeader)
        assertEquals(7, sum.total)
    }

    @Test
    fun `empty payload datagram yields no sentences and no errors`() {
        val result = parser.parse(rawFrame(""), Iec450Group.NAVD)
        assertTrue(result.sentences.isEmpty())
        assertEquals(0, result.discards.total)
    }
}
